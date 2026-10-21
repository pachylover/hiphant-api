package com.pachy.highlight.init;

import com.pachy.highlight.dto.ChzzkVideoResponse;
import com.pachy.highlight.repository.HighlightRepository;
import com.pachy.highlight.service.VideoInfoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 배포 직후, 영상 제목/채널 정보가 비어 있는 하이라이트를 치지직 API 로 채운다.
 *
 * <p>video_title(V4), channel_id/channel_name(V5) 컬럼은 기존 행에 NULL 로 추가되므로
 * 한 번은 백필이 필요하다. 치지직 API 를 영상 수만큼 호출하므로 별도 스레드에서 돌리고
 * 호출 사이에 간격을 둔다. 실패한 영상은 NULL 로 남아 다음 기동 때 다시 시도된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoMetaBackfillRunner implements ApplicationRunner {

    private final HighlightRepository highlightRepository;
    private final VideoInfoService videoInfoService;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor applicationTaskExecutor;

    @Value("${highlight.title-backfill.enabled:true}")
    private boolean enabled;

    /** 치지직 API 호출 간 간격(ms). 레이트 리밋을 피하기 위한 최소 간격. */
    @Value("${highlight.title-backfill.delay-ms:300}")
    private long delayMs;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        // 기동을 막지 않도록 백그라운드로 넘긴다
        applicationTaskExecutor.execute(this::backfill);
    }

    void backfill() {
        List<String> videoIds;
        try {
            videoIds = highlightRepository.findVideoIdsMissingMeta();
        } catch (Exception e) {
            log.warn("영상 정보 백필 대상 조회 실패", e);
            return;
        }

        if (videoIds.isEmpty()) return;
        log.info("영상 정보 백필 시작 - 대상 {}건", videoIds.size());

        int filled = 0;
        for (String videoId : videoIds) {
            try {
                ChzzkVideoResponse info = videoInfoService.get(videoId);
                if (info == null) {
                    log.info("영상 정보를 가져오지 못했습니다 (삭제/비공개 가능) - videoId: {}", videoId);
                } else {
                    String title = info.getVideoTitle() != null && !info.getVideoTitle().isBlank()
                            ? info.getVideoTitle() : null;
                    ChzzkVideoResponse.Channel channel = info.getChannel();
                    String channelId = channel != null ? channel.getChannelId() : null;
                    String channelName = channel != null ? channel.getChannelName() : null;

                    if (title != null || channelId != null) {
                        transactionTemplate.executeWithoutResult(status ->
                                highlightRepository.updateVideoMeta(videoId, title, channelId, channelName,
                                        info.getThumbnailImageUrl(), info.getPublishDate()));
                        filled++;
                    }
                }
            } catch (Exception e) {
                log.warn("영상 정보 백필 실패 - videoId: {}", videoId, e);
            }

            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("영상 정보 백필 중단됨 - {}건 처리", filled);
                return;
            }
        }

        log.info("영상 정보 백필 완료 - {}/{}건 채움", filled, videoIds.size());
    }
}
