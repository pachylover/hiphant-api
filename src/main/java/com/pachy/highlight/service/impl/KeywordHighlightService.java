package com.pachy.highlight.service.impl;

import com.pachy.highlight.dto.ChzzkVideoResponse;
import com.pachy.highlight.dto.HighlightResponse;
import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.ChatRepository;
import com.pachy.highlight.repository.HighlightRepository;
import com.pachy.highlight.service.VideoInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class KeywordHighlightService {
    private final ChatRepository chatRepository;
    private final HighlightRepository highlightRepository;
    private final VideoInfoService videoInfoService;

    @Transactional
    public List<HighlightResponse> create(String videoId, String input) {
        String keyword = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
        if (videoId == null || !videoId.matches("[0-9]{1,20}") || keyword.isBlank()
                || keyword.codePointCount(0, keyword.length()) > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a keyword between 1 and 50 characters");
        }
        // Transaction-scoped database lock serializes equivalent requests across app instances.
        highlightRepository.lockKeyword(videoId + ":" + keyword);
        List<Highlight> existing = highlightRepository
                .findByVideoIdAndKeywordAndHighlightTypeOrderByChatCountDescMinuteAsc(videoId, keyword, "KEYWORD");
        if (!existing.isEmpty()) return existing.stream().map(Highlight::toResponse).toList();

        List<Object[]> peaks = chatRepository.findLiteralKeywordPeaks(videoId, keyword);
        if (peaks.isEmpty()) return List.of();
        ChzzkVideoResponse info;
        try {
            info = videoInfoService.get(videoId);
        } catch (Exception ignored) {
            info = null;
        }
        Integer duration = info != null ? info.getDuration() : null;
        ChzzkVideoResponse.Channel channel = info != null ? info.getChannel() : null;
        List<HighlightResponse> results = new ArrayList<>();
        for (Object[] row : peaks) {
            long minute = ((Number) row[0]).longValue();
            if (minute > Long.MAX_VALUE - 90_000L || (duration != null && duration > 0 && minute >= duration * 1000L)) {
                continue;
            }
            int count = (int) Math.min(Integer.MAX_VALUE, ((Number) row[1]).longValue());
            var bounds = HighlightProcessor.calculateClipBounds(minute, duration);
            Highlight highlight = Highlight.builder()
                    .videoId(videoId).keyword(keyword).minute(minute)
                    .videoTitle(info != null ? info.getVideoTitle() : null)
                    .thumbnailImageUrl(info != null ? info.getThumbnailImageUrl() : null)
                    .publishDate(info != null ? info.getPublishDate() : null)
                    .channelId(channel != null ? channel.getChannelId() : null)
                    .channelName(channel != null ? channel.getChannelName() : null)
                    .startTs(bounds.startMs()).endTs(bounds.endMs()).chatCount(count)
                    .title(count + "개의 " + keyword + " 채팅이 발생한 구간")
                    .highlightType("KEYWORD").status("DONE").build();
            results.add(highlightRepository.save(highlight).toResponse());
        }
        return results;
    }
}
