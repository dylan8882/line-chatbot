package com.linechatbot.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 標籤 DTO
 */
@Data
public class TagDTO {

    private Long id;

    @NotBlank(message = "標籤名稱不可為空")
    @Size(max = 50, message = "標籤名稱最多 50 字")
    private String name;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "顏色須為 HEX 格式（例：#1677ff）")
    private String color;

    @Size(max = 200, message = "說明最多 200 字")
    private String description;

    private Integer userCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
