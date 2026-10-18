package com.pachy.highlight.controller;

import com.pachy.highlight.dto.clip.ClipSessionDtos;
import com.pachy.highlight.service.clip.ClipSourceException;
import com.pachy.highlight.service.clip.ClipSourceService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ClipSessionController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClipSessionController.class);
    private final ClipSourceService service;
    public ClipSessionController(ClipSourceService service) { this.service = service; }
    @PostMapping("/videos/{videoId}/clip-sessions")
    public ResponseEntity<ClipSessionDtos.Session> create(@PathVariable String videoId, @Valid @RequestBody ClipSessionDtos.CreateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.create(videoId, request));
    }
    @GetMapping("/clip-sessions/{sessionId}/renditions/{renditionId}/manifest")
    public ResponseEntity<ClipSessionDtos.Manifest> manifest(@PathVariable String sessionId, @PathVariable String renditionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.manifest(sessionId, renditionId));
    }
    @ExceptionHandler(ClipSourceException.class)
    public ResponseEntity<ClipSessionDtos.Error> sourceError(ClipSourceException error) {
        String requestId = UUID.randomUUID().toString();
        log.warn("Clip session error requestId={} code={}", requestId, error.code());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(new ClipSessionDtos.Error(
                error.code(), error.getMessage(), requestId, error.status() == 410 || error.status() == 429 || error.status() >= 500));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ClipSessionDtos.Error> invalidRequest(Exception ignored) {
        return sourceError(new ClipSourceException(400, "INVALID_RANGE", "올바른 시작·종료 시각을 입력해 주세요."));
    }
}
