package com.linechatbot.controller;

import com.linechatbot.model.dto.MessageTemplateDTO;
import com.linechatbot.service.MessageTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 訊息模板管理 API（需 JWT 認證）
 */
@RestController
@RequestMapping("/api/message-templates")
@RequiredArgsConstructor
public class MessageTemplateController {

    private final MessageTemplateService templateService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", templateService.getAll(),
                "message", "查詢成功"
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", templateService.getById(id),
                "message", "查詢成功"
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody MessageTemplateDTO dto) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", templateService.create(dto),
                "message", "新增成功"
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                       @Valid @RequestBody MessageTemplateDTO dto) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", templateService.update(id, dto),
                "message", "修改成功"
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "刪除成功"
        ));
    }
}
