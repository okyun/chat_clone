package com.chat.persistence.repository

import com.chat.domain.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 🧩 UserRepository
 *
 * 사용자(User) 엔티티를 위한 데이터 접근 계층.
 * (Spring Data JPA가 제공하는 JpaRepository를 상속받아 CRUD 및 커스텀 쿼리 메서드 지원)
 *
 * 기본 제공 기능:
 *  - findAll(), findById(), save(), deleteById() 등 공통 CRUD 메서드
 *
 * 추가 정의된 메서드:
 *  - username 기반 조회
 *  - username 중복 여부 검사
 *  - 마지막 접속 시각 업데이트
 *  - 사용자 검색 (이름/닉네임 부분 검색 + 페이징)
 */
@Repository
interface UserRepository : JpaRepository<User, Long> {

    /**
     *  username(로그인 ID)으로 사용자 조회
     *
     * - 메서드 네이밍 기반 쿼리 자동 생성
     *   SELECT * FROM user WHERE username = ?
     *
     * - 반환 타입: User?
     *   → 존재하지 않으면 null 반환 (Optional 대신 Kotlin nullable 사용)
     */
    fun findByUsername(username: String): User?


    /**
     *  username 중복 여부 확인
     *
     * - existsBy + (필드명)
     *   → SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END
     *
     * 예시:
     *   if (userRepository.existsByUsername("okyoon")) {
     *       println("이미 존재하는 사용자명입니다.")
     *   }
     */
    fun existsByUsername(username: String): Boolean


    /**
     *  마지막 접속 시간(lastSeenAt) 갱신
     *
     * - @Modifying: SELECT가 아닌 UPDATE 쿼리임을 명시해야 함
     * - JPQL 쿼리 직접 정의
     *
     * 예시 JPQL:
     *   UPDATE User u
     *   SET u.lastSeenAt = :lastSeenAt
     *   WHERE u.id = :userId
     *
     * - 반환 타입 없음 (업데이트만 수행)
     * - 주로 로그인/활동 시각 갱신 등에 사용
     */
    @Modifying
    @Query("""
        UPDATE User u 
        SET u.lastSeenAt = :lastSeenAt 
        WHERE u.id = :userId
    """)
    fun updateLastSeenAt(userId: Long, lastSeenAt: LocalDateTime)


    /**
     * 사용자 검색 (username 또는 displayName 에서 부분 일치 검색)
     *
     * - @Query: 커스텀 JPQL 작성
     * - LOWER(): 대소문자 구분 없이 검색 (case-insensitive)
     * - CONCAT('%', :query, '%'): LIKE 검색 패턴 생성
     * - Pageable: 페이징 및 정렬 지원 (스프링 데이터 기본 기능)
     * - 반환 타입: Page<User>
     *
     * 예시 JPQL:
     *   SELECT u FROM User u
     *   WHERE LOWER(u.username) LIKE LOWER('%keyword%')
     *      OR LOWER(u.displayName) LIKE LOWER('%keyword%')
     *
     * 예시 사용법:
     *   val pageable = PageRequest.of(0, 20, Sort.by("username"))
     *   val results = userRepository.searchUsers("ok", pageable)
     */
    @Query("""
        SELECT u FROM User u 
        WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) 
           OR LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%'))
    """)
    fun searchUsers(query: String, pageable: Pageable): Page<User>
}