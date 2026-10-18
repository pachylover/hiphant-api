package com.pachy.highlight.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class HighlightResponse {
    private String taskId;
    private String status;
    private String videoId;
    private String videoTitle;
    private String channelId;
    private String channelName;
    private long minute;
    private long start;
    private long end;
    private Integer chatCount;
    private String title;
    private String summary;
    private String highlightType;
    private String keyword;
    private List<Object> chatSnippet;
}
