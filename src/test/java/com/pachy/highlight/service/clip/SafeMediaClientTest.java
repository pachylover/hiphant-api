package com.pachy.highlight.service.clip;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.assertj.core.api.Assertions.*;

class SafeMediaClientTest {
    @Test void requiresExactHttpsHostAndNoCredentialsOrPorts() {
        SafeMediaClient client = new SafeMediaClient("media.example");
        assertThat(client.validate("https://media.example/video.m3u8").getHost()).isEqualTo("media.example");
        for (String uri : new String[]{"http://media.example/a", "https://media.example.evil/a", "https://evil.media.example/a", "https://user:password@media.example/a", "https://media.example:8080/a", "https://127.0.0.1/a", "file:///etc/passwd"})
            assertThatThrownBy(() -> client.validate(uri)).isInstanceOf(ClipSourceException.class);
    }
    @Test void rejectsPrivateAndSpecialAddressRanges() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "198.18.0.1", "::1", "fc00::1", "fe80::1"})
            assertThat(SafeMediaClient.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        assertThat(SafeMediaClient.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
    }
    @Test void sanitizesUpstreamFailures() {
        assertThat(SafeMediaClient.upstream(403).code()).isEqualTo("VIDEO_RESTRICTED");
        assertThat(SafeMediaClient.upstream(404).code()).isEqualTo("VIDEO_NOT_FOUND");
        assertThat(SafeMediaClient.upstream(429).status()).isEqualTo(429);
        assertThat(SafeMediaClient.upstream(500).status()).isEqualTo(502);
    }

    @Test void revalidatesRedirectTargetsBeforeRequesting() {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        var web = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.FOUND).header("Location", "https://127.0.0.1/private").build());
        }).build();
        var client = new SafeMediaClient("media.example", web);
        assertThatThrownBy(() -> client.get("https://media.example/master.m3u8"))
                .extracting("code").isEqualTo("DOWNLOAD_HOST_NOT_ALLOWED");
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void followsOnlyBoundedValidatedRedirects() {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        var web = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.FOUND).header("Location", "/loop").build());
        }).build();
        assertThatThrownBy(() -> new SafeMediaClient("media.example", web).get("https://media.example/master.m3u8"))
                .isInstanceOf(ClipSourceException.class);
        assertThat(requests.get()).isEqualTo(4);
    }
    @Test void doesNotExposeSignedUrlsFromTransportErrors() {
        var web = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request ->
                reactor.core.publisher.Mono.error(new IllegalStateException("https://media.example/?secret=do-not-expose"))).build();
        assertThatThrownBy(() -> new SafeMediaClient("media.example", web).get("https://media.example/master.m3u8"))
                .isInstanceOf(ClipSourceException.class).hasMessageNotContaining("secret").hasMessageNotContaining("media.example");
    }
    @Test void limitsDecodedResponseBytes() {
        var strategies = org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
        var web = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request ->
                reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                        .create(org.springframework.http.HttpStatus.OK, strategies)
                        .body(reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                                .wrap(new byte[2 * 1024 * 1024 + 1]))).build())).build();
        assertThatThrownBy(() -> new SafeMediaClient("media.example", web).get("https://media.example/master.m3u8"))
                .extracting("code").isEqualTo("MEDIA_NETWORK_ERROR");
    }
    @Test void sendsSessionOnlyOnInitialMetadataRequest() {
        var cookies = new java.util.ArrayList<String>();
        var web = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
            cookies.add(request.headers().getFirst("Cookie"));
            if (cookies.size() == 1) return reactor.core.publisher.Mono.just(
                    org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.FOUND)
                            .header("Location", "https://media.example/metadata").build());
            return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.OK).body("{}").build());
        }).build();
        var session = new com.pachy.highlight.dto.clip.ClipSessionDtos.NaverSession("fixture-aut", "fixture-ses");
        new SafeMediaClient("media.example", web).getVideoMetadata("123", session);
        assertThat(cookies).containsExactly("NID_AUT=fixture-aut; NID_SES=fixture-ses", null);
        assertThat(session.toString()).doesNotContain("fixture");
        assertThat(new com.pachy.highlight.dto.clip.ClipSessionDtos.CreateRequest(0, 10, session).toString()).doesNotContain("fixture");
    }
    @Test void rejectsInvalidSessionBeforeNetwork() {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var web = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return reactor.core.publisher.Mono.empty();
        }).build();
        var client = new SafeMediaClient("media.example", web);
        for (String value : new String[]{"", "value;other=cookie", "value\r\nInjected: value", "x".repeat(4097)}) {
            var session = new com.pachy.highlight.dto.clip.ClipSessionDtos.NaverSession(value, "fixture-ses");
            assertThatThrownBy(() -> client.getVideoMetadata("123", session))
                    .extracting("code").isEqualTo("INVALID_NAVER_SESSION");
        }
        assertThat(requests.get()).isZero();
    }
}
