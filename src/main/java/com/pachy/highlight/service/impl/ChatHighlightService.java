package com.pachy.highlight.service.impl;

import com.pachy.highlight.dto.ChzzkVideoResponse;
import com.pachy.highlight.dto.HighlightResponse;
import com.pachy.highlight.entity.Chat;
import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.ChatRepository;
import com.pachy.highlight.repository.HighlightRepository;
import com.pachy.highlight.service.VideoInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatHighlightService {
    private final ChatRepository chatRepository;
    private final HighlightRepository highlightRepository;
    private final VideoInfoService videoInfoService;

    @Transactional
    public HighlightResponse create(String videoId, Long chatId) {
        if (videoId == null || !videoId.matches("[0-9]{1,20}") || chatId == null || chatId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid video or chat ID");
        }
        // Serialize requests for the same chat across instances. The unique key is a second guard.
        Chat chat = chatRepository.findForHighlight(videoId, chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found for this video"));
        Highlight existing = highlightRepository.findBySourceChatId(chatId).orElse(null);
        if (existing != null) return existing.toResponse();
        Long time = chat.getPlayerMessageTime();
        if (time == null || time < 0 || time > Long.MAX_VALUE - 90_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chat has no valid video timestamp");
        }

        ChzzkVideoResponse info;
        try {
            info = videoInfoService.get(videoId);
        } catch (Exception ignored) {
            info = null;
        }
        Integer duration = info != null ? info.getDuration() : null;
        long minute = duration != null && duration > 0 ? Math.min(time, duration * 1000L - 1) : time;
        HighlightProcessor.ClipBounds bounds = HighlightProcessor.calculateClipBounds(minute, duration);
        String message = chat.getMessage() == null ? "" : chat.getMessage().strip();
        String title = message.isEmpty() ? "선택한 채팅 하이라이트" : message.substring(0,
                message.offsetByCodePoints(0, Math.min(200, message.codePointCount(0, message.length()))));
        ChzzkVideoResponse.Channel channel = info != null ? info.getChannel() : null;
        Highlight highlight = Highlight.builder()
                .videoId(videoId).sourceChatId(chatId).minute(minute)
                .videoTitle(info != null ? info.getVideoTitle() : null)
                .channelId(channel != null ? channel.getChannelId() : null)
                .channelName(channel != null ? channel.getChannelName() : null)
                .startTs(bounds.startMs()).endTs(bounds.endMs())
                .title(title).chatCount(1).highlightType("CHAT").status("DONE").build();
        return highlightRepository.save(highlight).toResponse();
    }
}
