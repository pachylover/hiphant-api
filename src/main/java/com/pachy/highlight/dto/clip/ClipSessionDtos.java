package com.pachy.highlight.dto.clip;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;

public final class ClipSessionDtos {
    private ClipSessionDtos() {}
    public record CreateRequest(@NotNull @PositiveOrZero Integer startSeconds,
                                @NotNull @PositiveOrZero Integer endSeconds,
                                NaverSession naverSession) {
        public CreateRequest(Integer startSeconds, Integer endSeconds) { this(startSeconds, endSeconds, null); }
    }
    public record NaverSession(String nidAut, String nidSes) {
        public boolean isValid() { return validCookie(nidAut) && validCookie(nidSes); }
        private static boolean validCookie(String value) {
            // RFC 6265 cookie-octet; reject header injection and oversized credentials.
            return value != null && !value.isEmpty() && value.length() <= 4096
                    && value.chars().allMatch(c -> c == 0x21 || c >= 0x23 && c <= 0x2b
                    || c >= 0x2d && c <= 0x3a || c >= 0x3c && c <= 0x5b || c >= 0x5d && c <= 0x7e);
        }
        @Override public String toString() { return "NaverSession[redacted]"; }
    }
    public record Rendition(String id, int height, String mode, Long estimatedBytes) {}
    public record Session(String sessionId, Instant expiresAt, int durationSeconds,
                          String title, String channelName, List<Rendition> renditions) {}
    public record Manifest(String mode, String url, String playlist, String baseUrl) {}
    public record Error(String code, String message, String requestId, boolean retryable) {}
}
