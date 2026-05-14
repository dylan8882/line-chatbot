package com.linechatbot.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 登入回應 DTO，包含 JWT Token 資訊
 */
@Data
@Builder
public class LoginResponse {

    /** JWT Access Token */
    private String token;

    /** Token 類型，固定為 Bearer */
    private String tokenType;

    /** Token 有效期（毫秒） */
    private Long expiresIn;

    /** 登入用戶名稱 */
    private String username;

    /** 用戶角色 */
    private String role;
}
