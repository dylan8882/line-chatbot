package com.linechatbot.controller;

import com.linechatbot.model.dto.BroadcastCreateRequest;
import com.linechatbot.service.BroadcastDispatchService;
import com.linechatbot.service.BroadcastService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 推播任務管理 API（需 JWT 認證）
 */
@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {

    private final BroadcastService broadcastService;
    private final BroadcastDispatchService dispatchService;

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
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body) {
        String lineUserId = body.get("lineUserId");
        if (lineUserId == null || lineUserId.isBlank()) {
            throw new IllegalArgumentException("lineUserId 不可為空");
        }
        String content = broadcastService.getDetail(id).getMessageContent();
        String requestId = dispatchService.testSend(content, lineUserId);
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
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.cancel(id),
                "message", "任務已取消"
        ));
    }

    /**
     * 任務進度（一次性查詢，Phase 4 會再加 SSE）
     */
    @GetMapping("/{id}/progress")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", broadcastService.getDetail(id),
                "message", "查詢成功"
        ));
    }
}
