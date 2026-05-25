package com.linechatbot.service;

/**
 * Broadcast 模組使用的 Redis Pub/Sub channel 命名常數。
 */
public final class BroadcastEventChannels {

    private BroadcastEventChannels() {}

    /** Pattern subscription pattern：監聽所有任務進度 */
    public static final String PROGRESS_PATTERN = "broadcast:progress:*";

    /** 單一任務的 progress channel */
    public static String progress(Long taskId) {
        return "broadcast:progress:" + taskId;
    }

    /** 從 channel 字串擷取 taskId */
    public static Long parseTaskId(String channel) {
        int idx = channel.lastIndexOf(':');
        if (idx < 0 || idx == channel.length() - 1) return null;
        try {
            return Long.valueOf(channel.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
