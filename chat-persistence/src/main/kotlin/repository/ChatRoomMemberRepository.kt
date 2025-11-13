package com.chat.persistence.repository

import com.chat.domain.model.ChatRoomMember
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 *  ChatRoomMemberRepository
 *
 * 채팅방과 사용자 간의 관계(Entity: ChatRoomMember)를 관리하는 JPA Repository.
 * (Spring Data JPA가 인터페이스 메서드 이름을 기반으로 자동으로 쿼리를 생성해줌)
 *
 * 주요 역할:
 *  - 채팅방 참여자 조회
 *  - 특정 유저의 채팅방 참여 여부 확인
 *  - 채팅방 나가기(상태 변경)
 *  - 활성 사용자 수 카운트
 */
@Repository
interface ChatRoomMemberRepository : CrudRepository<ChatRoomMember, Long> {

    /**
     *  채팅방에 "활성 상태(isActive=true)"인 멤버 전체 조회
     *
     * - 메서드 이름 기반 쿼리 자동 생성
     *   ⇒ SELECT * FROM chat_room_member WHERE chat_room_id = ? AND is_active = true
     *
     * - findBy + (필드명) + And + (조건)
     * - "IsActiveTrue" 는 Boolean 필드를 검사하는 **스프링 데이터 JPA 키워드**
     *   → isActive = true 인 row만 가져옴
     */
    fun findByChatRoomIdAndIsActiveTrue(chatRoomId: Long): List<ChatRoomMember>


    /**
     *  특정 채팅방의 특정 유저가 "활성 상태"인지 조회
     *
     * - Optional<T> 반환:
     *   → 결과가 없을 수도 있기 때문에 null 대신 Optional로 감싸서 반환.
     *   → Kotlin에서는 .orElse(null), .isPresent 등으로 null-safe 처리 가능.
     *
     * 예시:
     *   val member = repo.findByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)
     *   if (member.isPresent) { ... } else { ... }
     */
    fun findByChatRoomIdAndUserIdAndIsActiveTrue(chatRoomId: Long, userId: Long): Optional<ChatRoomMember>


    /**
     *  채팅방의 활성 멤버 수 카운트
     *
     * - @Query: JPQL 직접 작성
     * - COUNT() 함수로 활성화된 인원 수를 반환
     * - 반환 타입은 Long
     *
     * JPQL 예시:
     *   SELECT COUNT(c) FROM ChatRoomMember c
     *   WHERE c.chatRoom.id = :chatRoomId AND c.isActive = true
     */
    @Query(
        "SELECT COUNT(crm) " +
                "FROM ChatRoomMember crm " +
                "WHERE crm.chatRoom.id = :chatRoomId AND crm.isActive = true"
    )
    fun countActiveMembersInRoom(chatRoomId: Long): Long


    /**
     *  채팅방 나가기 처리 (상태 업데이트)
     *
     * - @Modifying: SELECT가 아닌 UPDATE/DELETE 쿼리임을 명시해야 JPA가 실행 가능.
     * - JPQL로 특정 유저의 isActive를 false로 바꾸고, 나간 시각(leftAt)을 기록.
     *
     * 동작 요약:
     *   1️ DB에서 ChatRoomMember row를 찾아
     *   2️ is_active=false, left_at=CURRENT_TIMESTAMP 로 업데이트
     *   3 실제 엔티티 객체에는 반영되지 않음 (별도 flush 필요)
     */
    @Modifying
    @Query("""
        UPDATE ChatRoomMember crm 
        SET crm.isActive = false, crm.leftAt = CURRENT_TIMESTAMP 
        WHERE crm.chatRoom.id = :chatRoomId AND crm.user.id = :userId
    """)
    fun leaveChatRoom(chatRoomId: Long, userId: Long)


    /**
     * 채팅방에 유저가 현재 활성 상태로 참여 중인지 확인
     *
     * - existsBy + (조건식)
     * - true / false 로 반환.
     *
     * 예시:
     *   if (repo.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
     *       println("이미 방에 있음")
     *   }
     *
     * JPA가 자동으로 아래 JPQL을 만들어 실행함:
     *   SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END
     *   FROM ChatRoomMember
     *   WHERE chat_room_id = ? AND user_id = ? AND is_active = true
     */
    fun existsByChatRoomIdAndUserIdAndIsActiveTrue(chatRoomId: Long, userId: Long): Boolean
}