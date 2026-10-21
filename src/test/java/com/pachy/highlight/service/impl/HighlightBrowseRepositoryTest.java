package com.pachy.highlight.service.impl;

import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.HighlightRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class HighlightBrowseRepositoryTest {
    @Autowired HighlightRepository highlights;

    @Test
    void distinctVideosArePagedWithStableTiesAndLiteralNameSearch() {
        String channel = UUID.randomUUID().toString();
        save(channel + "-1", channel, "테스트%_Streamer", 1);
        var latest = save(channel + "-1", channel, "테스트%_Streamer", 2);
        var second = save(channel + "-2", channel, "테스트%_Streamer", 2);
        highlights.flush();

        var firstPage = highlights.browseHighlights("%_streamer", channel, PageRequest.of(0, 1));
        var nextPage = highlights.browseHighlights("%_STREAMER", channel, PageRequest.of(1, 1));
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(Highlight::getId).containsExactly(second.getId());
        assertThat(nextPage.getContent()).extracting(Highlight::getId).containsExactly(latest.getId());
        assertThat(highlights.browseHighlights("missing", channel, PageRequest.of(0, 12))).isEmpty();
    }

    @Test
    void streamerSearchDeduplicatesChannelsAndUsesLatestName() {
        String channel = UUID.randomUUID().toString();
        String name = "이름-" + channel;
        save(channel + "-1", channel, "이전-" + channel, 1);
        save(channel + "-2", channel, name, 2);
        highlights.flush();
        assertThat(highlights.searchStreamers(name)).extracting(Highlight::getChannelId).containsExactly(channel);
        assertThat(highlights.searchStreamers("이전-" + channel)).isEmpty();
        assertThat(highlights.browseHighlights("", "unknown-" + channel, PageRequest.of(0, 12))).isEmpty();
    }

    @Test
    void metadataBackfillPersistsDisplayFieldsAndKeepsExistingValuesOnFailure() {
        String id = UUID.randomUUID().toString();
        save(id, null, null, 1);
        highlights.flush();
        assertThat(highlights.findVideoIdsMissingMeta()).contains(id);
        highlights.updateVideoMeta(id, "방송 제목", "channel", "스트리머", "https://example.com/thumb.jpg", "2026-10-01");
        highlights.updateVideoMeta(id, null, null, null, null, null);
        var item = highlights.browseHighlights("스트리머", "channel", PageRequest.of(0, 48)).stream()
                .filter(h -> id.equals(h.getVideoId())).findFirst().orElseThrow();
        // Native updates bypass the persistence context; read DTO fields through a fresh entity below.
        entityManager.clear();
        item = highlights.findById(item.getId()).orElseThrow();
        assertThat(item.getThumbnailImageUrl()).isEqualTo("https://example.com/thumb.jpg");
        assertThat(item.getPublishDate()).isEqualTo("2026-10-01");
        assertThat(item.getChannelName()).isEqualTo("스트리머");
        assertThat(highlights.findVideoIdsMissingMeta()).doesNotContain(id);
    }

    @Autowired jakarta.persistence.EntityManager entityManager;

    private Highlight save(String video, String channel, String name, long seconds) {
        return highlights.save(Highlight.builder().videoId(video).channelId(channel).channelName(name)
                .minute(1000L).status("DONE").createdAt(Instant.ofEpochSecond(seconds)).build());
    }
}
