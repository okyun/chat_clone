package com.chat.persistence.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.beans.factory.annotation.Value
import java.util.concurrent.Executors

/**
 * Redis 저수준 사용을 위한 공용 설정.
 *
 * 구성 요소
 * 1) distributedObjectMapper: 분산 메시지/캐시/이벤트에서 공통으로 쓸 수 있는 ObjectMapper.
 * 2) RedisTemplate<String, String>: 간단한 Key/Value, Hash 작업에 최적화된 문자열 템플릿.
 * 3) RedisMessageListenerContainer: Pub/Sub 수신 스레드 풀 및 오류 핸들링 설정.
 *
 * 팁
 * - 객체 저장이 필요하면 RedisTemplate<String, Any>/ValueSerializer 교체된 별도 템플릿을 추가로 정의한다.
 * - Pub/Sub은 리스너 등록 전까지 컨테이너가 유휴 상태이며, 리스너 추가 시 스레드가 동작한다.
 */
@Configuration
class RedisConfig {

    @Value("\${spring.data.redis.host:localhost}")
    private lateinit var redisHost: String

    @Value("\${spring.data.redis.port:6379}")
    private var redisPort: Int = 6379

    @Value("\${spring.data.redis.password:}")
    private var redisPassword: String = ""

    @Value("\${spring.data.redis.username:}")
    private var redisUsername: String = ""

    @Bean
    fun redisConnectionFactory(): RedisConnectionFactory {
        val config = RedisStandaloneConfiguration().apply {
            hostName = redisHost
            port = redisPort
            if (redisUsername.isNotBlank()) {
                username = redisUsername
            }
            if (redisPassword.isNotBlank()) {
                password = RedisPassword.of(redisPassword)
            }
        }
        return LettuceConnectionFactory(config)
    }

    @Bean("distributedObjectMapper")
    fun distributedObjectMapper(): ObjectMapper {
        // 직렬화/역직렬화 설정
        // 예) epoch milli 1751027620000 ↔ "2025-06-27T11:47:00" 같은 ISO-8601 포맷 간 상호 변환을 안전하게 처리
        return ObjectMapper().apply {
            registerModule(JavaTimeModule())
            registerModule(KotlinModule.Builder().build())
            // 날짜를 타임스탬프가 아닌 ISO-8601 문자열로 기록
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 알 수 없는 필드 무시(하위/상위 버전 간 호환성 ↑)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
        // <String, String> 템플릿: 키/값/해시 모두 문자열 직렬화
        // 장점: Redis CLI로 바로 읽기 쉽고, 디버깅 편함
        // 단점: 객체 저장에는 부적합(별도 템플릿/Serializer 권장)
        return RedisTemplate<String, String>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = StringRedisSerializer()
            valueSerializer = StringRedisSerializer()
            hashKeySerializer = StringRedisSerializer()
            hashValueSerializer = StringRedisSerializer()
            afterPropertiesSet() // 설정 적용 후 초기화
        }
    }

    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory
    ): RedisMessageListenerContainer {
        // Redis Pub/Sub 수신 컨테이너
        // - 필요 시 새로운 스레드를 생성하지만, 재사용 가능한 캐시드 풀을 사용해 과도한 생성비용을 줄임
        // - Daemon 스레드로 동작하여 애플리케이션 종료를 방해하지 않음
        return RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
            setTaskExecutor(
                Executors.newCachedThreadPool { runnable ->
                    Thread(runnable).apply {
                        name = "redis-message-listener-${System.currentTimeMillis()}"
                        isDaemon = true
                    }
                }
            )
            // (선택) 구독·메시지 처리 전용 풀을 분리하고 싶다면 아래와 같이 별도 Executor를 둘 수 있음
            // setSubscriptionExecutor(Executors.newCachedThreadPool())
            setErrorHandler { t ->
                // println 보다는 로거 사용을 권장(운영환경에서 레벨/포맷/전송 제어 용이)
                // logger.error("Redis Message Listener Error", t)
                println("Redis Message Listener Error: $t")
                t.printStackTrace()
            }
            // 주의: 실제 메시지를 받으려면 addMessageListener(...) 로 채널/패턴과 리스너를 등록해야 함.
            // 예) addMessageListener(myListener, ChannelTopic("chat:new-message"))
        }
    }
}
