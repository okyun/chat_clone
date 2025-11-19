package com.chat.websocket.interceptor

import org.slf4j.LoggerFactory
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.lang.Exception

@Component
class WebSocketHandshakeInterceptor : HandshakeInterceptor {

    private val logger = LoggerFactory.getLogger(WebSocketHandshakeInterceptor::class.java)

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String?, Any?>,
    ): Boolean {
        return try {
            // ws://localhost:8080/chat?userId=123
            val uri = request.uri
            val query = uri.query
            // 1) queryString 이 있는지 확인
            //    - 왜? userId 같은 식별 정보가 여기에 담겨 있기 때문.
            //    - 없으면 누가 접속하는지 알 수 없으므로 연결을 거부한다.
            if (query != null) {
                val param = parseQuery(query)
                val userId = param["userId"]?.toLongOrNull()

                if (userId != null) {
                    attributes["userId"] = userId
                    true
                }else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            logger.error(e.message, e)
            false
        }
    }
    /**
     * WebSocket 핸드셰이크 "이후"에 호출됨.
     *
     * - 성공/실패 여부를 로깅하는 용도로 사용 가능.
     * - 실제 인증 로직은 보통 beforeHandshake에서 처리.
     */
    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) {
        if (exception != null) {
            logger.error("WebSocket HandshakeInterceptor exception", exception)
        }else {
            logger.info("WebSocket HandshakeInterceptor")
        }
    }
    /**
     * query string 파싱 유틸리티
     *
     * 입력: "userId=123&roomId=10"
     * 출력: mapOf(
     *           "userId" -> "123",
     *           "roomId" -> "10"
     *      )
     *
     * - query:   "key=value&key2=value2" 형태
     * - split("&") → ["key=value", "key2=value2"]
     * - 각 항목을 다시 split("=", limit=2) 해서 key, value로 나눈다.
     */
    private fun parseQuery(query: String): Map<String, String> {
        return query.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0] to parts[1]
                } else null
            }
            .toMap()
    }

}
