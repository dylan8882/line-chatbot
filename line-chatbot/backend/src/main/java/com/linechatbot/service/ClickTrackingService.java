package com.linechatbot.service;

import com.linechatbot.model.entity.ClickEvent;
import com.linechatbot.model.entity.ClickLink;
import com.linechatbot.repository.ClickEventRepository;
import com.linechatbot.repository.ClickLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Click tracking 中心服務：查 link、寫事件、回傳重導目標。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClickTrackingService {

    private final ClickLinkRepository linkRepository;
    private final ClickEventRepository eventRepository;

    /**
     * 由 controller 呼叫：依 token 找原始 URL，並非同步寫入 click_event + 累計 click_count。
     *
     * @return 原始目標 URL，若 token 不存在則 empty
     */
    public Optional<String> resolveAndRecord(String token, String userAgent, String ip, String referer) {
        Optional<ClickLink> opt = linkRepository.findByToken(token);
        if (opt.isEmpty()) return Optional.empty();

        ClickLink link = opt.get();
        recordAsync(link.getId(), link.getTaskId(), userAgent, ip, referer);
        return Optional.of(link.getTargetUrl());
    }

    /**
     * 非同步寫入事件，避免拖慢 redirect 回應。
     * 使用既有的 broadcastWorkerExecutor 池，本動作很輕量。
     */
    @Async("broadcastWorkerExecutor")
    @Transactional
    public void recordAsync(Long linkId, Long taskId, String userAgent, String ip, String referer) {
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
