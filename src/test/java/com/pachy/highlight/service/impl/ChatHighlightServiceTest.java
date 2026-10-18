package com.pachy.highlight.service.impl;

import com.pachy.highlight.dto.ChzzkVideoResponse;
import com.pachy.highlight.entity.Chat;
import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.ChatRepository;
import com.pachy.highlight.repository.HighlightRepository;
import com.pachy.highlight.service.VideoInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatHighlightServiceTest {
    private final ChatRepository chats = mock(ChatRepository.class);
    private final HighlightRepository highlights = mock(HighlightRepository.class);
    private final VideoInfoService videos = mock(VideoInfoService.class);
    private final ChatHighlightService service = new ChatHighlightService(chats, highlights, videos);

    @BeforeEach
    void setUp() {
        when(highlights.save(any())).thenAnswer(invocation -> {
            Highlight highlight = invocation.getArgument(0);
            highlight.setId(99L);
            return highlight;
        });
    }

    @Test
    void createsVideoRelativeRangeAndClampsToDuration() {
        when(chats.findForHighlight("15727339", 1L)).thenReturn(Optional.of(Chat.builder()
                .id(1L).videoId("15727339").playerMessageTime(10_000L).message("😀".repeat(250)).build()));
        ChzzkVideoResponse info = new ChzzkVideoResponse();
        info.setDuration(60);
        when(videos.get("15727339")).thenReturn(info);
        var result = service.create("15727339", 1L);
        assertThat(result.getStart()).isZero();
        assertThat(result.getEnd()).isEqualTo(60_000L);
        assertThat(result.getMinute()).isEqualTo(10_000L);
        assertThat(result.getHighlightType()).isEqualTo("CHAT");
        assertThat(result.getTitle().codePointCount(0, result.getTitle().length())).isEqualTo(200);
    }

    @Test
    void rejectsChatOutsideTheVideo() {
        assertThatThrownBy(() -> service.create("15727339", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(videos);
        verify(highlights, never()).save(any());
    }

    @Test
    void duplicateReturnsExistingHighlight() {
        when(chats.findForHighlight("15727339", 1L)).thenReturn(Optional.of(new Chat()));
        when(highlights.findBySourceChatId(1L)).thenReturn(Optional.of(Highlight.builder().id(99L).build()));
        assertThat(service.create("15727339", 1L).getTaskId()).isEqualTo("99");
        verify(highlights, never()).save(any());
        verifyNoInteractions(videos);
    }

    @Test
    void rejectsMissingTimestamp() {
        when(chats.findForHighlight("15727339", 1L)).thenReturn(Optional.of(new Chat()));
        assertThatThrownBy(() -> service.create("15727339", 1L)).isInstanceOf(ResponseStatusException.class);
        verify(highlights, never()).save(any());
    }
}
