package com.pachy.highlight.service.impl;

import com.pachy.highlight.dto.ChzzkVideoResponse;
import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.ChatRepository;
import com.pachy.highlight.repository.HighlightRepository;
import com.pachy.highlight.service.VideoInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeywordHighlightServiceTest {
    private final ChatRepository chats = mock(ChatRepository.class);
    private final HighlightRepository highlights = mock(HighlightRepository.class);
    private final VideoInfoService videos = mock(VideoInfoService.class);
    private final KeywordHighlightService service = new KeywordHighlightService(chats, highlights, videos);

    @Test
    void normalizesKeywordAndClampsRangeToVideo() {
        when(chats.findLiteralKeywordPeaks("15727339", "wow%_")).thenReturn(
                Collections.singletonList(new Object[]{10_000L, 3L}));
        var info = new ChzzkVideoResponse();
        info.setDuration(60);
        when(videos.get("15727339")).thenReturn(info);
        when(highlights.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.create("15727339", " WOW%_ ").getFirst();
        assertThat(result.getKeyword()).isEqualTo("wow%_");
        assertThat(result.getStart()).isZero();
        assertThat(result.getEnd()).isEqualTo(60_000L);
        assertThat(result.getChatCount()).isEqualTo(3);
        assertThat(result.getTitle()).isEqualTo("3개의 wow%_ 채팅이 발생한 구간");
        assertThat(result.getHighlightType()).isEqualTo("KEYWORD");
        var order = inOrder(highlights, chats);
        order.verify(highlights).lockKeyword("15727339:wow%_");
        order.verify(highlights).findByVideoIdAndKeywordAndHighlightTypeOrderByChatCountDescMinuteAsc("15727339", "wow%_", "KEYWORD");
        order.verify(chats).findLiteralKeywordPeaks("15727339", "wow%_");
    }

    @Test
    void duplicateReturnsSavedResultsWithoutReanalysis() {
        when(highlights.findByVideoIdAndKeywordAndHighlightTypeOrderByChatCountDescMinuteAsc("15727339", "wow", "KEYWORD"))
                .thenReturn(List.of(Highlight.builder().id(12L).keyword("wow").build()));
        assertThat(service.create("15727339", "WOW").getFirst().getTaskId()).isEqualTo("12");
        verifyNoInteractions(chats, videos);
        verify(highlights, never()).save(any());
    }

    @Test
    void noDenseMinuteReturnsEmptyWithoutMetadataLookup() {
        assertThat(service.create("15727339", "안녕")).isEmpty();
        verifyNoInteractions(videos);
        verify(highlights, never()).save(any());
    }

    @Test
    void rejectsInvalidKeywordsAndVideoIds() {
        for (String keyword : new String[]{null, "  ", "a".repeat(51)}) {
            assertThatThrownBy(() -> service.create("15727339", keyword)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.create("bad", "hello")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(highlights, chats, videos);
    }

    @Test
    void acceptsFiftyUnicodeCodepoints() {
        assertThat(service.create("15727339", "😀".repeat(50))).isEmpty();
    }

    @Test
    void skipsTimestampsOutsideKnownDuration() {
        when(chats.findLiteralKeywordPeaks("15727339", "wow")).thenReturn(
                Collections.singletonList(new Object[]{61_000L, 3L}));
        var info = new ChzzkVideoResponse();
        info.setDuration(60);
        when(videos.get("15727339")).thenReturn(info);
        assertThat(service.create("15727339", "wow")).isEmpty();
        verify(highlights, never()).save(any());
    }
}
