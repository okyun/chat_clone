package com.chat.persistence.service
//redis로 들어온 메시지가 들어오는 순서를 보장해주는 서비스
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service



@Service
class MessageSequenceService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    private val prefix = "chat:sequence"

    fun getNextSequence(chatRoomId: Long): Long {
        val key = "${prefix}:${chatRoomId}"

        // INCR 명령어를 사용하여 원자적인 증가
        return redisTemplate.opsForValue().increment(key) ?: 1L
    }

}