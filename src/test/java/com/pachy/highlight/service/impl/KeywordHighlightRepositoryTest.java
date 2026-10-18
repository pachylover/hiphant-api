package com.pachy.highlight.service.impl;

import com.pachy.highlight.entity.Chat;
import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.ChatRepository;
import com.pachy.highlight.repository.HighlightRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class KeywordHighlightRepositoryTest {
    @Autowired ChatRepository chats;
    @Autowired HighlightRepository highlights;

    @Test
    void literalMatchingMinimumFrequencyAndStableTopFive() {
        String videoId = "keyword-test-" + UUID.randomUUID();
        for (int minute = 0; minute < 7; minute++) {
            addChat(videoId, minute * 60_000L + 1000, "WOW%_ first");
            addChat(videoId, minute * 60_000L + 2000, "wow%_ second");
        }
        addChat(videoId, 7 * 60_000L, "wow%_ single");
        addChat(videoId, 7 * 60_000L + 1000, "wowXY wildcard decoy");
        addChat(videoId, -1000, "wow%_ negative");
        chats.flush();
        var peaks = chats.findLiteralKeywordPeaks(videoId, "wow%_");
        assertThat(peaks).hasSize(5);
        for (int index = 0; index < 5; index++) {
            assertThat(((Number) peaks.get(index)[0]).longValue()).isEqualTo(index * 60_000L + 1000);
            assertThat(((Number) peaks.get(index)[1]).longValue()).isEqualTo(2);
        }
        assertThat(highlights.lockKeyword(videoId + ":wow%_")).isEqualTo(1);
    }

    @Test
    void keywordUniqueIndexRejectsDuplicateMinute() {
        String videoId = "keyword-test-" + UUID.randomUUID();
        highlights.saveAndFlush(highlight(videoId));
        assertThatThrownBy(() -> highlights.saveAndFlush(highlight(videoId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void addChat(String videoId, long time, String message) {
        chats.save(Chat.builder().videoId(videoId).userId(UUID.randomUUID().toString())
                .playerMessageTime(time).message(message).build());
    }

    private Highlight highlight(String videoId) {
        return Highlight.builder().videoId(videoId).minute(1000L).keyword("wow")
                .highlightType("KEYWORD").status("DONE").build();
    }
}
