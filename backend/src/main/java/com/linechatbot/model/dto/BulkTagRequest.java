package com.linechatbot.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量貼標籤請求
 */
@Data
public class BulkTagRequest {

    @NotEmpty(message = "用戶 ID 清單不可為空")
    private List<Long> userIds;

    @NotEmpty(message = "標籤 ID 清單不可為空")
    private List<Long> tagIds;

    /** ADD（新增）或 REMOVE（移除） */
    @NotNull(message = "操作類型不可為空")
    private Action action;

    public enum Action {
        ADD, REMOVE
    }
}
