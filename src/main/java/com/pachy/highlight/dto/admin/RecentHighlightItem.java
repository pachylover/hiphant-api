package com.pachy.highlight.dto.admin;

import com.pachy.highlight.entity.Highlight;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class RecentHighlightItem {
    private Long id;
    private String videoId;
    private String videoTitle;
    private String thumbnailImageUrl;
    private String publishDate;
    private String channelId;
    private String channelName;
    private String title;
    private Integer chatCount;
    private String highlightType;
    private Long minute;
    private Instant createdAt;

    public static RecentHighlightItem from(Highlight h) {
        return RecentHighlightItem.builder()
                .id(h.getId())
                .videoId(h.getVideoId())
                .videoTitle(h.getVideoTitle())
                .thumbnailImageUrl(h.getThumbnailImageUrl())
                .publishDate(h.getPublishDate())
                .channelId(h.getChannelId())
                .channelName(h.getChannelName())
                .title(h.getTitle())
                .chatCount(h.getChatCount())
                .highlightType(h.getHighlightType())
                .minute(h.getMinute())
                .createdAt(h.getCreatedAt())
                .build();
    }
}
