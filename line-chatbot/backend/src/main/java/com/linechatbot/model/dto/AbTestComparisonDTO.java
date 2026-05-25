package com.linechatbot.model.dto;

import lombok.Data;

import java.util.List;

/**
 * A/B 測試各 variant 的成效比較
 */
@Data
public class AbTestComparisonDTO {
    private String abTestId;
    private String taskName;
    private List<VariantStat> variants;

    @Data
    public static class VariantStat {
        private Long taskId;
        private String label;
        private String status;
        private int totalRecipients;
        private int sentCount;
        private int successCount;
        private int failedCount;
        private double successRate;
    }
}
