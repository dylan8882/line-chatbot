package com.linechatbot.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 問答配對 DTO，用於 API 請求與回應
 */
@Data
public class QAPairDTO {

    private Long id;

    /** 關鍵字 */
    @NotBlank(message = "關鍵字不可為空")
    private String keyword;

    /** 回答內容 */
    @NotBlank(message = "回答內容不可為空")
    private String answer;

    /** 是否啟用 */
    private Boolean isActive;

    /** 優先順序 */
    private Integer priority;

    /** 比對方式：EXACT / CONTAINS / REGEX */
    @NotNull(message = "比對方式不可為空")
    private String matchType;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
