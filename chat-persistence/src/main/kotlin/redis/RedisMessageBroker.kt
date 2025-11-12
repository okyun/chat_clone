package com.chat.persistence.redis

import com.chat.domain.dto.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Redis Pub/Sub 기반의 메시지 브로커
 * (Redis Pub/Sub 기반으로 채팅 메시지를 여러 서버 간에 동기화하는 핵심 클래스)
 *
 * 💡 역할:
 * - 여러 서버 간 실시간 채팅 메시지를 Redis를 통해 전송(broadcast)
 * - 같은 방(roomId)을 구독(subscribe) 중인 서버들에게 메시지 전달
 * - 중복 메시지 처리 방지 및 서버 식별(serverId) 관리
 *
 * 주요 컴포넌트:
 * - redisTemplate: Redis에 메시지 발행(publish)
 * - messageListenerContainer: Redis 구독 관리 (subscribe/unsubscribe)
 * - objectMapper: ChatMessage <-> JSON 변환 (직렬화/역직렬화)
 */
@Service
class RedisMessageBroker(
    private val redisTemplate: RedisTemplate<String, String>,
    private val messageListenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper // JSON ↔ 객체 변환 도구
) : MessageListener {

    private val logger = LoggerFactory.getLogger(RedisMessageBroker::class.java)

    // 현재 서버를 식별하기 위한 고유 ID (서버 간 메시지 구분용)
    private val serverId = System.getenv("HOSTNAME") ?: "server-${System.currentTimeMillis()}"

    // 중복 메시지 처리 방지를 위한 저장소 (ConcurrentHashMap → 스레드 안전)
    private val processedMessages = ConcurrentHashMap<String, Long>()

    // 현재 서버가 구독 중인 채팅방(roomId) 목록
    private val subscribeRooms = ConcurrentHashMap.newKeySet<Long>()

    // 수신된 메시지를 실제 서비스 로직(예: WebSocket 세션)에 전달하기 위한 콜백 핸들러
    // ChatMessage 의 dto 형태로 데이터를 받는다.
    private var localMessageHandler: ((Long, ChatMessage) -> Unit)? = null

    /** 현재 서버의 고유 ID 반환 */
    fun getServerId() = serverId


    /**
     * @PostConstruct
     * class의 di 가 모두 주입받은 이후에 알아서 실행 (RedisMessageListenerContainer 초기화 이후, 주기적인 정리 스레드 실행)
     *
     * - 실행 시점: 빈이 생성되고 의존성이 모두 주입된 직후
     * - 역할: processedMessages(중복 메시지 기록)에서 오래된 항목을 주기적으로 정리
     * - 주의: 데몬 스레드로 실행되어 앱 종료를 막지 않음
     */
    @PostConstruct
    fun initialize() {
        //서버가 중복 메시지를 걸러내기 위해 유지한 기록을,
        //메모리 누수 없이 오래 가지 않도록 주기적으로 청소하는 코드
        logger.info("Initializing RedisMessageListenerContainer")
//        기존의 Thread { sleep → cleanUpProcessedMessages() } 블록을 제거.
//        앱이 켜지고 나서 30초 뒤에 딱 한 번만 cleanUpProcessedMessages()가 실행
        // 30 초 마다 삭제 하게  @Scheduled 추가
//        Thread {
//
//            try {
//                Thread.sleep(30_000) // 30초 대기 후 정리 실행
//                cleanUpProcessedMessages()
//            } catch (e: Exception) {
//                logger.error("Error during RedisMessageListenerContainer init", e)
//            }
//        }.apply {
//            isDaemon = true
//            name = "redis-broker-cleanup"
//            start()
//        }
    }

    /**
     * 🗓 30초 후 시작해서, 30초마다 중복 메시지 캐시 정리
     * - initialDelay: 애플리케이션 기동 직후 안정화 시간을 주기 위함
     * - fixedDelay: 이전 실행이 끝난 시점 기준 30초 뒤에 다시 실행
     *
     * 필요에 따라 application.yml로 간단히 조정 가능:
     *   @Scheduled(
     *     initialDelayString = "\${chat.cleanup.initial-delay:30000}",
     *     fixedDelayString = "\${chat.cleanup.fixed-delay:30000}"
     *   )
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    fun scheduledCleanup() {
        cleanUpProcessedMessages()//30초 마다 검사해서 1분이상 thread 삭제
    }

    /**
     *  processedMessages 정리 스케줄
     *
     * - 역할: 오래된 중복 메시지 기록을 1분 단위로 삭제하여 메모리 누수 방지
     */
    private fun cleanUpProcessedMessages() {
        logger.info("cleanUpProcessedMessages starting...")
        val now = System.currentTimeMillis()
        val expiredKeys = processedMessages.filter { (_, time) ->
            now - time > 60_000 // 1분 이상 지난 메시지 제거
        }.keys

        expiredKeys.forEach { processedMessages.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            logger.info("Cleaned up ${expiredKeys.size} expired processed messages")
        }
    }

    /**
     *  @PreDestroy
     * 애플리케이션 종료 직전 모든 구독 해제
     *
     * - 역할: 메모리 누수나 중복 리스너 방지를 위해 모든 방(roomId) 구독 해제
     */
    @PreDestroy
    fun cleanup() {
        subscribeRooms.forEach { roomId ->
            unsubscribeFromRoom(roomId)
        }
        //roomid로 구독하고 있으니, roomid로 구독취소
        logger.info("Removed RedisMessageListenerContainer listeners")
    }

    /**
     *  메시지 처리 콜백 등록(역 의존성)
     *
     * - 사용처: WebSocket 서비스나 비즈니스 로직에서 Redis 수신 메시지를 전달받고 싶을 때
     * - 예시: broker.setLocalMessageHandler { roomId, message -> webSocketSender.send(roomId, message) }
     */
    fun setLocalMessageHandler(handler: (Long, ChatMessage) -> Unit) {
        this.localMessageHandler = handler
    }


    ///////////////////////구독 처리 /////////////////////////////////

    /**
     *  ~~ 채팅방 구독 시작  ~~
     *
     * - 역할: Redis Pub/Sub의 특정 Topic("chat.room.{roomId}")에 리스너 등록
     * - 중복 구독 방지: subscribeRooms Set에 존재 여부로 체크
     */
    fun subscribeToRoom(roomId: Long) {
        if (subscribeRooms.add(roomId)) {
            val topic = ChannelTopic("chat.room.$roomId")
            messageListenerContainer.addMessageListener(this, topic)
            logger.info("Subscribed to room $roomId")
        } else {
            logger.error("Already subscribed to room $roomId")
        }
    }

    /**
     *  ~~ 채팅방 구독 해제 ~~
     *
     * - 역할: Redis 리스너 컨테이너에서 해당 채널(topic) 제거
     * - 메모리 절약 및 중복 구독 방지
     */
    fun unsubscribeFromRoom(roomId: Long) {
        if (subscribeRooms.remove(roomId)) {
            val topic = ChannelTopic("chat.room.$roomId")
            messageListenerContainer.removeMessageListener(this, topic)
            logger.info("Unsubscribed from room $roomId")
        } else {
            logger.error("Room $roomId not found in subscriptions")
        }
    }

    // 순서 : broadcastToRoom() → Redis → onMessage()
    ////broadcastToRoom() :메시지를 Redis에 발행 -여러 서버에 동시에 퍼뜨리기 위해
    ///onMessage() : Redis가 퍼뜨린 메시지를 받음 - 다른 서버(혹은 자기 자신)에서 수신

    /**
     *  채팅방으로 메시지 전송 (Publish) -하나의 서버에 메시지가 들어왔을때, 다른 서버들에게도 메시지를 전달하는 역할
     *
     *
     * - 역할: 채팅방(roomId) Redis 채널로 JSON 직렬화된 메시지 발행
     * - excludeServerId: 자신이 보낸 메시지를 다시 받지 않도록 설정
     *
     * 싱글 서버 환경이라면 굳이 broadcastToRoom 필요 없음.
     * 멀티 서버 구조라서 broadcast로 다른 서버에게 알려줘야함.
     *
     *
     */
    fun broadcastToRoom(roomId: Long, message: ChatMessage, excludeSeverId: String? = null) {
                try {
                    val distributedMessage = DistributedMessage(
                id = "$serverId-${System.currentTimeMillis()}-${System.nanoTime()}",
                serverId = serverId,
                roomId = roomId,
                excludeSeverId = excludeSeverId,
                timestamp = LocalDateTime.now(),
                payload = message
            )

            // 객체 → JSON 변환
            val json = objectMapper.writeValueAsString(distributedMessage)

            // Redis Pub/Sub 발행
            redisTemplate.convertAndSend("chat.room.$roomId", json)

            logger.info("Broadcasted message to room $roomId: $json")
        } catch (e: Exception) {
            logger.error("Error broadcasting to room $roomId", e)
        }
    }

    /**
     *  Redis로부터 "메시지를 수신했을 때" 자동 호출되는 "콜백"
     * (MessageListener 인터페이스 구현)
     *
     * - 역할:
     *   0. 서버 확인
     *   1. JSON → 객체 역직렬화
     *   2. excludeServerId 체크 (자기 자신 메시지 무시)
     *   3. 중복 메시지 방지 (processedMessages로 필터)
     *   4. 콜백(localMessageHandler) 호출 → 실제 클라이언트로 전달
     */
    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val json = String(message.body)
            //DistributedMessage로 메시지 받기
            val distributedMessage = objectMapper.readValue(json, DistributedMessage::class.java)

            // 자신이 보낸 메시지는 무시
            if (distributedMessage.excludeSeverId == serverId) {
                logger.debug("Skipped message from same server: $serverId")
                return
            }

            // 이미 처리된 메시지는 중복 방지
            if (processedMessages.containsKey(distributedMessage.id)) {
                logger.debug("Duplicate message ignored: ${distributedMessage.id}")
                return
            }

            // 메시지를 실제 로직(WebSocket 등)에 전달
            localMessageHandler?.invoke(distributedMessage.roomId, distributedMessage.payload)

            // 처리 완료 메시지 기록
            processedMessages[distributedMessage.id] = System.currentTimeMillis()

            // 메모리 보호: processedMessages가 10,000개 이상이면 오래된 항목 제거
            if (processedMessages.size > 10_000) {
                val oldestEntries = processedMessages.entries.sortedBy { it.value }
                    .take(processedMessages.size - 10_000)
                oldestEntries.forEach { processedMessages.remove(it.key) }
            }

            logger.debug("Processed message: ${distributedMessage.id}")

        } catch (e: Exception) {
            logger.error("Error handling Redis message", e)
        }
    }



    /**
     *  서버 간 주고받는 메시지의 포맷 정의
     *
     * - id: 메시지 고유 식별자 (서버 ID + 타임스탬프 조합)
     * - serverId: 메시지를 보낸 서버 식별자
     * - roomId: 채팅방 ID
     * - excludeSeverId: 특정 서버는 이 메시지를 무시하도록 설정
     * - timestamp: 메시지 전송 시각
     * - payload: 실제 채팅 메시지 내용(ChatMessage)
     */
    data class DistributedMessage(
        val id: String,
        val serverId: String,
        val roomId: Long,
        val excludeSeverId: String?,
        val timestamp: LocalDateTime,
        val payload: ChatMessage
    )
}
