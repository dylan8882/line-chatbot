package com.linechatbot.service;

import com.linechatbot.model.entity.ClickEvent;
import com.linechatbot.repository.ClickEventRepository;
import com.linechatbot.repository.ClickLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Click event 非同步寫入器（事件 INSERT + click_count INCR）。
 *
 * <p>故意拆成獨立 bean，讓 {@link ClickTrackingService} 跨 bean 呼叫，Spring AOP proxy
 * 才會包到 {@code @Async} 與 {@code @Transactional} 兩個 aspect。
 * 同 bean 內 {@code this.xxx()} 屬於 self-invocation，會繞過 proxy、兩個 annotation 都失效，
 * {@link ClickLinkRepository#incrementClickCount(Long)} 這種 {@code @Modifying} JPQL
 * 會抛 {@code TransactionRequiredException}。
 *
 * <p><b>executor 選擇</b>：用獨立的 {@code clickEventExecutor}（core=2, max=8, queue=500），
 * 避免兩個陷阱：
 * <ol>
 *   <li>{@code broadcastWorkerExecutor} 的 core threads 全被 4 個無限迴圈 worker 占住，任務永遠 enqueue 不執行</li>
 *   <li>共用 {@code lineMessageExecutor} 時點擊爆量會延遲 LINE Webhook 回覆</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClickEventWriter {

    private final ClickEventRepository eventRepository;
    private final ClickLinkRepository linkRepository;

    /**
     * 在獨立執行緒上寫一筆 click_event、並原子遞增 click_links.click_count。
     * 例外吞掉只記 warn — 點擊事件寫入失敗不能影響 redirect 回應。
     */
    @Async("clickEventExecutor")
    @Transactional
    public void recordEvent(Long linkId, Long taskId, String userAgent, String ip, String referer) {
        try {
            eventRepository.save(ClickEvent.builder()
                    .linkId(linkId)
                    .taskId(taskId)
                    .userAgent(truncate(userAgent, 500))
                    .ip(truncate(ip, 45))
                    .referer(truncate(referer, 500))
                    .build());
            linkRepository.incrementClickCount(linkId);
        } catch (Exception e) {
            log.warn("寫入 click_event 失敗：linkId={}, error={}", linkId, e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
