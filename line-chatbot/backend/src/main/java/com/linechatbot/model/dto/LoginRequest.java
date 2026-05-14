package com.linechatbot.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登入請求 DTO
 */
@Data
public class LoginRequest {

    @NotBlank(message = "帳號不可為空")
    private String username;

    @NotBlank(message = "密碼不可為空")
    private String password;
}
