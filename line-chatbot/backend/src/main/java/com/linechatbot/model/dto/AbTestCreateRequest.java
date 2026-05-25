package com.linechatbot.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A/B 測試任務建立請求：一個母任務切成 N 個 variant（通常 2 個），各自走獨立派發。
 */
@Data
public class AbTestCreateRequest {

    @NotBlank(message = "任務名稱不可為空")
    private String name;

    /** A/B 變體清單（最少 2、最多 4） */
    @NotEmpty(message = "至少需要 2 個變體")
    @Size(min = 2, max = 4, message = "變體數量需介於 2~4 之間")
    private List<Variant> variants;

    /** ALL / TAGS / USER_LIST（NARROWCAST 不適用 A/B 測試） */
    @NotNull(message = "目標類型不可為空")
    private String targetType;

    private List<Long> tagIds;
    private String tagMatch;
    private List<Long> userIds;

    private LocalDateTime scheduledAt;
    private String idempotencyKey;

    @Data
    public static class Variant {
        /** 變體標籤："A" / "B" / "Control" / "Variant1" 等 */
        @NotBlank
        private String label;

        /** 模板 ID（與 messageContent 二擇一） */
        private Long templateId;

        /** 直接帶訊息內容 */
        private String messageContent;

        /** 流量比例（0~100），如 50 表示 50%。所有 variants 加總應為 100 */
        @NotNull
        private Integer trafficPercent;
    }
}
