package com.linechatbot.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 推播任務建立請求
 */
@Data
public class BroadcastCreateRequest {

    @NotBlank(message = "任務名稱不可為空")
    private String name;

    /** 模板 ID（與 messageContent 二擇一；若提供則 service 會自模板載入內容） */
    private Long templateId;

    /** 直接帶訊息內容（JSON 陣列字串） */
    private String messageContent;

    /** ALL / TAGS / USER_LIST */
    @NotNull(message = "目標類型不可為空")
    private String targetType;

    /** 當 targetType = TAGS 時使用 */
    private List<Long> tagIds;

    /** 多標籤匹配方式：ANY（聯集）或 ALL（交集），預設 ANY */
    private String tagMatch;

    /** 當 targetType = USER_LIST 時使用（後台直接指定 LineUser ID） */
    private List<Long> userIds;

    /** LINE API 模式：PUSH（逐一精準）/ MULTICAST（批量、僅整批回報），null = 後端依規則決定，預設 PUSH */
    private String apiMode;

    /** 排程時間，null = 立即執行 */
    private LocalDateTime scheduledAt;

    /** 冪等鍵，前端 UUID。同一鍵在 24h 內回傳同一筆任務避免重複建立 */
    private String idempotencyKey;
}
