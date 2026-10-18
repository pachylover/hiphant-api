package com.pachy.highlight.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HighlightProcessorTest {

    @Test
    void clipRangeDoesNotStartBeforeVideo() {
        HighlightProcessor.ClipBounds bounds = HighlightProcessor.calculateClipBounds(10_000L, 300);

        assertThat(bounds.startMs()).isZero();
        assertThat(bounds.endMs()).isEqualTo(100_000L);
    }

    @Test
    void clipRangeDoesNotEndAfterKnownDuration() {
        HighlightProcessor.ClipBounds bounds = HighlightProcessor.calculateClipBounds(290_000L, 300);

        assertThat(bounds.startMs()).isEqualTo(260_000L);
        assertThat(bounds.endMs()).isEqualTo(300_000L);
    }

    @Test
    void clipRangeRemainsValidWhenMinuteIsPastKnownDuration() {
        HighlightProcessor.ClipBounds bounds = HighlightProcessor.calculateClipBounds(400_000L, 300);

        assertThat(bounds.startMs()).isEqualTo(299_999L);
        assertThat(bounds.endMs()).isEqualTo(300_000L);
    }

    @Test
    void clipRangeUsesSuggestedWindowWhenDurationIsUnknown() {
        HighlightProcessor.ClipBounds bounds = HighlightProcessor.calculateClipBounds(60_000L, null);

        assertThat(bounds.startMs()).isEqualTo(30_000L);
        assertThat(bounds.endMs()).isEqualTo(150_000L);
    }
}
