package com.pachy.highlight.controller;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pachy.highlight.dto.HighlightResponse;
import com.pachy.highlight.dto.admin.RecentHighlightItem;
import com.pachy.highlight.dto.response.Response;
import com.pachy.highlight.dto.response.ResponseList;
import com.pachy.highlight.entity.Highlight;
import com.pachy.highlight.repository.HighlightRepository;
import com.pachy.highlight.service.HighlightService;
import com.pachy.highlight.service.progress.ProgressService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/highlights")
@RequiredArgsConstructor
public class HighlightController {

    private final HighlightService highlightService;
    private final HighlightRepository highlightRepository;
    private final ProgressService progressService;
    private final com.pachy.highlight.service.impl.ChatHighlightService chatHighlightService;
    private final com.pachy.highlight.service.impl.KeywordHighlightService keywordHighlightService;

    public record KeywordRequest(String keyword) {}

    @PostMapping("/{videoId}/keywords")
    public ResponseEntity<List<HighlightResponse>> createFromKeyword(
            @PathVariable("videoId") String videoId, @RequestBody KeywordRequest request) {
        return ResponseEntity.ok(keywordHighlightService.create(videoId, request.keyword()));
    }

    // 공개 홈용: 최근 생성된 하이라이트 (영상별 최신 1건, 기본 6건)
    @GetMapping("/recent")
    public ResponseEntity<ResponseList<RecentHighlightItem>> recent(
            @RequestParam(value = "limit", defaultValue = "6") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<Highlight> highlights = highlightRepository.findRecentDistinctVideoHighlights(safeLimit);
        List<RecentHighlightItem> items = highlights.stream().map(RecentHighlightItem::from).toList();

        ResponseList<RecentHighlightItem> response = new ResponseList<>(HttpStatus.OK);
        response.setCount(items.size());
        response.setList(items);
        return ResponseEntity.ok(response);
    }

    /**
     * 사이트맵용: 하이라이트가 생성된 영상 목록 (영상별 1건, 최근 순).
     * 홈의 /recent 와 같은 데이터지만 검색엔진 색인을 위해 더 많은 건수를 허용한다.
     */
    @GetMapping("/index")
    public ResponseEntity<ResponseList<RecentHighlightItem>> index(
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 2000);
        List<Highlight> highlights = highlightRepository.findRecentDistinctVideoHighlights(safeLimit);
        List<RecentHighlightItem> items = highlights.stream().map(RecentHighlightItem::from).toList();

        ResponseList<RecentHighlightItem> response = new ResponseList<>(HttpStatus.OK);
        response.setCount(items.size());
        response.setList(items);
        return ResponseEntity.ok(response);
    }

    /**
     * 같은 스트리머의 다른 하이라이트 페이지 목록.
     * 하이라이트 화면 하단에서 "이 스트리머의 다른 하이라이트"로 노출된다.
     */
    @GetMapping("/channel/{channelId}")
    public ResponseEntity<ResponseList<RecentHighlightItem>> byChannel(
            @PathVariable("channelId") String channelId,
            @RequestParam(value = "exclude", defaultValue = "") String excludeVideoId,
            @RequestParam(value = "limit", defaultValue = "6") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<Highlight> highlights = highlightRepository.findChannelHighlights(channelId, excludeVideoId, safeLimit);
        List<RecentHighlightItem> items = highlights.stream().map(RecentHighlightItem::from).toList();

        ResponseList<RecentHighlightItem> response = new ResponseList<>(HttpStatus.OK);
        response.setCount(items.size());
        response.setList(items);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseList<HighlightResponse>> get(@PathVariable("id") String id) {
        ResponseList<HighlightResponse> response = new ResponseList<>();
        List<HighlightResponse> result = highlightService.getHighlight(id);

        HttpStatus status;
        if (result.isEmpty()) {
            // 결과는 없지만 생성이 진행 중이면 202 로 알려 클라이언트가 진행 화면을 띄우게 한다
            status = progressService.isRunning(id) ? HttpStatus.ACCEPTED : HttpStatus.NOT_FOUND;
        } else {
            status = progressService.isRunning(id) ? HttpStatus.ACCEPTED : HttpStatus.OK;
            response.setCount(result.size());
            response.setList(result);
        }
        response.setResultCode(status.value());
        response.setResultMsg(status.getReasonPhrase());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{videoId}/chats/{chatId}")
    public ResponseEntity<HighlightResponse> createFromChat(
            @PathVariable("videoId") String videoId, @PathVariable("chatId") Long chatId) {
        return ResponseEntity.ok(chatHighlightService.create(videoId, chatId));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> handleChatHighlightError(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(java.util.Map.of("message", ex.getReason() != null ? ex.getReason() : "Invalid request"));
    }

    @PostMapping("/{id}")
    public ResponseEntity<Response> create(@PathVariable("id") String videoId,
                                           @RequestParam(value = "type", required = false) String highlightType) {
        Response response = new Response();

        // 이미 하이라이트가 존재하는 경우 400 응답
        if (highlightService.getHighlight(videoId).stream()
                .anyMatch(highlight -> !"CHAT".equals(highlight.getHighlightType())
                        && !"KEYWORD".equals(highlight.getHighlightType()))) {
            response.setResultCode(HttpStatus.BAD_REQUEST.value());
            response.setResultMsg("Highlight already exists for this video");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        if (!highlightService.createHighlight(videoId, highlightType)) {
            response.setResultCode(HttpStatus.CONFLICT.value());
            response.setResultMsg("Highlight creation already in progress for this video");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        response.setResultCode(HttpStatus.CREATED.value());
        response.setResultMsg("Highlight creation started");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 진행 상황 스트리밍(SSE). 하이라이트 생성 / 채팅 재수집의 현재 단계를 실시간으로 내려준다.
     * 연결 시점에 진행 중인 작업이 있으면 마지막 상태를 즉시 한 번 전송한다.
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") String videoId) {
        return progressService.subscribe(videoId);
    }
}
