package com.linechatbot.controller;

import com.linechatbot.model.dto.LoginRequest;
import com.linechatbot.model.dto.LoginResponse;
import com.linechatbot.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 認證 API Controller
 * - POST /api/auth/login  登入
 * - POST /api/auth/logout 登出
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用戶登入，回傳 JWT Token
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse loginResponse = authService.login(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", loginResponse,
                "message", "登入成功"
        ));
    }

    /**
     * 用戶登出，將 Token 加入黑名單
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader("Authorization") String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            authService.logout(authHeader.substring(7));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "登出成功"
        ));
    }
}
