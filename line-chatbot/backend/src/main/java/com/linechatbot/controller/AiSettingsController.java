package com.linechatbot.controller;

import com.linechatbot.model.dto.AiConfigDTO;
import com.linechatbot.service.AiSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 串接設定 API（需 ADMIN 權限）。
 *
 * <pre>
 * GET /api/ai-settings   取得目前設定（apiKey 遮罩）
 * PUT /api/ai-settings   儲存設定（欄位 null=不動、""=清除）
 * </pre>
 */
@RestController
@RequestMapping("/api/ai-settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AiSettingsController {

    private final AiSettingsService aiSettingsService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", aiSettingsService.getConfig(),
                "message", "查詢成功"
        ));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody AiConfigDTO dto) {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", aiSettingsService.saveConfig(dto),
                "message", "設定已儲存"
        ));
    }
}
