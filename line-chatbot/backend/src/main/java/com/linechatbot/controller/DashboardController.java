package com.linechatbot.controller;

import com.linechatbot.model.dto.UsageStatsDTO;
import com.linechatbot.model.entity.MessageLog;
import com.linechatbot.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 統計 API Controller（需 JWT 認證）
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final MessageLogRepository messageLogRepository;

    /**
     * 今日統計摘要 + 近 7 天訊息量趨勢（一個端點包兩種資料給 Dashboard 用）。
     * GET /api/dashboard/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT);
        LocalDateTime now = LocalDateTime.now();

        long total = messageLogRepository.countByCreatedAtBetween(startOfDay, now);
        long qaHits = messageLogRepository.countByCreatedAtBetweenAndResponseType(startOfDay, now, "QA");
        long aiReplies = messageLogRepository.countByCreatedAtBetweenAndResponseType(startOfDay, now, "AI");
        long noReply = messageLogRepository.countByCreatedAtBetweenAndResponseType(startOfDay, now, "NONE");
        Double avgLatency = messageLogRepository.findAvgLatencyBetween(startOfDay, now);

        double qaHitRate = total > 0 ? (double) qaHits / total * 100 : 0;

        // 近 7 天趨勢（Dashboard 圖表用）
        LocalDateTime sevenDaysAgo = LocalDateTime.of(LocalDate.now().minusDays(6), LocalTime.MIDNIGHT);
        List<UsageStatsDTO.DailyStats> dailyStats = loadDailyStats(sevenDaysAgo);

        UsageStatsDTO stats = UsageStatsDTO.builder()
                .totalMessages(total)
                .qaHits(qaHits)
                .aiReplies(aiReplies)
                .noReply(noReply)
                .qaHitRate(Math.round(qaHitRate * 10.0) / 10.0)
                .avgLatencyMs(avgLatency != null ? Math.round(avgLatency * 10.0) / 10.0 : 0.0)
                .dailyStats(dailyStats)
                .build();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", stats,
                "message", "查詢成功"
        ));
    }

    /** 共用：把 native query 結果 map 成 DTO 列表 */
    private List<UsageStatsDTO.DailyStats> loadDailyStats(LocalDateTime since) {
        List<Object[]> rawStats = messageLogRepository.findDailyStatsSince(since);
        List<UsageStatsDTO.DailyStats> dailyStats = new ArrayList<>();
        for (Object[] row : rawStats) {
            dailyStats.add(UsageStatsDTO.DailyStats.builder()
                    .date(((java.sql.Date) row[0]).toLocalDate())
                    .messageCount(((Number) row[1]).longValue())
                    .qaCount(((Number) row[2]).longValue())
                    .aiCount(((Number) row[3]).longValue())
                    .build());
        }
        return dailyStats;
    }

    /**
     * 近 N 天用量趨勢
     * GET /api/dashboard/usage?days=7
     */
    @GetMapping("/usage")
    public ResponseEntity<Map<String, Object>> getUsage(
            @RequestParam(defaultValue = "7") int days) {
        LocalDateTime since = LocalDateTime.of(
                LocalDate.now().minusDays(days - 1), LocalTime.MIDNIGHT);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", loadDailyStats(since),
                "message", "查詢成功"
        ));
    }

    /**
     * 訊息紀錄（分頁）
     * GET /api/dashboard/logs?page=0&size=20
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getLogs(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<MessageLog> logs = messageLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", logs,
                "message", "查詢成功"
        ));
    }
}
