package com.linechatbot.controller;

import com.linechatbot.model.dto.QAPairDTO;
import com.linechatbot.service.QAService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 問答管理 API Controller（需 JWT 認證）
 */
@RestController
@RequestMapping("/api/qa")
@RequiredArgsConstructor
public class QAController {

    private final QAService qaService;

    /**
     * 分頁查詢所有 QA
     * GET /api/qa?page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<QAPairDTO> page = qaService.getAllQA(pageable);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", page,
                "message", "查詢成功"
        ));
    }

    /**
     * 新增 QA
     * POST /api/qa
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody QAPairDTO dto) {
        QAPairDTO created = qaService.createQA(dto);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", created,
                "message", "新增成功"
        ));
    }

    /**
     * 修改 QA
     * PUT /api/qa/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @Valid @RequestBody QAPairDTO dto) {
        QAPairDTO updated = qaService.updateQA(id, dto);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", updated,
                "message", "修改成功"
        ));
    }

    /**
     * 刪除 QA
     * DELETE /api/qa/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        qaService.deleteQA(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "刪除成功"
        ));
    }

    /**
     * 切換 QA 啟用/停用
     * PATCH /api/qa/{id}/toggle
     */
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable Long id) {
        QAPairDTO toggled = qaService.toggleQA(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", toggled,
                "message", toggled.getIsActive() ? "已啟用" : "已停用"
        ));
    }
}
