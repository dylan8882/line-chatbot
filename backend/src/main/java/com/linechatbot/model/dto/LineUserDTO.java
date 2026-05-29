package com.linechatbot.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LINE 用戶資料 DTO（後台查詢用）
 */
@Data
public class LineUserDTO {

    private Long id;
    private String lineUserId;
    private String displayName;
    private String pictureUrl;
    private String statusMessage;
    private String language;
    private String status;
    private LocalDateTime followedAt;
    private LocalDateTime unfollowedAt;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;

    /** 該用戶擁有的標籤清單 */
    private List<TagDTO> tags;
}
