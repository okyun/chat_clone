package com.chat.websocket.handler

import com.chat.domain.dto.ErrorMessage
import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.model.MessageType
import com.chat.domain.service.ChatService
import com.chat.persistence.service.WebSocketSessionManager
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.io.IOException

@Component
class ChatWebSocketHandler(
    private val sessionManager: WebSocketSessionManager,   // 웹소켓 세션 등록/해제/브로드캐스팅 담당
    private val chatService: ChatService,                  // 메시지 저장 + Redis 브로드캐스트까지 하는 도메인 서비스
    private val objectMapper: ObjectMapper               // JSON 변환용
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 🔥 WebSocket 연결이 “성공적으로 맺어진 직후” 자동 호출되는 메서드
     *
     * - HandshakeInterceptor에서 attributes["userId"] 를 복원하여 userId 확인
     * - 해당 유저를 서버(Local Server)의 세션 맵에 등록
     * - 유저가 참여한 채팅방 목록을 로드 후 → 각 방에 joinRoom() 호출
     *   → joinRoom()은 Redis Pub/Sub 구독 관리 및 서버-방 매핑 관리 수행
     *
     * 즉, “유저 WebSocket 연결 → 서버 인스턴스에 등록 → 해당 유저가 참여한 모든 방을 이 서버에서 듣도록 세팅”
     */
    override fun afterConnectionEstablished(session: WebSocketSession) {
        val userId = getUserIdFromSession(session)

        if (userId != null) {
            // 현재 서버에 WebSocketSession 추가
            sessionManager.addSession(userId, session)
            logger.info("Session established for user $userId")

            try {
                // 유저가 참여한 방 전체를 로딩 후 Redis Pub/Sub 구독 연결
                loadUserChatRooms(userId)
            } catch (e: Exception) {
                logger.error("Error while loading user chat rooms", e)
            }
        }
    }

    /**
     * 🔥 클라이언트가 WebSocket을 통해 메시지를 보낼 때 마다 자동 호출
     *
     * ① userId 가져오기
     * ② TextMessage 인지 확인
     * ③ 메시지 타입 분석 → SEND_MESSAGE, ...
     * ④ SEND_MESSAGE인 경우 → JSON 파싱 후 ChatService.sendMessage() 호출
     *
     * 결국: “클라이언트 → 서버로 들어오는 실시간 메시지 처리”
     */
    override fun handleMessage(
        session: WebSocketSession,
        message: WebSocketMessage<*>,
    ) {
        val userId = getUserIdFromSession(session) ?: return

        try {
            when(message) {
                is TextMessage -> {
                    handleTextMessage(session, userId, message.payload)
                }
                else -> {
                    logger.warn("Unsupported message type ${message.javaClass.name}")
                }
            }
        } catch (e: Exception) {
            logger.warn("Error while processing message", e)
            sendErrorMessage(session, "메시지 처리 에러")
        }
    }

    /**
     * 🔥 WebSocket 통신 에러 발생 시 호출, websocket 연결에 문제가 생겼을때
     * 예: 클라이언트 사용자가 브라우저를 끄거나 ,네트워크 끊김, 파싱 문제, 클라이언트 강제 종료 등
     *
     * - EOFException: 정상 종료와 비슷해 debug로만 처리
     * - 기타 오류는 error 로그
     *
     * → 오류 여부와 상관없이 sessionManager.removeSession() 호출
     *
     *
     */
    override fun handleTransportError(
        session: WebSocketSession,
        exception: Throwable,
    ) {
        val userId = getUserIdFromSession(session)

        if (exception is java.io.EOFException) {
            logger.debug("WebSocket connection closed by client for user: $userId")
        } else {
            logger.error("WebSocket transport error for user: $userId", exception)
        }

        if (userId != null) {
            sessionManager.removeSession(userId, session)
        }
    }

    /**
     * 🔥 WebSocket 연결이 완전히 종료되었을 때 호출
     * - 사용자의 세션을 sessionManager 에서 제거
     * - 해당 유저의 모든 세션이 0개라면 → 이 서버가 구독 중인 Redis 방도 정리됨
     *
     * 즉: “클라이언트가 나가면 서버 로컬 상태 + Redis 구독 상태도 정리”
     */
    override fun afterConnectionClosed(
        session: WebSocketSession,
        closeStatus: CloseStatus,
    ) {
        val userId = getUserIdFromSession(session)
        if (userId != null) {
            sessionManager.removeSession(userId, session)
            logger.info("Session removed for $userId")
        }
    }

    /** WebSocket 메시지 부분 전송 지원 여부 (사용 안 함) */
    override fun supportsPartialMessages(): Boolean = false

    /**
     * 🔥 HandshakeInterceptor에서 넣어둔 attributes["userId"] 를 꺼내는 메서드
     *
     * → WebSocketSession마다 userId가 저장되어 있어야
     *   - 세션 등록
     *   - 메시지 처리
     *   - 브로드캐스트 대상 조회
     *   등에 활용 가능
     */
    private fun getUserIdFromSession(session: WebSocketSession): Long? {
        return session.attributes["userId"] as? Long
    }

    /**
     * 🔥 유저가 참여한 채팅방 전체 목록을 조회하여,
     *   해당 유저가 접속한 순간 모든 방에 Redis Pub/Sub 구독을 연결하는 과정
     *
     * 흐름:
     *  1) chatService.getChatRooms(userId) 로 DB에서 참여 방 조회
     *  2) roomId마다 joinRoom(userId, roomId) 호출
     *     → joinRoom 내부에서 Redis 구독 / 서버-방 매핑 / 세션 상태 구성
     */
    private fun loadUserChatRooms(userId: Long) {
        try {
            val chatRooms = chatService.getChatRooms(userId, PageRequest.of(0, 100))

            chatRooms.content.forEach { room ->
                sessionManager.joinRoom(userId, room.id)
            }

            logger.info("Loaded ${chatRooms.content.size} chat rooms for user: $userId")
        } catch (e: Exception) {
            logger.error("Failed to load chat rooms for user: $userId", e)
        }
    }

    /**
     * 🔥 에러 메시지를 WebSocket으로 직접 해당 세션에 전송해주는 유틸리티
     *
     * - 형식: {"message": "...", "code": "..."}
     * - 클라이언트가 에러 팝업/경고 표시할 때 사용 가능
     */
    private fun sendErrorMessage(
        session: WebSocketSession,
        errorMessage: String,
        errorCode: String? = null
    ) {
        try {
            val error = ErrorMessage(
                chatRoomId = null,
                message = errorMessage,
                code = errorCode
            )
            val json = objectMapper.writeValueAsString(error)
            session.sendMessage(TextMessage(json))
        } catch (e: IOException) {
            logger.error("Failed to send error message", e)
        }
    }

    /**
     * 🔥 클라이언트 메시지(JSON)의 "type" 필드를 추출하는 메서드
     * 예: {"type":"SEND_MESSAGE", ...}
     *     → "SEND_MESSAGE"
     */
    private fun extractMessageType(payload: String): String? {
        return try {
            objectMapper.readTree(payload).get("type")?.asText()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 🔥 실제 TextMessage를 처리하는 핵심 메서드
     *
     * 주 흐름:
     *  1) JSON 파싱
     *  2) "type"이 SEND_MESSAGE 인지 확인
     *  3) 필요한 필드(chatRoomId, messageType, content) 파싱
     *  4) ChatService.sendMessage() 호출
     *
     * sendMessage 내부:
     *  - DB 저장
     *  - 로컬 세션(WebSocket)에 즉시 전송
     *  - Redis Pub/Sub 으로 다른 서버들에 브로드캐스팅
     *
     * 따라서 이 메서드는 “클라이언트 전송 메시지를 서버의 도메인 서비스로 전달하는 역할”
     */
    private fun handleTextMessage(session: WebSocketSession, userId: Long, payload: String) {
        try {
            val messageType = extractMessageType(payload)

            when (messageType) {
                "SEND_MESSAGE" -> {
                    val jsonNode = objectMapper.readTree(payload)

                    val chatRoomId = jsonNode.get("chatRoomId")?.asLong()
                        ?: throw IllegalArgumentException("chatRoomId is required")
                    val messageTypeText = jsonNode.get("messageType")?.asText()
                        ?: throw IllegalArgumentException("messageType is required")
                    val content = jsonNode.get("content")?.asText()

                    val sendMessageRequest = SendMessageRequest(
                        chatRoomId = chatRoomId,
                        type = MessageType.valueOf(messageTypeText),
                        content = content
                    )

                    // 핵심: 메시지 전송(시퀀스 부여 → DB 저장 → 로컬 브로드캐스트 → Redis 브로드캐스트)
                    chatService.sendMessage(sendMessageRequest, userId)
                }

                else -> {
                    logger.warn("Unknown message type: $messageType")
                    sendErrorMessage(
                        session,
                        "알 수 없는 메시지 타입입니다: $messageType",
                        "UNKNOWN_MESSAGE_TYPE"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error parsing WebSocket message from user $userId: ${e.message}", e)
            sendErrorMessage(session, "메시지 형식만 전송 가능", "INVALID_MESSAGE_FORMAT")
        }
    }
}
