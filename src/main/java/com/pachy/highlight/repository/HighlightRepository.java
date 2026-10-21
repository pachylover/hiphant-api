package com.pachy.highlight.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pachy.highlight.entity.Highlight;

import java.time.Instant;
import java.util.List;

public interface HighlightRepository extends JpaRepository<Highlight, Long> {
  List<Highlight> findAllByVideoIdOrderByChatCountDesc(String videoId);

  java.util.Optional<Highlight> findBySourceChatId(Long sourceChatId);

  List<Highlight> findByVideoIdAndKeywordAndHighlightTypeOrderByChatCountDescMinuteAsc(
          String videoId, String keyword, String highlightType);

  @Query(value = "SELECT count(*) FROM pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
  long lockKeyword(@Param("lockKey") String lockKey);

  @Modifying
  @Query(value = "DELETE FROM highlights WHERE source_chat_id IN " +
          "(SELECT id FROM chats WHERE user_id = :userId)", nativeQuery = true)
  int deleteBySourceChatUserId(@Param("userId") String userId);

  // 최근 생성된 하이라이트 (관리자 대시보드)
  List<Highlight> findAllByOrderByCreatedAtDesc(Pageable pageable);

  // 공개 홈용: 영상별 최신 하이라이트 1건씩, 최근 생성 순 (중복 영상 제거)
  @Query(value = "SELECT * FROM (" +
          "  SELECT DISTINCT ON (video_id) * FROM highlights ORDER BY video_id, created_at DESC, id DESC" +
          ") t ORDER BY created_at DESC, id DESC LIMIT :limit", nativeQuery = true)
  List<Highlight> findRecentDistinctVideoHighlights(@Param("limit") int limit);

  /**
   * 같은 스트리머(채널)의 다른 하이라이트 — 영상별 최신 1건씩, 최근 생성 순.
   * 현재 보고 있는 영상은 제외한다.
   */
  @Query(value = "SELECT * FROM (" +
          "  SELECT DISTINCT ON (video_id) * FROM highlights " +
          "  WHERE channel_id = :channelId AND video_id <> :excludeVideoId " +
          "  ORDER BY video_id, created_at DESC, id DESC" +
          ") t ORDER BY created_at DESC, id DESC LIMIT :limit", nativeQuery = true)
  List<Highlight> findChannelHighlights(@Param("channelId") String channelId,
                                        @Param("excludeVideoId") String excludeVideoId,
                                        @Param("limit") int limit);

  // 영상 제목/채널 정보가 아직 채워지지 않은 영상 ID 목록 (백필 대상)
  @Query(value = "SELECT DISTINCT video_id FROM highlights " +
          "WHERE video_title IS NULL OR video_title = '' OR channel_id IS NULL OR channel_name IS NULL OR channel_name = '' OR thumbnail_image_url IS NULL OR publish_date IS NULL",
          nativeQuery = true)
  List<String> findVideoIdsMissingMeta();

  /** 조회에 성공한 값만 덮어쓴다(치지직 조회 실패로 NULL 이 오면 기존 값을 유지). */
  @Modifying
  @Query(value = "UPDATE highlights SET " +
          "video_title = COALESCE(CAST(:title AS text), video_title), " +
          "channel_id = COALESCE(CAST(:channelId AS text), channel_id), " +
          "channel_name = COALESCE(CAST(:channelName AS text), channel_name), " +
          "thumbnail_image_url = COALESCE(CAST(:thumbnail AS text), thumbnail_image_url), " +
          "publish_date = COALESCE(CAST(:publishDate AS text), publish_date), " +
          "updated_at = now() WHERE video_id = :videoId",
          nativeQuery = true)
  int updateVideoMeta(@Param("videoId") String videoId,
                      @Param("title") String title,
                      @Param("channelId") String channelId,
                      @Param("channelName") String channelName,
                      @Param("thumbnail") String thumbnail,
                      @Param("publishDate") String publishDate);

  @Query(value = "SELECT * FROM (SELECT DISTINCT ON (video_id) * FROM highlights " +
          "ORDER BY video_id, created_at DESC, id DESC) h " +
          "WHERE (:channelId = '' OR channel_id = :channelId) " +
          "AND (:query = '' OR strpos(lower(channel_name), lower(:query)) > 0) " +
          "ORDER BY created_at DESC, id DESC",
          countQuery = "SELECT count(*) FROM (SELECT DISTINCT ON (video_id) * FROM highlights " +
                  "ORDER BY video_id, created_at DESC, id DESC) h " +
                  "WHERE (:channelId = '' OR channel_id = :channelId) " +
                  "AND (:query = '' OR strpos(lower(channel_name), lower(:query)) > 0)", nativeQuery = true)
  Page<Highlight> browseHighlights(
          @Param("query") String query, @Param("channelId") String channelId, Pageable pageable);

  @Query(value = "SELECT * FROM (SELECT DISTINCT ON (channel_id) * FROM highlights " +
          "WHERE channel_id IS NOT NULL AND channel_id <> '' AND channel_name IS NOT NULL " +
          "AND channel_name <> '' ORDER BY channel_id, created_at DESC, id DESC) h " +
          "WHERE (:query = '' OR strpos(lower(channel_name), lower(:query)) > 0) " +
          "ORDER BY lower(channel_name), channel_id LIMIT 20", nativeQuery = true)
  List<Highlight> searchStreamers(@Param("query") String query);

  // 통계
  @Query(value = "SELECT count(DISTINCT video_id) FROM highlights", nativeQuery = true)
  long countDistinctVideos();

  @Query(value = "SELECT count(*) FROM highlights WHERE created_at >= :from", nativeQuery = true)
  long countCreatedSince(@Param("from") Instant from);

  // 일자별 하이라이트 생성 추이 (YYYY-MM-DD, count)
  @Query(value = "SELECT to_char(created_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD') AS d, count(*) AS c " +
          "FROM highlights WHERE created_at >= :from " +
          "GROUP BY d ORDER BY d ASC", nativeQuery = true)
  List<Object[]> dailyCreatedCounts(@Param("from") Instant from);

  // 상위 영상 (하이라이트 개수 기준)
  @Query(value = "SELECT video_id, count(*) AS c, COALESCE(max(chat_count), 0) AS max_chat " +
          "FROM highlights GROUP BY video_id ORDER BY c DESC, max_chat DESC LIMIT :limit", nativeQuery = true)
  List<Object[]> topVideosByHighlightCount(@Param("limit") int limit);
}
