package com.pachy.highlight.service.clip;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pachy.highlight.dto.clip.ClipSessionDtos.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClipSourceServiceTest {
    private final SafeMediaClient media = mock(SafeMediaClient.class);
    private final Clock clock = mock(Clock.class);
    private final ClipSourceService service = new ClipSourceService(media, new ObjectMapper(), clock);
    private static final String PLAYLIST = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nsegment.m4s\n#EXT-X-ENDLIST\n";
    private void metadata(String body) {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(media.get("https://api.chzzk.naver.com/service/v3/videos/123")).thenReturn(new SafeMediaClient.Text(URI.create("https://api.chzzk.naver.com/"), body));
    }
    @Test void validatesInputBeforeNetwork() {
        for (CreateRequest request : new CreateRequest[]{new CreateRequest(-1, 10), new CreateRequest(10, 10), new CreateRequest(0, 601), new CreateRequest(null, 10)})
            assertThatThrownBy(() -> service.create("123", request)).isInstanceOf(ClipSourceException.class);
        assertThatThrownBy(() -> service.create("../secret", new CreateRequest(0, 10))).isInstanceOf(ClipSourceException.class);
        verifyNoInteractions(media);
    }
    @Test void requiresSessionForAdultAndRejectsOutOfBounds() {
        metadata("{\"content\":{\"adult\":true,\"duration\":100}}");
        assertThatThrownBy(() -> service.create("123", new CreateRequest(0, 10))).extracting("code").isEqualTo("NAVER_SESSION_REQUIRED");
        metadata("{\"content\":{\"duration\":100}}");
        assertThatThrownBy(() -> service.create("123", new CreateRequest(99, 101))).extracting("code").isEqualTo("INVALID_RANGE");
    }
    @Test void createsOpaqueSessionAndExpiresIt() throws Exception {
        String playback = new ObjectMapper().writeValueAsString("{\"media\":[{\"path\":\"https://media.example/vod.m3u8\"}]}");
        metadata("{\"content\":{\"duration\":100,\"liveRewindPlaybackJson\":" + playback + "}}");
        when(media.get("https://media.example/vod.m3u8")).thenReturn(new SafeMediaClient.Text(URI.create("https://media.example/vod.m3u8"), PLAYLIST));
        Session result = service.create("123", new CreateRequest(0, 10));
        assertThat(result.renditions()).hasSize(1);
        assertThat(service.manifest(result.sessionId(), "hls-0").playlist()).isEqualTo(PLAYLIST);
        assertThatThrownBy(() -> service.manifest(result.sessionId(), "missing")).extracting("code").isEqualTo("RENDITION_NOT_FOUND");
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:05:00Z"));
        assertThatThrownBy(() -> service.manifest(result.sessionId(), "hls-0")).extracting("code").isEqualTo("SESSION_EXPIRED");
    }
    @Test void rejectsEncryptedTsAndLiveManifests() {
        for (String playlist : new String[]{"#EXTM3U\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST", PLAYLIST.replace("#EXT-X-ENDLIST", ""), PLAYLIST + "#EXT-X-KEY:METHOD=AES-128,URI=\"key\""})
            assertThatThrownBy(() -> service.validatePlaylist(new SafeMediaClient.Text(URI.create("https://media.example/vod.m3u8"), playlist))).isInstanceOf(ClipSourceException.class);
    }
    @Test void acceptsDiscontinuitiesAndChecksEveryInitializationMapHost() {
        String playlist = PLAYLIST.replace("#EXTINF:6,", "#EXT-X-DISCONTINUITY\n#EXTINF:6,")
                .replace("#EXT-X-ENDLIST", "#EXT-X-DISCONTINUITY\n#EXT-X-MAP:URI=\"next-init.mp4\"\n#EXTINF:6,\nnext.m4s\n#EXT-X-ENDLIST");
        assertThatCode(() -> service.validatePlaylist(new SafeMediaClient.Text(URI.create("https://media.example/vod.m3u8"), playlist)))
                .doesNotThrowAnyException();
        verify(media).validate("https://media.example/init.mp4");
        verify(media).validate("https://media.example/next-init.mp4");
        SafeMediaClient client = new SafeMediaClient("media.example");
        ClipSourceService real = new ClipSourceService(client, new ObjectMapper());
        assertThatThrownBy(() -> real.validatePlaylist(new SafeMediaClient.Text(URI.create("https://media.example/vod.m3u8"),
                playlist.replace("next-init.mp4", "https://evil.example/init.mp4"))))
                .extracting("code").isEqualTo("DOWNLOAD_HOST_NOT_ALLOWED");
    }
    @Test void checksEveryManifestAssetHost() {
        SafeMediaClient client = new SafeMediaClient("media.example");
        ClipSourceService real = new ClipSourceService(client, new ObjectMapper());
        assertThatThrownBy(() -> real.validatePlaylist(new SafeMediaClient.Text(URI.create("https://media.example/vod.m3u8"), PLAYLIST.replace("segment.m4s", "https://evil.example/segment.m4s"))))
                .extracting("code").isEqualTo("DOWNLOAD_HOST_NOT_ALLOWED");
    }
    @Test void resolvesPublicProgressivePlaybackAndKeepsUrlsOutOfSessionResponse() {
        metadata("{\"content\":{\"duration\":100,\"videoId\":\"fixture-video\",\"inKey\":\"fixture\"}}");
        when(media.get("https://play.rmcnmv.naver.com/vod/play/v2.0/fixture-video?key=fixture"))
                .thenReturn(new SafeMediaClient.Text(URI.create("https://play.rmcnmv.naver.com/"),
                        "{\"videos\":{\"list\":[{\"source\":\"https://media.example/file.mp4\",\"size\":1000000,\"encodingOption\":{\"height\":720}}]}}"));
        Session result = service.create("123", new CreateRequest(10, 20));
        assertThat(result.renditions()).hasSize(1);
        assertThat(result.renditions().getFirst().mode()).isEqualTo("PROGRESSIVE_MP4");
        assertThat(result.renditions().getFirst().estimatedBytes()).isEqualTo(100000);
        assertThat(result.toString()).doesNotContain("https:", "fixture-video");
        assertThat(service.manifest(result.sessionId(), "mp4-0").url()).isEqualTo("https://media.example/file.mp4");
        verify(media).validate("https://media.example/file.mp4");
    }
    private static final NaverSession CREDENTIALS = new NaverSession("fixture-aut", "fixture-ses");
    private void authenticatedMetadata(String content) {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(media.getVideoMetadata("123", CREDENTIALS)).thenReturn(new SafeMediaClient.Text(
                URI.create("https://api.chzzk.naver.com/"), content));
    }
    @Test void resolvesAdultHlsWithAuthenticatedMetadataAndKeepsCookiesOutOfMediaRequests() throws Exception {
        String playback = new ObjectMapper().writeValueAsString("{\"media\":[{\"path\":\"https://media.example/vod.m3u8\"}]}");
        authenticatedMetadata("{\"code\":200,\"content\":{\"adult\":true,\"duration\":100,\"liveRewindPlaybackJson\":" + playback + "}}");
        when(media.get("https://media.example/vod.m3u8")).thenReturn(new SafeMediaClient.Text(URI.create("https://media.example/vod.m3u8"), PLAYLIST));
        Session result = service.create("123", new CreateRequest(0, 10, CREDENTIALS));
        assertThat(result.renditions()).hasSize(1);
        assertThat(service.manifest(result.sessionId(), "hls-0").playlist()).isEqualTo(PLAYLIST);
        assertThat(result.toString()).doesNotContain("fixture-aut", "fixture-ses");
        verify(media).getVideoMetadata("123", CREDENTIALS);
        verify(media, times(2)).get("https://media.example/vod.m3u8");
        verify(media, atLeastOnce()).validate(anyString());
        verifyNoMoreInteractions(media);
    }
    @Test void resolvesAdultProgressiveWithAuthenticatedMetadata() {
        authenticatedMetadata("{\"content\":{\"adult\":true,\"duration\":100,\"videoId\":\"fixture-video\",\"inKey\":\"fixture\"}}");
        when(media.get("https://play.rmcnmv.naver.com/vod/play/v2.0/fixture-video?key=fixture"))
                .thenReturn(new SafeMediaClient.Text(URI.create("https://play.rmcnmv.naver.com/"),
                        "{\"videos\":{\"list\":[{\"source\":\"https://media.example/file.mp4\",\"encodingOption\":{\"height\":720}}]}}"));
        Session result = service.create("123", new CreateRequest(0, 10, CREDENTIALS));
        assertThat(result.renditions().getFirst().mode()).isEqualTo("PROGRESSIVE_MP4");
        assertThat(service.manifest(result.sessionId(), "mp4-0").url()).isEqualTo("https://media.example/file.mp4");
    }
    @Test void authenticatedSessionDoesNotAllowPaidMembershipBlindOrDrmVideos() {
        for (String restriction : new String[]{"\"paid\":true", "\"paidProductId\":1", "\"membershipBenefitType\":\"SUBSCRIPTION\"",
                "\"blindType\":\"BLOCKED\"", "\"subscriptionOnly\":true", "\"drm\":true", "\"isDrm\":true", "\"videoAccessType\":\"SUBSCRIBER\""}) {
            authenticatedMetadata("{\"content\":{\"adult\":true,\"duration\":100," + restriction + "}}");
            assertThatThrownBy(() -> service.create("123", new CreateRequest(0, 10, CREDENTIALS)))
                    .extracting("code").isEqualTo("VIDEO_RESTRICTED");
        }
        verify(media, never()).get(anyString());
    }
    @Test void rejectedSessionShowsReauthenticationMessageForHttpAndJsonFailures() {
        for (int status : new int[]{401, 403}) {
            authenticatedMetadata("{\"code\":" + status + "}");
            assertThatThrownBy(() -> service.create("123", new CreateRequest(0, 10, CREDENTIALS)))
                    .extracting("code").isEqualTo("NAVER_AUTH_REQUIRED");
        }
        when(media.getVideoMetadata("123", CREDENTIALS)).thenThrow(SafeMediaClient.upstream(403));
        assertThatThrownBy(() -> service.create("123", new CreateRequest(0, 10, CREDENTIALS)))
                .hasMessage("성인 인증된 계정의 네이버 세션을 다시 입력해 주세요.")
                .extracting("code").isEqualTo("NAVER_AUTH_REQUIRED");
    }
    @Test void anonymousAccessDenialPromptsForSessionBeforeMediaResolution() {
        metadata("{\"code\":403}");
        assertThatThrownBy(() -> service.create("123", new CreateRequest(0, 10)))
                .extracting("code").isEqualTo("NAVER_SESSION_REQUIRED");
        when(media.get("https://api.chzzk.naver.com/service/v3/videos/123")).thenThrow(SafeMediaClient.upstream(401));
        assertThatThrownBy(() -> service.create("123", new CreateRequest(0, 10)))
                .extracting("code").isEqualTo("NAVER_SESSION_REQUIRED");
    }
    @Test void rejectsIncompleteSessionBeforeNetwork() {
        assertThatThrownBy(() -> service.create("123", new CreateRequest(0, 10, new NaverSession("fixture", null))))
                .extracting("code").isEqualTo("INVALID_NAVER_SESSION");
        verifyNoInteractions(media);
    }
}
