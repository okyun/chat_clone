package com.chat.persistence.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

/**
 * Spring Cache 추상화를 Redis로 사용할 수 있게 하는 설정.
 *
 * 핵심 포인트
 * 1) @EnableCaching: @Cacheable, @CachePut, @CacheEvict 등을 활성화한다.
 * 2) RedisCacheManager: 캐시 이름별 TTL/직렬화 전략을 설정한다.
 * 3) Jackson(ObjectMapper) 기반 JSON 직렬화: Kotlin + JavaTime을 안전하게 처리한다.
 *
 * 주의
 * - null 캐싱은 비활성화한다(캐시 폴루션 방지).
 * - Key는 String 직렬화(가독성 + 디버깅 용이).
 * - Value는 GenericJackson2JsonRedisSerializer로 JSON 저장(언어/플랫폼 중립).
 */
@Configuration
@EnableCaching
class CacheConfig {

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): CacheManager {
        // ObjectMapper: Kotlin data class, LocalDateTime(etc.)을 ISO-8601 문자열로 안정 직렬화
        // (CacheConfig 내부에서 별도 생성하지만, 프로젝트 전역 통일을 원하면 빈 주입으로 공유하는 것을 권장)
        val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            // NOTE: GenericJackson2JsonRedisSerializer는 기본적으로 type 정보를 포함시켜 역직렬화 안전성을 보장한다.
            // 여기서는 WRITE_DATES_AS_TIMESTAMPS 비활성화를 강제하지 않아도 JavaTimeModule이 문자열 포맷을 처리한다.
            // 필요 시 SerializationFeature.WRITE_DATES_AS_TIMESTAMPS 비활성화도 가능.
        }

        // 기본 캐시 설정
        val defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            // 전체 기본 TTL: 30분
            .entryTtl(Duration.ofMinutes(30))
            // 키 직렬화: 사람이 읽기 쉬운 문자열
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())
            )
            // 값 직렬화: Jackson 기반의 JSON (클래스 변경/다형성 대응이 비교적 안전)
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJackson2JsonRedisSerializer(objectMapper)
                )
            )
            // null 값 캐싱 방지: "캐시 미스"와 "실제 null"의 혼동을 막는다.
            .disableCachingNullValues()
        // (선택) 캐시 이름 prefix 지정: 여러 서비스가 같은 Redis를 공유할 때 충돌 방지
        // .computePrefixWith { cacheName -> "chat::$cacheName::" }
        // (선택) value compression 적용 고려: 데이터가 큰 경우 LZ4/GZIP(별도 Serializer 필요)

        // 캐시 이름별 개별 TTL 설정
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            // 사용자 정보는 상대적으로 자주 바뀌지 않으니 TTL을 길게(1시간)
            .withCacheConfiguration("users", defaultConfig.entryTtl(Duration.ofHours(1)))
            // 채팅방 메타 정보: 15분
            .withCacheConfiguration("chatRooms", defaultConfig.entryTtl(Duration.ofMinutes(15)))
            // 채팅방 멤버 목록: 10분
            .withCacheConfiguration("chatRoomMembers", defaultConfig.entryTtl(Duration.ofMinutes(10)))
            // 메시지 목록/요약: 5분 (변화가 잦음)
            .withCacheConfiguration("messages", defaultConfig.entryTtl(Duration.ofMinutes(5)))
            .build()
    }
}
