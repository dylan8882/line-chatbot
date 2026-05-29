package com.linechatbot.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 訊息模板 DTO
 */
@Data
public class MessageTemplateDTO {

    private Long id;

    @NotBlank(message = "模板名稱不可為空")
    @Size(max = 100)
    private String name;

    /** TEXT / FLEX / IMAGE / TEMPLATE */
    @NotNull(message = "訊息類型不可為空")
    private String messageType;

    /** LINE messages 物件陣列 JSON 字串 */
    @NotBlank(message = "訊息內容不可為空")
    private String content;

    private String thumbnail;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
