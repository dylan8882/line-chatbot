package com.linechatbot.controller;

import com.linechatbot.model.dto.BulkTagRequest;
import com.linechatbot.service.LineUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * LINE 用戶管理 API（需 JWT 認證）
 */
@RestController
@RequestMapping("/api/line-users")
@RequiredArgsConstructor
public class LineUserController {

    private final LineUserService lineUserService;

    /**
     * 分頁查詢用戶；支援暱稱關鍵字、狀態、標籤篩選。
     * GET /api/line-users?keyword=xxx&status=FOLLOWED&tagIds=1,2&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) List<Long> tagIds,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", lineUserService.search(keyword, status, tagIds, pageable),
                "message", "查詢成功"
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", lineUserService.getById(id),
                "message", "查詢成功"
        ));
    }

    /**
     * 指派標籤到單一用戶（覆寫式）。
     * POST /api/line-users/{id}/tags  Body: { "tagIds": [1, 2] }
     */
    @PostMapping("/{id}/tags")
    @PreAuthorize("hasAnyRole('CS_AGENT', 'MARKETER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> assignTags(@PathVariable Long id,
                                                           @RequestBody Map<String, List<Long>> body) {
        List<Long> tagIds = body.getOrDefault("tagIds", List.of());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", lineUserService.assignTags(id, tagIds),
                "message", "標籤已更新"
        ));
    }

    /**
     * 批量貼 / 移除標籤。
     * POST /api/line-users/bulk-tag  Body: { "userIds": [...], "tagIds": [...], "action": "ADD|REMOVE" }
     */
    @PostMapping("/bulk-tag")
    @PreAuthorize("hasAnyRole('CS_AGENT', 'MARKETER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> bulkTag(@Valid @RequestBody BulkTagRequest req) {
        int affected = lineUserService.bulkTag(req);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", Map.of("affected", affected),
                "message", "批量操作完成"
        ));
    }
}
