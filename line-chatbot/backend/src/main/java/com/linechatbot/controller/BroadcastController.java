package com.linechatbot.controller;

import com.linechatbot.model.dto.AbTestCreateRequest;
import com.linechatbot.model.dto.BroadcastCreateRequest;
import com.linechatbot.service.BroadcastProgressService;
import com.linechatbot.service.BroadcastService;
import com.linechatbot.service.BroadcastStatisticsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 推播任務管理 API（需 JWT 認證）
 */
@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {

    private final BroadcastService broadcastService;
    private final BroadcastStatisticsService statisticsService;
    private final BroadcastProgressService progressService;

    /**
     * 任務列表
     * GET /api/broadcasts?status=RUNNING&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.list(status, pageable),
                "message", "查詢成功"
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.getDetail(id),
                "message", "查詢成功"
        ));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MARKETER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody BroadcastCreateRequest req) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.create(req),
                "message", "任務已建立"
        ));
    }

    /**
     * 預估收件人數（不真的建立任務）
     */
    @PostMapping("/estimate")
    public ResponseEntity<Map<String, Object>> estimate(@Valid @RequestBody BroadcastCreateRequest req) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.estimate(req),
                "message", "預估完成"
        ));
    }

    /**
     * 測試發送：將任務內容用 pushMessage 送給單一 lineUserId，不影響任務統計
     * POST /api/broadcasts/{id}/test  Body: { "lineUserId": "U..." }
     */
    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('MARKETER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body) {
        String lineUserId = body.get("lineUserId");
        if (lineUserId == null || lineUserId.isBlank()) {
            throw new IllegalArgumentException("lineUserId 不可為空");
        }
        String requestId = broadcastService.testSend(id, lineUserId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("requestId", requestId),
                "message", "已發送測試訊息"
        ));
    }

    /**
     * 提交任務執行
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.submit(id),
                "message", "任務已提交執行"
        ));
    }

    /**
     * 取消任務
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.cancel(id),
                "message", "任務已取消"
        ));
    }

    /**
     * 任務進度一次性查詢
     */
    @GetMapping("/{id}/progress")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.getDetail(id),
                "message", "查詢成功"
        ));
    }

    /**
     * 任務進度 SSE 即時推送
     * GET /api/broadcasts/{id}/progress/stream
     *
     * 注意：SSE 端點目前以 query string `?token=...` 帶 JWT，
     * 因為瀏覽器的 EventSource 不支援自訂 Header。Phase 4 暫時透過 SecurityConfig 放行此端點，
     * 若要嚴格認證需另外處理（例如 cookie token 或 reverse-proxy 注入 header）。
     */
    @GetMapping(value = "/{id}/progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progressStream(@PathVariable Long id) {
        return progressService.subscribe(id);
    }

    /**
     * 成效統計：成功率、錯誤分布、發送速率等
     */
    @GetMapping("/{id}/statistics")
    public ResponseEntity<Map<String, Object>> statistics(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", statisticsService.getStatistics(id),
                "message", "查詢成功"
        ));
    }

    /**
     * 失敗清單：列出 FAILED / RETRYING 的 chunk 詳細資訊
     */
    @GetMapping("/{id}/failures")
    public ResponseEntity<Map<String, Object>> failures(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", statisticsService.getFailures(id),
                "message", "查詢成功"
        ));
    }

    /**
     * Phase 7：點擊統計（CTR + 各 link 點擊數）
     */
    @GetMapping("/{id}/clicks")
    public ResponseEntity<Map<String, Object>> clicks(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", statisticsService.getClickStatistics(id),
                "message", "查詢成功"
        ));
    }

    /**
     * 建立 A/B 測試任務：一次切出 N 個 variant 任務，可分別 submit
     */
    @PostMapping("/ab-test")
    @PreAuthorize("hasAnyRole('MARKETER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> createAbTest(@Valid @RequestBody AbTestCreateRequest req) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.createAbTest(req),
                "message", "A/B 測試已建立"
        ));
    }

    /**
     * 取得 A/B 測試各 variant 的成效比較
     */
    @GetMapping("/ab-test/{abTestId}")
    public ResponseEntity<Map<String, Object>> getAbTestComparison(@PathVariable String abTestId) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.getAbTestComparison(abTestId),
                "message", "查詢成功"
        ));
    }
}
