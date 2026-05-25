package com.linechatbot.controller;

import com.linechatbot.model.dto.TagDTO;
import com.linechatbot.service.TagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 標籤管理 API（需 JWT 認證）
 */
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", tagService.getAll(),
                "message", "查詢成功"
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", tagService.getById(id),
                "message", "查詢成功"
        ));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody TagDTO dto) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", tagService.create(dto),
                "message", "新增成功"
        ));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                       @Valid @RequestBody TagDTO dto) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", tagService.update(id, dto),
                "message", "修改成功"
        ));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        tagService.delete(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "刪除成功"
        ));
    }
}
