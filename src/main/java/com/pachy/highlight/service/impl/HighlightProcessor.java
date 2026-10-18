package com.pachy.highlight.service.impl;

import com.pachy.highlight.client.ChzzkClient;
import com.pachy.highlight.dto.ChzzkVideoResponse;
import com.pachy.highlight.dto.progress.ProgressEvent;
import com.pachy.highlight.dto.progress.ProgressEvent.Phase;
import com.pachy.highlight.entity.Chat;
import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.ChatBatchInsertRepository;
import com.pachy.highlight.repository.ChatRepository;
import com.pachy.highlight.repository.HighlightRepository;
import com.pachy.highlight.service.BlockedUserService;
import com.pachy.highlight.service.VideoInfoService;
import com.pachy.highlight.service.progress.ProgressService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 채팅 수집 → 저장 → 하이라이트 추출을 백그라운드에서 수행한다.
 *
 * <p>{@code @Async} 는 프록시를 통해서만 동작하므로 호출자({@link HighlightServiceImpl})와
 * 다른 빈으로 분리해 두었다. 각 단계는 {@link ProgressService} 로 진행 상황을 발행해
 * 웹에서 SSE 로 확인할 수 있다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HighlightProcessor {

    private static final int CHUNK = 500;

    private final HighlightRepository highlightRepository;
    private final ChatRepository chatRepository;
    private final ChatBatchInsertRepository chatBatchInsertRepository;
    private final ChzzkClient chzzkClient;
    private final VideoInfoService videoInfoService;
    private final ProgressService progressService;
    private final BlockedUserService blockedUserService;

    /** 채팅 수집 + 하이라이트 생성 (전체 파이프라인). */
    @Async
    public void process(String videoId) {
        try {
            VideoMeta meta = resolveMeta(videoId);

            int collected = collectChats(videoId, 0, 70);
            if (collected == 0) {
                // 치지직이 채팅을 주지 않아도 이전에 수집해 둔 채팅이 남아 있으면 그것으로 분석한다
                long stored = chatRepository.countByVideoId(videoId);
                if (stored == 0) {
                    progressService.publish(ProgressEvent.builder()
                            .videoId(videoId).phase(Phase.ERROR)
                            .message("치지직에서 채팅을 불러오지 못했습니다. 다시보기가 비공개이거나 채팅 보관 기간이 지났을 수 있습니다.")
                            .percent(100).chatCount(0).at(java.time.Instant.now()).build());
                    return;
                }
                collected = (int) Math.min(Integer.MAX_VALUE, stored);
            }

            int created = analyze(videoId, meta);

            progressService.publish(ProgressEvent.builder()
                    .videoId(videoId).phase(Phase.DONE)
                    .message("하이라이트 생성이 완료되었습니다")
                    .percent(100).chatCount(collected).highlightCount(created)
                    .at(java.time.Instant.now()).build());
        } catch (Exception e) {
            log.error("하이라이트 생성 중 예외 발생 - videoId: {}", videoId, e);
            progressService.publish(videoId, Phase.ERROR, "하이라이트 생성 중 오류가 발생했습니다", 100);
        } finally {
            progressService.finish(videoId);
        }
    }

    /** 채팅만 다시 수집한다 (보관 기간이 지나 채팅이 사라진 영상 복구용). */
    @Async
    public void reloadChats(String videoId) {
        try {
            int collected = collectChats(videoId, 0, 95);
            if (collected == 0) {
                progressService.publish(ProgressEvent.builder()
                        .videoId(videoId).phase(Phase.ERROR)
                        .message("채팅을 불러올 수 없습니다. 치지직에서 해당 다시보기의 채팅을 더 이상 제공하지 않습니다.")
                        .percent(100).chatCount(0).at(java.time.Instant.now()).build());
                return;
            }

            progressService.publish(ProgressEvent.builder()
                    .videoId(videoId).phase(Phase.DONE)
                    .message(String.format("채팅 %,d개를 다시 불러왔습니다", collected))
                    .percent(100).chatCount(collected).at(java.time.Instant.now()).build());
        } catch (Exception e) {
            log.error("채팅 재수집 중 예외 발생 - videoId: {}", videoId, e);
            progressService.publish(videoId, Phase.ERROR, "채팅을 다시 불러오는 중 오류가 발생했습니다", 100);
        } finally {
            progressService.finish(videoId);
        }
    }

    /**
     * 치지직에서 채팅을 수집해 DB 에 저장하고, 수집 건수를 반환한다.
     * 진행률은 [fromPercent, toPercent] 구간에 매핑된다.
     */
    private int collectChats(String videoId, int fromPercent, int toPercent) {
        progressService.publish(videoId, Phase.FETCH_CHATS, "치지직에서 채팅을 불러오는 중입니다", fromPercent);

        int fetchEnd = fromPercent + (int) ((toPercent - fromPercent) * 0.7);
        List<Chat> chats = chzzkClient.fetchAllChats(videoId, count ->
                progressService.publish(ProgressEvent.builder()
                        .videoId(videoId).phase(Phase.FETCH_CHATS)
                        .message(String.format("채팅 %,d개 수집 중", count))
                        // 전체 분량을 미리 알 수 없어 로그 곡선으로 근사한다 (10만건 부근에서 구간 끝에 수렴)
                        .percent(scaleUnknownTotal(count, fromPercent, fetchEnd))
                        .chatCount(count).at(java.time.Instant.now()).build()));

        chats = excludeBlockedUsers(videoId, chats);

        log.info("채팅 수집 완료 - videoId: {}, 채팅 수: {}", videoId, chats.size());
        if (chats.isEmpty()) return 0;

        progressService.publish(ProgressEvent.builder()
                .videoId(videoId).phase(Phase.SAVE_CHATS)
                .message(String.format("채팅 %,d개 저장 중", chats.size()))
                .percent(fetchEnd).chatCount(chats.size()).at(java.time.Instant.now()).build());

        for (int i = 0; i < chats.size(); i += CHUNK) {
            int toIndex = Math.min(i + CHUNK, chats.size());
            List<Chat> chunk = chats.subList(i, toIndex);
            long t0 = System.currentTimeMillis();

            int inserted = chatBatchInsertRepository.insertBatch(chunk);

            log.info("Batch inserted chunk [{} - {}) requested={} inserted={} in {} ms",
                    i, toIndex, chunk.size(), inserted, System.currentTimeMillis() - t0);

            int percent = fetchEnd + (toPercent - fetchEnd) * toIndex / chats.size();
            progressService.publish(ProgressEvent.builder()
                    .videoId(videoId).phase(Phase.SAVE_CHATS)
                    .message(String.format("채팅 저장 중 (%,d / %,d)", toIndex, chats.size()))
                    .percent(percent).chatCount(chats.size()).at(java.time.Instant.now()).build());
        }

        return chats.size();
    }

    /** 저장된 채팅에서 하이라이트 구간을 뽑아 저장하고, 생성된 하이라이트 수를 반환한다. */
    private int analyze(String videoId, VideoMeta meta) {
        progressService.publish(videoId, Phase.ANALYZE, "채팅이 많았던 구간을 분석하는 중입니다", 75);
        int created = saveHighlights(videoId, meta,
                chatRepository.findPeakMinute(videoId), "NORMAL", "%d개의 채팅이 발생한 구간");

        progressService.publish(videoId, Phase.ANALYZE, "ㅋㅋㅋ 구간을 분석하는 중입니다", 85);
        created += saveHighlights(videoId, meta,
                chatRepository.findKeywordPeakMinute(videoId, "ㅋㅋㅋ"), "LAUGH", "%d개의 ㅋㅋㅋ 채팅이 발생한 구간");

        progressService.publish(videoId, Phase.ANALYZE, "갈고리 구간을 분석하는 중입니다", 95);
        created += saveHighlights(videoId, meta,
                chatRepository.findKeywordPeakMinute(videoId, "?"), "QUESTION", "%d개의 ? 채팅이 발생한 구간");

        return created;
    }

    /** 하이라이트에 함께 저장할 영상/채널 정보. 조회 실패 시 모든 값이 null 이다. */
    record VideoMeta(String title, String channelId, String channelName, Integer durationSeconds) {
        static final VideoMeta EMPTY = new VideoMeta(null, null, null, null);
    }

    private VideoMeta resolveMeta(String videoId) {
        try {
            ChzzkVideoResponse info = videoInfoService.get(videoId);
            if (info == null) return VideoMeta.EMPTY;

            String title = info.getVideoTitle() != null && !info.getVideoTitle().isBlank()
                    ? info.getVideoTitle() : null;
            ChzzkVideoResponse.Channel channel = info.getChannel();
            return new VideoMeta(title,
                    channel != null ? channel.getChannelId() : null,
                    channel != null ? channel.getChannelName() : null,
                    info.getDuration());
        } catch (Exception e) {
            log.warn("영상 정보 조회 실패 - videoId: {}", videoId, e);
            return VideoMeta.EMPTY;
        }
    }

    /** 수집을 원하지 않는다고 요청한 회원(블랙리스트)의 채팅을 저장 전에 걸러낸다. */
    private List<Chat> excludeBlockedUsers(String videoId, List<Chat> chats) {
        Set<String> blocked = blockedUserService.blockedUids();
        if (blocked.isEmpty()) return chats;

        List<Chat> kept = chats.stream()
                .filter(c -> c.getUserId() == null || !blocked.contains(c.getUserId()))
                .toList();

        int removed = chats.size() - kept.size();
        if (removed > 0) log.info("비수집 회원 채팅 제외 - videoId: {}, {}건", videoId, removed);
        return kept;
    }

    /**
     * 총량을 모르는 진행률을 0~1 로 근사한다. 수집량이 늘수록 완만하게 상한에 수렴한다.
     */
    private int scaleUnknownTotal(int count, int from, int to) {
        double ratio = Math.min(1.0, Math.log10(count + 1) / 5.0); // 10만건에서 1.0
        return from + (int) Math.round((to - from) * ratio);
    }

    private int saveHighlights(String videoId, VideoMeta meta, List<Object[]> peakRows,
                               String highlightType, String titleFormat) {
        int created = 0;
        for (Object[] row : peakRows) {
            if (row == null || row.length < 3 || row[1] == null) continue;

            Long minuteMs = toOffsetMillis(row[1]);
            if (minuteMs == null) continue;

            Object cntObj = row[2];
            Number cnt = cntObj instanceof Number ? (Number) cntObj : null;

            Highlight h = new Highlight();
            h.setVideoId(videoId);
            h.setVideoTitle(meta.title());
            h.setChannelId(meta.channelId());
            h.setChannelName(meta.channelName());
            h.setMinute(minuteMs);
            h.setChatCount(cnt != null ? cnt.intValue() : 0);
            h.setTitle(cnt != null ? String.format(titleFormat, cnt.intValue()) : "하이라이트");
            ClipBounds clipBounds = calculateClipBounds(minuteMs, meta.durationSeconds());
            h.setStartTs(clipBounds.startMs());
            h.setEndTs(clipBounds.endMs());
            h.setStatus("DONE");
            h.setHighlightType(highlightType);
            highlightRepository.save(h);
            created++;
        }
        log.info("하이라이트 생성 완료 - videoId: {}, type: {}, count: {}", videoId, highlightType, created);
        return created;
    }

    /** minute 컬럼은 영상 기준 상대 밀리초다. */
    private Long toOffsetMillis(Object minuteObj) {
        if (minuteObj instanceof Number n) return n.longValue();
        if (minuteObj instanceof Object[] inner && inner.length > 0 && inner[0] instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    record ClipBounds(long startMs, long endMs) {}

    static ClipBounds calculateClipBounds(long minuteMs, Integer durationSeconds) {
        long startMs = Math.max(0L, minuteMs - Duration.ofSeconds(30).toMillis());
        long endMs = Math.max(startMs + 1L, minuteMs + Duration.ofSeconds(90).toMillis());

        if (durationSeconds != null && durationSeconds > 0) {
            long durationMs = Duration.ofSeconds(durationSeconds.longValue()).toMillis();
            startMs = Math.min(startMs, Math.max(0L, durationMs - 1L));
            endMs = Math.min(Math.max(startMs + 1L, endMs), durationMs);
        }

        return new ClipBounds(startMs, endMs);
    }
}
