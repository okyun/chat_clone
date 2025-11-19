package com.chat.persistence.service

import com.chat.domain.dto.*
import com.chat.domain.model.*
import com.chat.domain.service.ChatService
import com.chat.persistence.repository.*
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.service.WebSocketSessionManager
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/*
 * 이 클래스는 채팅 도메인의 핵심 유스케이스를 수행:
 * - 채팅방 생성/조회/검색/입퇴장
 * - 메시지 조회(페이지/커서)
 * - 메시지 전송(시퀀스 부여 → DB 저장 → 로컬 전송 → Redis 브로드캐스트)
 *
 * @Transactional(클래스 레벨)
 * - 기본적으로 모든 public 메서드가 트랜잭션 안에서 동작
 * - Repository의 @Modifying 쿼리/저장/조회 일관성 보장
 *
 * 캐시 어노테이션
 * - @Cacheable : 캐시에 없으면 로직 실행 후 결과 캐시에 저장, 있으면 캐시에서 바로 반환
 * - @CacheEvict: 캐시 비우기(키 지정 또는 전체)
 * - @Caching   : 여러 캐시 어노테이션 묶음
 */

@Service
@Transactional
class ChatServiceImpl(
    private val chatRoomRepository: ChatRoomRepository,
    private val messageRepository: MessageRepository,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val userRepository: UserRepository,
    private val redisMessageBroker: RedisMessageBroker,
    private val messageSequenceService: MessageSequenceService,
    private val webSocketSessionManager: WebSocketSessionManager
) : ChatService {

    private val logger = LoggerFactory.getLogger(ChatServiceImpl::class.java)

    /**
     * (주의) @Cacheable이 private 메서드에 붙어있음.
     * Spring AOP 프록시는 기본적으로 "public 메서드"의 "외부 호출"에만 적용됨.
     * - 같은 빈 내부에서 private/protected 메서드 호출 시 캐시가 동작하지 않음(셀프 인보케이션).
     * - 진짜 캐시를 쓰려면:
     *   1) public 메서드로 다른 Bean으로 분리하거나,
     *   2) 해당 DTO 매핑 로직을 호출하는 public 서비스 메서드에 @Cacheable을 붙이거나,
     *   3) 또는 self proxy를 사용(AopContext) — 일반적으론 1) 권장.
     */
    @Cacheable(value = ["chatRooms"], key = "#chatRoom.id")
    private fun chatRoomToDto(chatRoom: ChatRoom): ChatRoomDto {
        // 활성 멤버 수 계산 (카운트는 DB에서 집계)
        val memberCount = chatRoomMemberRepository.countActiveMembersInRoom(chatRoom.id).toInt()
        // 마지막 메시지 조회 후 DTO 변환 (null 가능)
        val lastMessage = messageRepository.findLatestMessage(chatRoom.id)?.let { messageToDto(it) }

        return ChatRoomDto(
            id = chatRoom.id,
            name = chatRoom.name,
            description = chatRoom.description,
            type = chatRoom.type,
            imageUrl = chatRoom.imageUrl,
            isActive = chatRoom.isActive,
            maxMembers = chatRoom.maxMembers,
            memberCount = memberCount,
            createdBy = userToDto(chatRoom.createdBy),
            createdAt = chatRoom.createdAt,
            lastMessage = lastMessage
        )
    }

    /** 메시지 엔티티 → API 응답용 DTO 매핑 */
    private fun messageToDto(message: Message): MessageDto {
        return MessageDto(
            id = message.id,
            chatRoomId = message.chatRoom.id,
            sender = userToDto(message.sender),
            type = message.type,
            content = message.content,
            isEdited = message.isEdited,
            isDeleted = message.isDeleted,
            createdAt = message.createdAt,
            editedAt = message.editedAt,
            sequenceNumber = message.sequenceNumber
        )
    }

    /** 채팅방 멤버 엔티티 → DTO 매핑 */
    private fun memberToDto(member: ChatRoomMember): ChatRoomMemberDto {
        return ChatRoomMemberDto(
            id = member.id,
            user = userToDto(member.user),
            role = member.role,
            isActive = member.isActive,
            lastReadMessageId = member.lastReadMessageId,
            joinedAt = member.joinedAt,
            leftAt = member.leftAt
        )
    }

    /**
     * (주의) 이 또한 private + self-invocation이라 @Cacheable이 실제로는 적용되지 않을 가능성 높음.
     * 사용자 DTO 매핑이 비용이 크고 캐시로 이득이 있다면 public 메서드로 노출하거나 별도 컴포넌트로 분리 권장.
     */
    @Cacheable(value = ["users"], key = "#user.id")
    private fun userToDto(user: User): UserDto {
        return UserDto(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            profileImageUrl = user.profileImageUrl,
            status = user.status,
            isActive = user.isActive,
            lastSeenAt = user.lastSeenAt,
            createdAt = user.createdAt
        )
    }

    /**
     * 채팅방 생성
     * 흐름:
     *  1) 생성자 조회 → ChatRoom 생성/저장 → OWNER 멤버로 추가
     *  2) (로컬 서버 기준) 생성자가 온라인이면 WebSocket 세션을 해당 방에 조인(joinRoom)
     *  3) 캐시 무효화: 채팅방 목록 관련 캐시 전체를 비움(@CacheEvict allEntries=true)
     */
    @CacheEvict(value = ["chatRooms"], allEntries = true)
    override fun createChatRoom(
        request: CreateChatRoomRequest,
        createdBy: Long,
    ): ChatRoomDto {
        val creator = userRepository.findById(createdBy)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $createdBy") }

        val chatRoom = ChatRoom(
            name = request.name,
            description = request.description,
            type = request.type,
            imageUrl = request.imageUrl,
            maxMembers = request.maxMembers,
            createdBy = creator
        )

        val savedRoom = chatRoomRepository.save(chatRoom)

        // 생성자를 방 OWNER 멤버로 등록
        val ownerMember = ChatRoomMember(
            chatRoom = savedRoom,
            user = creator,
            role = MemberRole.OWNER
        )
        chatRoomMemberRepository.save(ownerMember)

        // 생성자가 현재 이 서버에 온라인이라면, 서버의 Redis 구독상태/세션 상태를 갱신
        if (webSocketSessionManager.isUserOnlineLocally(creator.id)) {
            webSocketSessionManager.joinRoom(creator.id, savedRoom.id)
        }

        return chatRoomToDto(savedRoom)
    }

    /** 단일 채팅방 조회(+ DTO 매핑). @Cacheable로 방 단위 캐시 적용 */
    @Cacheable(value = ["chatRooms"], key = "#roomId")
    override fun getChatRoom(roomId: Long): ChatRoomDto {
        val chatRoom = chatRoomRepository.findById(roomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: $roomId") }
        return chatRoomToDto(chatRoom)
    }

    /** 사용자가 속한 채팅방 목록 페이지 조회 */
    override fun getChatRooms(
        userId: Long,
        pageable: Pageable,
    ): Page<ChatRoomDto> {
        return chatRoomRepository.findUserChatRooms(userId, pageable)
            .map { chatRoomToDto(it) }
    }

    /** 채팅방 검색(활성 방만). query가 비었으면 전체 최신순, 있으면 이름 부분일치 + 최신순 */
    override fun searchChatRooms(
        query: String,
        userId: Long,
    ): List<ChatRoomDto> {
        val chatRooms = if (query.isBlank()) {
            chatRoomRepository.findByIsActiveTrueOrderByCreatedAtDesc()
        } else {
            chatRoomRepository.findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(query)
        }

        return chatRooms.map { chatRoomToDto(it) }
    }

    /**
     * 채팅방 입장
     * - 중복 입장 방지(existsBy ... isActive=true)
     * - 방/멤버 캐시 무효화(@Caching)
     * - 유저가 로컬 서버에 온라인이면 WebSocketSessionManager.joinRoom 호출로
     *   서버의 Redis 구독/세션 맵을 갱신
     */
    @Caching(evict = [
        CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
        CacheEvict(value = ["chatRooms"], key = "#roomId")
    ])
    override fun joinChatRoom(roomId: Long, userId: Long) {
        // 방/유저 존재 확인
        val chatRoom = chatRoomRepository.findById(roomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: $roomId") }

        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $userId") }

        // 이미 활성 멤버인지 검사
        if (chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw IllegalStateException("이미 참여한 채팅방입니다")
        }

        // 인원 제한 체크가 필요하면 아래 재활성화
        // val currentMemberCount = chatRoomMemberRepository.countActiveMembersInRoom(roomId)
        // if (currentMemberCount >= chatRoom.maxMembers) {
        //     throw IllegalStateException("채팅방이 가득 찼습니다")
        // }

        // 멤버 추가(활성)
        val member = ChatRoomMember(
            chatRoom = chatRoom,
            user = user,
            role = MemberRole.MEMBER
        )
        chatRoomMemberRepository.save(member)

        // 로컬 온라인이면 구독/세션 갱신
        if (webSocketSessionManager.isUserOnlineLocally(userId)) {
            webSocketSessionManager.joinRoom(userId, roomId)
        }
        //로컬 서버에 실제로 연결된 세션이 있을 때만 그 방(roomId)의 Redis Pub/Sub 구독을 세팅하고, 내부 세션 맵에 “이 방을 듣는다” 상태를 반영하려는 것.
        //즉, 불필요한 구독과 상태 저장을 줄여 자원(스레드/네트워크/메모리)을 아끼려는 보호 로직.
    }

    /**
     * 채팅방 나가기(소프트 삭제: isActive=false + leftAt=현재시간)
     * - 방/멤버 캐시 무효화
     */
    @Caching(evict = [
        CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
        CacheEvict(value = ["chatRooms"], key = "#roomId")
    ])
    override fun leaveChatRoom(roomId: Long, userId: Long) {
        chatRoomMemberRepository.leaveChatRoom(roomId, userId)
    }

    /**
     * 채팅방 멤버 목록 조회 (활성 멤버만)
     * - 방 단위 캐싱: "chatRoomMembers::{roomId}"
     */
    @Cacheable(value = ["chatRoomMembers"], key = "#roomId")
    override fun getChatRoomMembers(roomId: Long): List<ChatRoomMemberDto> {
        return chatRoomMemberRepository.findByChatRoomIdAndIsActiveTrue(roomId)
            .map { memberToDto(it) }
    }

    /**
     * 메시지 페이지 조회(페이지네이션)
     * - 멤버십 가드(권한 체크)
     * - 저장소에서 페이지 조회 후 DTO 매핑
     */
    override fun getMessages(
        roomId: Long,
        userId: Long,
        pageable: Pageable,//Pageable가 동적인 값이라 Cacheable 지정 안했음.
    ): Page<MessageDto> {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw IllegalArgumentException("채팅방 멤버가 아닙니다")
        }

        return messageRepository.findByChatRoomId(roomId, pageable)
            .map { messageToDto(it) }
    }

    /**
     * 메시지 커서 기반 조회(무한스크롤)
     * - BEFORE: cursor 이전(과거) 메시지
     * - AFTER : cursor 이후(최신 방향) 메시지 (클라이언트 표시를 위해 역전 정렬)
     * - 커서가 없으면 최신 메시지부터
     * - nextCursor/prevCursor, hasNext/hasPrev 계산 포함
     */

    /*
     * 🔥 [커서 기반 페이지네이션(Cursor Pagination)] 설명
     * ------------------------------------------------------------
     * Offset 방식과 커서 방식은 조회 방식 자체가 완전히 다르다.
     *
     * ① Offset 방식 (LIMIT x OFFSET y)
     *    - 예: SELECT ... LIMIT 20 OFFSET 1000
     *    - 문제점:
     *        • OFFSET이 커질수록 성능 ↓ (OFFSET n 은 사실상 n개 스킵)
     *        • 테이블 신규 입력/삭제 시 데이터 밀림(불안정)
     *        • 최신 메시지 읽기엔 적합하지만, 무한 스크롤에는 부적합
     *
     * ② Cursor 방식 (WHERE id > cursor or id < cursor)
     *    - 예: SELECT ... WHERE id < cursor LIMIT 20
     *    - 장점:
     *        • 인덱스를 그대로 활용 → 성능 매우 빠름 (O(logN))
     *        • 데이터가 변경되더라도 밀림 현상 없음
     *        • 모바일 채팅/피드/무한 스크롤에서 표준 방식
     *
     * 👉 결론:
     *    “채팅 메시지” 같이 계속 쌓이는 데이터는 커서 기반이 필수다.
     *    특히 메시지 ID(=AutoIncrement 또는 시퀀스)를 커서로 쓰면 매우 효율적.
     */
    override fun getMessagesByCursor(
        request: MessagePageRequest,
        userId: Long,
    ): MessagePageResponse {

        // 멤버십(권한) 확인
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(request.chatRoomId, userId)) {
            throw IllegalArgumentException("채팅방 멤버가 아닙니다")
        }

        val pageable = PageRequest.of(0, request.limit)
        val cursor = request.cursor

        val messages = when {
            cursor == null -> {
                // 첫 로드: 최신 메시지부터
                messageRepository.findLatestMessages(request.chatRoomId, pageable)
            }
            request.direction == MessageDirection.BEFORE -> {
                // 과거 방향: cursor 이전 메시지
                messageRepository.findMessagesBefore(request.chatRoomId, cursor, pageable)
            }
            else -> {
                // 최신 방향: cursor 이후 메시지
                messageRepository.findMessagesAfter(request.chatRoomId, cursor, pageable)
                    .reversed() // 시간 오름차순으로 보여주기 위해 역전
            }
        }
        //클라이언트가 커서값을 전달 해준다.
        val messageDtos = messages.map { messageToDto(it) }

        // 커서 계산(빈 목록이면 모두 null)
        val nextCursor = if (messageDtos.isNotEmpty()) messageDtos.last().id else null
        val prevCursor = if (messageDtos.isNotEmpty()) messageDtos.first().id else null

        val hasNext = messages.size == request.limit   // limit만큼 꽉 찼으면 다음 페이지가 있을 가능성 큼
        val hasPrev = cursor != null                   // 커서가 있었다면 이전 페이지가 있었던 것

        return MessagePageResponse(
            messages = messageDtos,
            nextCursor = nextCursor,
            prevCursor = prevCursor,
            hasNext = hasNext,
            hasPrev = hasPrev
        )
    }

    /**
     * 메시지 전송
     * 흐름(아주 중요):
     *  1) 방/보내는 사람/멤버십 검증
     *  2) "방 전역 시퀀스 번호" 할당(Redis INCR) — 모든 서버/클라이언트 정렬 기준 일치
     *  3) DB 저장(트랜잭션 내)
     *  4) (지연 없이) 로컬 서버의 WebSocket 세션에 즉시 전송 → 체감 반응속도 ↑
     *  5) Redis 브로드캐스트: 다른 서버 인스턴스에도 전달(자기 서버는 exclude)
     *
     * 주의:
     * - 이상적으로는 "DB 커밋 후" 브로드캐스트해야(재시도/일관성). 필요시 AFTER_COMMIT 훅 사용 권장.
     * - excludeSeverId 파라미터명 오탈자 → excludeServerId 로 통일 추천.
     */
    override fun sendMessage(
        request: SendMessageRequest,
        senderId: Long,
    ): MessageDto {
        // 방/보낸 사람 확인
        val chatRoom = chatRoomRepository.findById(request.chatRoomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: ${request.chatRoomId}") }

        val sender = userRepository.findById(senderId)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $senderId") }

        // 멤버십 확인(활성 멤버)
        chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(request.chatRoomId, senderId)
            .orElseThrow { IllegalArgumentException("채팅방에 참여하지 않은 사용자입니다.") }

        // 방 전역 시퀀스 부여(원자적, Redis INCR) - redis의 순서 보장하는 숫자
        val sequenceNumber = messageSequenceService.getNextSequence(request.chatRoomId)

        // 메시지 엔티티 생성/저장
        val message = Message(
            content = request.content,
            type = request.type ?: MessageType.TEXT,
            chatRoom = chatRoom,
            sender = sender,
            sequenceNumber = sequenceNumber
        )
        val savedMessage = messageRepository.save(message)

        // 브로커/세션 매니저가 사용하는 전송용 DTO 생성
        val chatMessage = ChatMessage(
            id = savedMessage.id,
            content = savedMessage.content ?: "",
            type = savedMessage.type,
            chatRoomId = savedMessage.chatRoom.id,
            senderId = savedMessage.sender.id,
            senderName = savedMessage.sender.displayName,
            sequenceNumber = savedMessage.sequenceNumber,
            timestamp = savedMessage.createdAt
        )

        // 1) 로컬로 먼저 전송(화면 반응성 ↑). 실패 시에도 브로드캐스트는 진행(로그만).
        webSocketSessionManager.sendMessageToLocalRoom(request.chatRoomId, chatMessage)

        // 2) 다른 서버로 브로드캐스트(자기 서버 제외)
        try {
            redisMessageBroker.broadcastToRoom(
                roomId = request.chatRoomId,
                message = chatMessage,
                excludeSeverId = redisMessageBroker.getServerId() // (오탈자: excludeServerId 권장)
            )
        } catch (e: Exception) {
            logger.error("Failed to broadcast message via Redis: ${e.message}", e)
        }

        return messageToDto(savedMessage)
    }
}
