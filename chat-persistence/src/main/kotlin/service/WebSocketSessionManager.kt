package com.chat.persistence.service


import com.chat.domain.dto.ChatMessage
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

//| **RedisMessageBroker**       | 서버 간 메시지 전달 (Redis Pub/Sub)        |
//| **WebSocketSessionManager**  | 로컬 세션 관리 + 메시지 브로드캐스트              |
//| **ChatRoomMemberRepository** | 유저가 실제 방 멤버인지 DB 확인                |
//| **RedisTemplate**            | “이 서버가 구독 중인 방 목록”을 Redis Set으로 기록 |
//| **ObjectMapper**             | 메시지(JSON) 직렬화/역직렬화                 |




// Redis Pub/Sub을 통해 들어온 메시지를 로컬 세션들에게 "브로드캐스팅"하는 책임을 가짐.
@Service
class WebSocketSessionManager(
    private val redisTemplate: RedisTemplate<String, String>,          // (운영/상태 저장) 서버별 구독 방 목록 관리에 사용 (Set)
    private val objectMapper: ObjectMapper,                            // ChatMessage <-> JSON 직렬화/역직렬화
    private val redisMessageBroker: RedisMessageBroker,                // 서버 간 메시지 중계 (publish/subscribe)
    private val chatRoomMemberRepository: ChatRoomMemberRepository,    // 유저가 방 멤버인지 DB로 검증
) {
    private val logger = LoggerFactory.getLogger(WebSocketSessionManager::class.java)

    // 로컬 서버에 연결된 유저의 WebSocket 세션들을 관리
    // - key: userId
    // - value: 해당 유저의 모든 WebSocketSession (멀티 디바이스/브라우저 탭 동시 접속 지원)
    // 동시성: ConcurrentHashMap + MutableSet (Set 자체는 동시성 컬렉션이 아니므로 수정 시 주의)
    private val userSession = ConcurrentHashMap<Long, MutableSet<WebSocketSession>>()

    // Redis 키 프리픽스: "이 서버가 현재 구독 중인 roomId 세트"
    // 실제 키는 "${serverRoomsKeyPrefix}$serverId" 형태가 됨.
    // 예: chat:server:roomsmy-server-1  (필요하면 ":" 구분자 추가를 고려: "$serverRoomsKeyPrefix:$serverId")
    private val serverRoomsKeyPrefix = "chat:server:rooms"

    /**
     * ✅ 초기화 훅 (@PostConstruct)
     *
     * - RedisMessageBroker가 Redis로부터 메시지를 수신하면 호출할 "콜백"을 등록한다.
     * - 콜백은 (roomId, ChatMessage) -> Unit 시그니처로, 수신 즉시 로컬 세션에게 메시지를 전달한다.
     * - 이로써 "브로커(서버 간 통신)"와 "세션 매니저(로컬 전송)"의 결합도를 낮추고, 교체/테스트 용이성을 확보.
     */
    @PostConstruct
    fun initialize() {
        // onMessage() -> localMessageHandler.invoke(roomId, msg) -> sendMessageToLocalRoom(roomId, msg)
        redisMessageBroker.setLocalMessageHandler { roomId, msg ->
            sendMessageToLocalRoom(roomId, msg)
        }
    }

    /**
     * ✅ 유저가 WebSocket으로 연결되었을 때 세션을 등록
     *
     * - 멀티 세션(여러 탭/디바이스)을 지원하므로 Set으로 관리
     * - computeIfAbsent: 최초 접속인 경우 Set 생성
     * - 성능/동시성:
     *   - userSession.put/compute 계열은 ConcurrentHashMap이므로 안전
     *   - 단, 반환된 MutableSet 수정 시에는 외부에서 동시에 접근 가능 → 아래에서 "닫힌 세션 정리"에서 add/remove 시 주의
     */
    fun addSession(userId: Long, session: WebSocketSession) {
        logger.info("Adding session for userId={}", userId)
        userSession.computeIfAbsent(userId) { mutableSetOf() }.add(session)
    }

    /**
     * ✅ 유저의 특정 WebSocket 세션 제거
     *
     * - 세션이 끊길 때(브라우저 종료/네트워크 단절 등) 호출
     * - 마지막 세션까지 제거되어 해당 유저가 로컬에 더 이상 없으면 userSession에서 userId 키 삭제
     * - 전체 서버 연결 유저 수가 0이면:
     *   1) 이 서버가 구독 중인 모든 roomId를 Redis에서 조회
     *   2) RedisMessageBroker.unsubscribeFromRoom(roomId) 호출로 실제 Pub/Sub 구독 해제
     *   3) 서버-방 매핑 Redis 키 삭제 (운영 상태 정리)
     *
     * 주의:
     * - "전체 서버 연결 유저 수 = 0" 판단은 이 노드 기준 (다른 서버는 각자 판단)
     * - 고립 노드(트래픽 0)에서 불필요한 구독을 유지하지 않기 위해 정리하는 목적
     */
    fun removeSession(userId: Long, session: WebSocketSession) {
        userSession[userId]?.remove(session)

        // 해당 유저가 가진 세션이 모두 사라졌다면 userSession에서 사용자 키 제거
        if (userSession[userId]?.isEmpty() == true) {
            userSession.remove(userId)

            // 이 서버에 연결된 모든 유저의 "열린" 세션 수를 합산하여, 완전히 무유저 상태인지 확인
            val totalConnectedUsers = userSession.values.sumOf { sessions ->
                sessions.count { it.isOpen }
            }

            // 이 서버가 더 이상 어떤 유저도 보유하지 않으면: Redis 구독/키 클린업
            if (totalConnectedUsers == 0) {
                val serverId = redisMessageBroker.getServerId()
                val serverRoomKey = "${serverRoomsKeyPrefix}$serverId"

                // 이 서버가 Redis에 기록해 둔 "구독 중인 방 목록"을 가져온다 (없으면 emptySet)
                val subscribedRooms = redisTemplate.opsForSet().members(serverRoomKey) ?: emptySet()

                // 모든 방에 대해 Pub/Sub 구독 해제
                subscribedRooms.forEach { roomIdStr ->
                    val roomId = roomIdStr.toLongOrNull()
                    if (roomId != null) {
                        redisMessageBroker.unsubscribeFromRoom(roomId)
                    }
                }

                // 서버-방 매핑 키 자체를 삭제
                redisTemplate.delete(serverRoomKey)
                logger.info("No more local users. Cleared subscriptions={}, key={}", subscribedRooms, serverRoomKey)
            }
        }
    }

    /**
     * ✅ 유저가 특정 채팅방에 "입장"했을 때 호출
     *
     * - 이 서버가 해당 roomId를 이미 구독 중인지 Redis Set( serverRoomsKey )에서 확인
     * - 아직 구독하지 않았다면 RedisMessageBroker.subscribeToRoom(roomId) 호출 → Pub/Sub 구독
     * - 이후 Redis Set에 roomId 추가 (운영 상태 기록)
     *
     * 논리:
     * - "누군가" 이 방에 로컬에서 참여하면 구독 필요
     * - 같은 방에 다수 유저가 입장해도 "한 번만" 구독하도록 보호
     * - 반대로 모든 유저가 나가면 removeSession()에서 정리 로직이 동작
     */
    fun joinRoom(userId: Long, roomId: Long) {
        val serverId = redisMessageBroker.getServerId()
        val serverRoomKey = "${serverRoomsKeyPrefix}$serverId"

        // 이미 구독 중인지 Redis Set에서 O(1) 체크
        val wasAlreadySubscribed = redisTemplate.opsForSet().isMember(serverRoomKey, roomId.toString()) == true

        // 아직 구독하지 않았다면 Pub/Sub 구독 시작
        if (!wasAlreadySubscribed) {
            redisMessageBroker.subscribeToRoom(roomId)
        }

        // 이 서버의 구독 방 목록(Set)에 roomId 추가 (중복 추가는 Set 특성상 무해)
        redisTemplate.opsForSet().add(serverRoomKey, roomId.toString())

        logger.info("User {} joined room {} (serverId={}, key={})", userId, roomId, serverId, serverRoomKey)
    }

    /**
     * ✅ "로컬 서버"의 WebSocket 세션으로 메시지를 브로드캐스팅
     *
     * 호출 경로:
     * - RedisMessageBroker.onMessage() → localMessageHandler.invoke(roomId, message) → 여기
     *
     * 동작:
     *  1) 메시지를 JSON 문자열로 직렬화 (WebSocket TextMessage로 전송)
     *  2) 현재 서버의 userSession 전체를 순회하며:
     *     - (옵션) excludeUserId가 아닌 유저만 대상으로
     *     - DB로 "해당 유저가 roomId의 활성 멤버인지" 검사
     *     - 해당 유저의 모든 열려있는 세션에 TextMessage 전송
     *     - 닫힌 세션은 모아두었다가 일괄 제거 (메모리 누수 방지)
     *
     * 성능/주의:
     * - N(유저) * 1(DB exists) 만큼 DB round-trip이 발생 → 트래픽이 크면 부담
     *   → 개선안: (1) 방-멤버십을 Redis 캐시/로컬 캐시로 유지, (2) userId -> joinedRooms Map 유지
     * - 세션 Set은 동시 수정될 수 있으므로, removeAll 호출 전 별도 수집(closedSessions) 후 일괄 제거
     * - payload 크기가 클 경우 네트워크/브라우저 부담 → 메시지 스키마/압축 고려
     */
    fun sendMessageToLocalRoom(roomId: Long, message: ChatMessage, excludeUserId: Long? = null) {
        // 객체 -> JSON (WebSocket TextMessage로 보낼 문자열)
        val json = objectMapper.writeValueAsString(message)

        // 이 서버에 연결된 모든 유저/세션을 순회
        userSession.forEach { (userId, sessions) ->
            // 1) 송신 제외 대상이면 skip (예: 보낸 본인에게는 회신 안 함)
            if (userId != excludeUserId) {
                // 2) DB로 멤버십 확인 (isActive=true)
                val isMember = chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)

                if (isMember) {
                    val closedSessions = mutableSetOf<WebSocketSession>()

                    // 해당 유저의 모든 세션에 전송
                    sessions.forEach { s ->
                        if (s.isOpen) { //isOpen - WebSocket 연결이 아직 “살아 있는지” (열려 있는 상태인지) 확인하는 상태 플래그
                            try {
                                s.sendMessage(TextMessage(json)) // 실제 송신 지점
                                logger.info("Sent message to user={} room={}", userId, roomId)
                            } catch (e: Exception) {
                                // 전송 중 예외(네트워크 단절 등) → 세션을 닫힌 것으로 간주하고 정리 대상에 추가
                                logger.error("Send failed to user={}, marking session closed", userId, e)
                                closedSessions.add(s)
                            }
                        } else {
                            // isOpen=false → 이미 닫힌 세션이므로 정리 대상
                            closedSessions.add(s)
                        }
                    }

                    // 3) 닫힌 세션들을 일괄 제거 (Set 수정은 루프 외부에서 수행)
                    if (closedSessions.isNotEmpty()) {
                        sessions.removeAll(closedSessions)
                    }
                } else {
                    // 멤버가 아닌 유저에게는 전송하지 않음 (권한 보호)
                    logger.debug("Skip user={} (not a member of room {})", userId, roomId)
                }
            }
        }
    }

    /**
     * ✅ 특정 유저가 "이 서버"에 온라인인지 확인
     *
     * - userSession에서 해당 userId의 세션들을 가져와 열려있는(isOpen)지 검사
     * - 닫힌 세션은 즉시 정리하여 메모리 누수 방지
     * - 반환: true(열린 세션 ≥ 1), false(세션 없음 또는 모두 닫힘)
     *
     * 주의:
     * - "이 서버" 기준의 온라인 여부만 판단 (다른 서버 접속 여부는 별도 조회 필요)
     * - 글로벌 온라인 여부는 Redis/DB 등 분산 상태 저장으로 합산 판단하는 별도 로직 필요
     */
    fun isUserOnlineLocally(userId: Long): Boolean {
        val sessions = userSession[userId] ?: return false

        // 열린 세션 목록 (브라우저 탭/디바이스 수만큼 존재 가능)
        val openSessions = sessions.filter { it.isOpen }

        // 닫힌 세션이 섞여 있으면 정리
        if (openSessions.size != sessions.size) {
            val closedSessions = sessions.filter { !it.isOpen }
            sessions.removeAll(closedSessions)

            // 정리 후 완전히 비면 userSession에서 해당 유저 제거
            if (sessions.isEmpty()) {
                userSession.remove(userId)
            }
        }

        return openSessions.isNotEmpty()
    }
}