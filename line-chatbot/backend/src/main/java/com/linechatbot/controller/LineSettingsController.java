package com.linechatbot.controller;

import com.linechatbot.model.dto.LineChannelConfigDTO;
import com.linechatbot.service.LineSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * LINE Messaging API 頻道設定 Controller（需 JWT 認證）。
 *
 * <pre>
 * GET  /api/line-settings          取得目前設定（敏感欄位遮罩）
 * PUT  /api/line-settings          儲存設定
 * POST /api/line-settings/verify   驗證 Access Token 是否有效
 * </pre>
 */
@RestController
@RequestMapping("/api/line-settings")
@RequiredArgsConstructor
public class LineSettingsController {

    private final LineSettingsService lineSettingsService;

    /**
     * 取得目前 LINE 頻道設定。
     * Channel Secret 與 Access Token 回傳遮罩值（****xxxx）。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        LineChannelConfigDTO dto = lineSettingsService.getConfig();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", dto,
                "message", "查詢成功"
        ));
    }

    /**
     * 儲存 LINE 頻道設定。
     * channelSecret / channelAccessToken 傳入 null 代表不更新。
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody LineChannelConfigDTO dto) {
        LineChannelConfigDTO saved = lineSettingsService.saveConfig(dto);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", saved,
                "message", "設定已儲存"
        ));
    }

    /**
     * 驗證 Channel Access Token 是否能成功呼叫 LINE Bot API。
     */
    @PostMapping("/verify")
    public Mono<ResponseEntity<Map<String, Object>>> verifyToken() {
        return lineSettingsService.verifyAccessToken()
                .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                        "success", result.startsWith("驗證成功"),
                        "message", result
                )));
    }
}
