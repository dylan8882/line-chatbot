package com.linechatbot.controller;

import com.linechatbot.service.ClickTrackingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/**
 * 點擊追蹤端點（公開、不需 JWT）。
 *
 * <p>使用者點擊推播訊息中 button 後到達此端點 → 寫入點擊事件並 302 重導到原始 URL。
 *
 * <p>SecurityConfig 已對 {@code /c/**} 放行。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ClickTrackingController {

    private final ClickTrackingService trackingService;

    @GetMapping("/c/{token}")
    public ResponseEntity<Void> redirect(@PathVariable String token, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        String referer = request.getHeader("Referer");

        Optional<String> targetUrl = trackingService.resolveAndRecord(token, userAgent, ip, referer);
        if (targetUrl.isEmpty()) {
            log.debug("無效 click token：{}", token);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(targetUrl.get()))
                .build();
    }

    /**
     * 嘗試從 X-Forwarded-For / X-Real-IP 取真實來源 IP；
     * 在 ngrok / nginx reverse proxy 後面這兩個 header 才有有效值。
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For 可能含多個 IP（client, proxy1, proxy2）取最左
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return request.getRemoteAddr();
    }
}
