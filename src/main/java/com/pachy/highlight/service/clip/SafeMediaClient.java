package com.pachy.highlight.service.clip;

import com.pachy.highlight.dto.clip.ClipSessionDtos.NaverSession;
import io.netty.channel.ChannelOption;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Small control-plane requests only; DNS answers used for the connection are checked. */
@Component
public class SafeMediaClient {
    // Long, finalized HLS playlists can exceed 2 MiB. Keep a finite control-plane cap.
    static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private final Set<String> hosts;
    private final WebClient client;
    @org.springframework.beans.factory.annotation.Autowired
    public SafeMediaClient(@Value("${clip.media.allowed-hosts:ex-nlive-slitvod-streaming.navercdn.com,b01-kr-naver-vod.pstatic.net}") String allowedHosts,
                           @Value("${chzzk.user-agent:HiPhant/1.0 (+https://hiphant.pachylover.com)}") String userAgent) {
        this(allowedHosts, createClient(userAgent));
    }
    SafeMediaClient(String allowedHosts, WebClient client) {
        hosts = Arrays.stream(allowedHosts.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        hosts.add("api.chzzk.naver.com");
        hosts.add("play.rmcnmv.naver.com");
        this.client = client;
    }
    public SafeMediaClient(String allowedHosts) { this(allowedHosts, "HiPhant/1.0 (+https://hiphant.pachylover.com)"); }
    private static WebClient createClient(String userAgent) {
        HttpClient http = HttpClient.create().followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000).responseTimeout(Duration.ofSeconds(10))
                .resolver(new AddressResolverGroup<InetSocketAddress>() {
                    @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                        return new DefaultNameResolver(executor) {
                            @Override protected void doResolve(String name, Promise<InetAddress> promise) {
                                try { promise.setSuccess(resolvePublic(name).getFirst()); }
                                catch (Exception e) { promise.setFailure(e); }
                            }
                            @Override protected void doResolveAll(String name, Promise<List<InetAddress>> promise) {
                                try { promise.setSuccess(resolvePublic(name)); }
                                catch (Exception e) { promise.setFailure(e); }
                            }
                        }.asAddressResolver();
                    }
                });
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                .defaultHeader("User-Agent", userAgent)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_TEXT_BYTES)).build();
    }
    static List<InetAddress> resolvePublic(String name) throws Exception {
        List<InetAddress> addresses = List.of(InetAddress.getAllByName(name));
        for (InetAddress address : addresses) if (!isPublic(address)) throw denied();
        return addresses;
    }
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int first = Byte.toUnsignedInt(b[0]);
        if (b.length == 16) return (first & 0xe0) == 0x20
                && !(first == 0x20 && b[1] == 0x02)
                && !(first == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0); // global unicast only
        int second = Byte.toUnsignedInt(b[1]);
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19)) && !(first == 192 && second == 0);
    }
    public URI validate(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().contains(":") || uri.getHost().matches("[0-9.]+")
                    || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw denied();
            return uri;
        } catch (IllegalArgumentException e) { throw denied(); }
    }
    public record Text(URI uri, String body) {}
    public Text get(String raw) { return get(validate(raw), 0, null); }
    public Text getVideoMetadata(String videoId, NaverSession session) {
        if (videoId == null || !videoId.matches("[1-9][0-9]{0,19}")) throw denied();
        if (session != null && !session.isValid())
            throw new ClipSourceException(400, "INVALID_NAVER_SESSION", "네이버 세션 값 두 개를 올바르게 입력해 주세요.");
        return get(validate("https://api.chzzk.naver.com/service/v3/videos/" + videoId), 0, session);
    }
    private Text get(URI uri, int redirects, NaverSession session) {
        try {
            var request = client.get().uri(uri).header("Accept", "application/json,application/vnd.apple.mpegurl,text/plain");
            if (session != null && redirects == 0 && "api.chzzk.naver.com".equals(uri.getHost()))
                request.header("Cookie", "NID_AUT=" + session.nidAut() + "; NID_SES=" + session.nidSes());
            return request.exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status >= 300 && status < 400) {
                            String location = response.headers().asHttpHeaders().getFirst("Location");
                            return response.releaseBody().then(reactor.core.publisher.Mono.fromCallable(() -> {
                                if (redirects >= 3 || location == null) throw denied();
                                return get(validate(uri.resolve(location).toString()), redirects + 1, null);
                            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
                        }
                        if (status != 200) return response.releaseBody().then(reactor.core.publisher.Mono.error(upstream(status)));
                        return response.bodyToMono(String.class).switchIfEmpty(reactor.core.publisher.Mono.error(ClipSourceException.unsupported()))
                                .map(body -> new Text(uri, body));
                    }).block(Duration.ofSeconds(15));
        } catch (ClipSourceException e) { throw e; }
        catch (RuntimeException e) {
            // Decoder errors may be wrapped; expose only a stable code, never the cause text.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.springframework.core.io.buffer.DataBufferLimitException)
                    throw new ClipSourceException(502, "MEDIA_METADATA_TOO_LARGE", "영상 메타데이터가 지원 용량을 초과합니다.");
                if (cause == cause.getCause()) break;
            }
            // Never propagate client exception text: it can contain signed URLs.
            throw new ClipSourceException(502, "MEDIA_NETWORK_ERROR", "미디어 정보를 불러오지 못했습니다. 다시 시도해 주세요.");
        }
    }
    static ClipSourceException upstream(int status) {
        if (status == 403 || status == 401) return new ClipSourceException(403, "VIDEO_RESTRICTED", "다운로드할 수 없는 영상입니다.");
        if (status == 404) return new ClipSourceException(404, "VIDEO_NOT_FOUND", "영상 또는 미디어를 찾을 수 없습니다.");
        if (status == 429) return new ClipSourceException(429, "UPSTREAM_RATE_LIMITED", "요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        return new ClipSourceException(502, "UPSTREAM_ERROR", "영상 서버에서 오류가 발생했습니다.");
    }
    private static ClipSourceException denied() {
        return new ClipSourceException(422, "DOWNLOAD_HOST_NOT_ALLOWED", "검증되지 않은 미디어 서버입니다.");
    }
}
