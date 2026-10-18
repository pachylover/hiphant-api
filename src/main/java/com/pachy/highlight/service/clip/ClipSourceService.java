package com.pachy.highlight.service.clip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pachy.highlight.dto.clip.ClipSessionDtos.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

@Service
public class ClipSourceService {
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");
    private final SafeMediaClient media;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore resolving = new java.util.concurrent.Semaphore(4);
    private record Source(Rendition rendition, String url) {}
    private record StoredSession(Session response, Map<String, Source> sources) {}
    @org.springframework.beans.factory.annotation.Autowired
    public ClipSourceService(SafeMediaClient media, ObjectMapper mapper) { this(media, mapper, Clock.systemUTC()); }
    ClipSourceService(SafeMediaClient media, ObjectMapper mapper, Clock clock) { this.media = media; this.mapper = mapper; this.clock = clock; }
    public Session create(String videoId, CreateRequest request) {
        validateRange(videoId, request);
        if (request.naverSession() != null && !request.naverSession().isValid())
            throw new ClipSourceException(400, "INVALID_NAVER_SESSION", "네이버 세션 값 두 개를 올바르게 입력해 주세요.");
        if (!resolving.tryAcquire()) throw new ClipSourceException(429, "SESSION_LIMIT", "잠시 후 다시 시도해 주세요.");
        try { return resolve(videoId, request); } finally { resolving.release(); }
    }
    private Session resolve(String videoId, CreateRequest request) {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !s.response.expiresAt().isAfter(now));
        if (sessions.size() >= 200) throw new ClipSourceException(429, "SESSION_LIMIT", "잠시 후 다시 시도해 주세요.");
        JsonNode metadata;
        try {
            metadata = json((request.naverSession() == null
                    ? media.get("https://api.chzzk.naver.com/service/v3/videos/" + videoId)
                    : media.getVideoMetadata(videoId, request.naverSession())).body());
            if (metadata.path("code").asInt(200) != 200)
                throw SafeMediaClient.upstream(metadata.path("code").asInt());
        } catch (ClipSourceException error) {
            if (error.status() == 403 && request.naverSession() == null)
                throw new ClipSourceException(403, "NAVER_SESSION_REQUIRED", "영상 접근 인증이 필요합니다. 네이버 세션을 입력해 주세요.");
            if (error.status() == 403)
                throw new ClipSourceException(403, "NAVER_AUTH_REQUIRED", "성인 인증된 계정의 네이버 세션을 다시 입력해 주세요.");
            throw error;
        }
        JsonNode video = metadata.path("content");
        if (video.isMissingNode() || video.isNull()) throw SafeMediaClient.upstream(404);
        if (video.path("adult").asBoolean() && request.naverSession() == null)
            throw new ClipSourceException(403, "NAVER_SESSION_REQUIRED", "연령 제한 영상입니다. 성인 인증된 계정의 네이버 세션을 입력해 주세요.");
        if (video.path("paid").asBoolean()
                || video.hasNonNull("paidProductId") || video.hasNonNull("paidProduct")
                || (video.hasNonNull("membershipBenefitType") && !"NONE".equals(video.path("membershipBenefitType").asText()))
                || (video.hasNonNull("blindType") && !"NONE".equals(video.path("blindType").asText()))
                || video.path("subscriptionOnly").asBoolean() || video.path("drm").asBoolean() || video.path("isDrm").asBoolean()
                || (video.hasNonNull("videoAccessType") && !"ALL".equals(video.path("videoAccessType").asText()))) throw SafeMediaClient.upstream(403);
        int duration = video.path("duration").asInt();
        if (duration <= 0 || request.endSeconds() > duration) throw invalidRange();
        Map<String, Source> sources = new LinkedHashMap<>();
        String rewind = video.path("liveRewindPlaybackJson").asText("");
        if (!rewind.isBlank()) for (JsonNode item : json(rewind).path("media")) {
            if (sources.size() >= 12) break;
            String path = item.path("path").asText("");
            if (!path.isBlank()) addHls(sources, path, request.endSeconds() - request.startSeconds());
        }
        // Naver playback provides progressive renditions even for ABR_HLS metadata.
        // Identifiers originate from metadata (authenticated for age-restricted videos), never user-supplied URLs.
        JsonNode progressive = mapper.createObjectNode();
        if (sources.isEmpty() && video.path("videoId").asText("").matches("[A-Za-z0-9_-]{1,100}")
                && !video.path("inKey").asText("").isBlank()) {
            String key = java.net.URLEncoder.encode(video.path("inKey").asText(), java.nio.charset.StandardCharsets.UTF_8);
            progressive = json(media.get("https://play.rmcnmv.naver.com/vod/play/v2.0/"
                    + video.path("videoId").asText() + "?key=" + key).body());
        }
        for (JsonNode item : progressive.path("videos").path("list")) {
            String source = item.path("source").asText("");
            if (!source.isBlank() && sources.size() < 12) {
                media.validate(source);
                String id = "mp4-" + sources.size();
                long size = item.path("size").asLong();
                Long estimate = size > 0 ? (long)Math.ceil(size * ((double)(request.endSeconds() - request.startSeconds()) / duration)) : null;
                sources.put(id, new Source(new Rendition(id, item.path("encodingOption").path("height").asInt(), "PROGRESSIVE_MP4", estimate), source));
            }
        }
        if (sources.isEmpty()) throw ClipSourceException.unsupported();
        String id = UUID.randomUUID().toString();
        Session result = new Session(id, clock.instant().plusSeconds(300), duration, video.path("videoTitle").asText("영상"),
                video.path("channel").path("channelName").asText("채널"), sources.values().stream().map(Source::rendition).toList());
        synchronized (sessions) {
            if (sessions.size() >= 200) throw new ClipSourceException(429, "SESSION_LIMIT", "잠시 후 다시 시도해 주세요.");
            sessions.put(id, new StoredSession(result, Map.copyOf(sources)));
        }
        return result;
    }
    static void validateRange(String videoId, CreateRequest request) {
        if (videoId == null || !videoId.matches("[1-9][0-9]{0,19}")) throw new ClipSourceException(400, "INVALID_VIDEO_ID", "올바른 영상 ID가 필요합니다.");
        if (request == null || request.startSeconds() == null || request.endSeconds() == null || request.startSeconds() < 0
                || request.endSeconds() <= request.startSeconds() || (long)request.endSeconds() - request.startSeconds() > 600) throw invalidRange();
    }
    private void addHls(Map<String, Source> sources, String path, int length) {
        SafeMediaClient.Text text = media.get(path);
        if (!text.body().startsWith("#EXTM3U")) throw ClipSourceException.unsupported();
        String[] lines = text.body().split("\\R");
        boolean master = false;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
            master = true;
            Map<String, String> attrs = attributes(lines[i]);
            if (attrs.containsKey("AUDIO")) continue;
            int next = i + 1;
            while (next < lines.length && (lines[next].isBlank() || lines[next].startsWith("#"))) next++;
            if (next == lines.length || sources.size() >= 12) continue;
            String url = media.validate(text.uri().resolve(lines[next].trim()).toString()).toString();
            String id = "hls-" + sources.size();
            int height = 0;
            try { height = Integer.parseInt(attrs.getOrDefault("RESOLUTION", "0x0").split("x")[1]); } catch (RuntimeException ignored) {}
            Long estimate = null;
            try { estimate = Math.multiplyExact(Long.parseLong(attrs.get("BANDWIDTH")), length) / 8; } catch (RuntimeException ignored) {}
            sources.put(id, new Source(new Rendition(id, height, "HLS_FMP4", estimate), url));
        }
        if (!master) {
            validatePlaylist(text);
            String id = "hls-" + sources.size();
            sources.put(id, new Source(new Rendition(id, 0, "HLS_FMP4", null), text.uri().toString()));
        }
    }
    public Manifest manifest(String sessionId, String renditionId) {
        StoredSession session = sessions.get(sessionId);
        if (session == null || !session.response.expiresAt().isAfter(clock.instant())) {
            sessions.remove(sessionId);
            throw new ClipSourceException(410, "SESSION_EXPIRED", "다운로드 세션이 만료되었습니다. 다시 시도해 주세요.");
        }
        Source source = session.sources.get(renditionId);
        if (source == null) throw new ClipSourceException(404, "RENDITION_NOT_FOUND", "선택한 화질을 찾을 수 없습니다.");
        if (source.rendition.mode().equals("PROGRESSIVE_MP4")) return new Manifest("PROGRESSIVE_MP4", source.url, null, null);
        SafeMediaClient.Text text = media.get(source.url);
        validatePlaylist(text);
        return new Manifest("HLS_FMP4", null, text.body(), text.uri().toString());
    }
    void validatePlaylist(SafeMediaClient.Text text) {
        String playlist = text.body();
        if (!playlist.startsWith("#EXTM3U") || !playlist.contains("#EXT-X-MAP:") || !playlist.contains("#EXT-X-ENDLIST")
                || playlist.contains("#EXT-X-STREAM-INF:") || Pattern.compile("(?m)^#EXT-X-DISCONTINUITY\\s*$").matcher(playlist).find() || playlist.contains("#EXT-X-SESSION-KEY")) throw ClipSourceException.unsupported();
        for (String raw : playlist.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-KEY:") && !"NONE".equals(attributes(line).get("METHOD"))) throw ClipSourceException.unsupported();
            if (!line.isBlank() && !line.startsWith("#")) media.validate(text.uri().resolve(line).toString());
            if (line.startsWith("#")) {
                String url = attributes(line).get("URI");
                if (url != null) media.validate(text.uri().resolve(url).toString());
            }
        }
    }
    private static Map<String, String> attributes(String line) {
        Matcher matcher = ATTRIBUTE.matcher(line.substring(line.indexOf(':') + 1));
        Map<String, String> result = new LinkedHashMap<>();
        while (matcher.find()) result.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        return result;
    }
    private JsonNode json(String body) { try { JsonNode node = mapper.readTree(body); if (node == null || !node.isObject()) throw ClipSourceException.unsupported(); return node; } catch (Exception e) { throw ClipSourceException.unsupported(); } }
    private static ClipSourceException invalidRange() { return new ClipSourceException(400, "INVALID_RANGE", "영상 내에서 1초 이상 600초 이하의 구간을 선택해 주세요."); }
}
