package com.pachy.highlight.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pachy.highlight.entity.Chat;

import java.util.List;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Chat c WHERE c.id = :chatId AND c.videoId = :videoId")
    java.util.Optional<Chat> findForHighlight(@Param("videoId") String videoId, @Param("chatId") Long chatId);


    /**
     * 채팅 검색 (전체 사용자용). videoId 필수, keyword(메시지 부분검색)와 username(닉네임 부분검색)은 선택.
     * 빈 문자열이면 해당 조건을 무시한다. ILIKE 는 pg_trgm GIN 인덱스로 가속된다.
     */
    @Query(value = "SELECT * FROM chats " +
            "WHERE video_id = :videoId " +
            "AND (:keyword = '' OR message ILIKE '%' || :keyword || '%') " +
            "AND (:username = '' OR username ILIKE '%' || :username || '%') " +
            "ORDER BY player_message_time ASC",
            countQuery = "SELECT count(*) FROM chats " +
                    "WHERE video_id = :videoId " +
                    "AND (:keyword = '' OR message ILIKE '%' || :keyword || '%') " +
                    "AND (:username = '' OR username ILIKE '%' || :username || '%')",
            nativeQuery = true)
    Page<Chat> searchChats(@Param("videoId") String videoId,
                           @Param("keyword") String keyword,
                           @Param("username") String username,
                           Pageable pageable);

    /**
     * 정확 일치 검색. keyword/username 이 (대소문자 무시) 완전히 일치하는 채팅만 반환한다.
     */
    @Query(value = "SELECT * FROM chats " +
            "WHERE video_id = :videoId " +
            "AND (:keyword = '' OR lower(message) = lower(:keyword)) " +
            "AND (:username = '' OR lower(username) = lower(:username)) " +
            "ORDER BY player_message_time ASC",
            countQuery = "SELECT count(*) FROM chats " +
                    "WHERE video_id = :videoId " +
                    "AND (:keyword = '' OR lower(message) = lower(:keyword)) " +
                    "AND (:username = '' OR lower(username) = lower(:username))",
            nativeQuery = true)
    Page<Chat> searchChatsExact(@Param("videoId") String videoId,
                                @Param("keyword") String keyword,
                                @Param("username") String username,
                                Pageable pageable);

    @Query(value = "SELECT MIN(player_message_time), count(*) FROM chats " +
            "WHERE video_id = :videoId AND player_message_time >= 0 " +
            "AND strpos(lower(message), :keyword) > 0 " +
            "GROUP BY (player_message_time / 60000) HAVING count(*) >= 2 " +
            "ORDER BY count(*) DESC, MIN(player_message_time) ASC LIMIT 5", nativeQuery = true)
    List<Object[]> findLiteralKeywordPeaks(@Param("videoId") String videoId, @Param("keyword") String keyword);

    long countByVideoId(String videoId);

    /**
     * 분당 채팅량 타임라인. 영상 시작 기준 분(0,1,2...)별 채팅 수를 오름차순으로 반환한다.
     * 채팅이 하나도 없는 분은 행 자체가 없으므로 빈 구간 채우기는 호출측에서 처리한다.
     */
    @Query(value = "SELECT (player_message_time / 60000) AS minute_index, count(*) AS cnt " +
            "FROM chats WHERE video_id = :videoId AND player_message_time IS NOT NULL " +
            "GROUP BY (player_message_time / 60000) ORDER BY minute_index ASC",
            nativeQuery = true)
    List<Object[]> findChatCountsPerMinute(@Param("videoId") String videoId);

    @Query(value = "SELECT (player_message_time - (player_message_time % 60000)) AS minute_offset, MIN(player_message_time) AS first_player_time, count(*) AS cnt FROM chats WHERE video_id = :videoId GROUP BY (player_message_time - (player_message_time % 60000)) ORDER BY cnt DESC LIMIT 5", nativeQuery = true)
    List<Object[]> findPeakMinute(@Param("videoId") String videoId);

    @Query(value = "SELECT (player_message_time - (player_message_time % 60000)) AS minute_offset, MIN(player_message_time) AS first_player_time, count(*) AS cnt FROM chats WHERE video_id = :videoId AND message ILIKE '%' || :keyword || '%' GROUP BY (player_message_time - (player_message_time % 60000)) ORDER BY cnt DESC LIMIT 5", nativeQuery = true)
    List<Object[]> findKeywordPeakMinute(@Param("videoId") String videoId, @Param("keyword") String keyword);

    List<Chat> findByVideoId(String videoId);

    @Query(value = "SELECT player_message_time FROM chats WHERE video_id = :videoId AND player_message_time IN (:times)", nativeQuery = true)
    List<Long> findExistingMessageTimes(@Param("videoId") String videoId, @Param("times") List<Long> times);

    /** 비수집 요청 처리: 해당 회원의 채팅을 모두 삭제하고 삭제 건수를 반환한다. */
    @Modifying
    @Query(value = "DELETE FROM chats WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") String userId);

    long countByUserId(String userId);

    /** 비수집 등록 전 확인용: 해당 uid 로 저장된 닉네임 목록 */
    @Query(value = "SELECT DISTINCT username FROM chats WHERE user_id = :userId AND username IS NOT NULL LIMIT 10",
            nativeQuery = true)
    List<String> findNicknamesByUserId(@Param("userId") String userId);
}
