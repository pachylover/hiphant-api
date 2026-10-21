package com.pachy.highlight.entity;

import lombok.*;

import com.pachy.highlight.dto.HighlightResponse;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "highlights")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Highlight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "bigserial", updatable = false, nullable = false)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private String videoId;

    // 치지직 다시보기 제목. 생성 시점에 채워지며, 과거 데이터는 배치로 백필된다.
    @Column(name = "keyword")
    private String keyword;

    @Column(name = "source_chat_id")
    private Long sourceChatId;

    @Column(name = "video_title")
    private String videoTitle;

    private String thumbnailImageUrl;
    private String publishDate;

    // 스트리머(채널) 정보. 같은 스트리머의 다른 하이라이트를 묶어 보여주기 위해 저장한다.
    @Column(name = "channel_id")
    private String channelId;

    @Column(name = "channel_name")
    private String channelName;

    @Column(name = "minute", nullable = false, columnDefinition = "bigint")
    private Long minute;

    @Column(name = "start_ts", columnDefinition = "bigint")
    private Long startTs;

    @Column(name = "end_ts", columnDefinition = "bigint")
    private Long endTs;

    @Column(name = "chat_count")
    private Integer chatCount;

    @Column(name = "title")
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "highlight_type")
    private String highlightType;

    @Column(name = "status")
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        if (this.updatedAt == null) this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public HighlightResponse toResponse() {
        return HighlightResponse.builder()
                .taskId(id != null ? id.toString() : null)
                .status(status)
                .videoId(videoId)
                .videoTitle(videoTitle)
                .channelId(channelId)
                .channelName(channelName)
                .minute(minute != null ? minute : 0L)
                .start(startTs != null ? startTs : 0L)
                .end(endTs != null ? endTs : 0L)
                .chatCount(chatCount)
                .title(title)
                .summary(summary)
                .keyword(keyword)
                .highlightType(highlightType)
                .build();
    }
}
