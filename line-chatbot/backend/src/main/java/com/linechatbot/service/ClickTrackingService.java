package com.linechatbot.service;

import com.linechatbot.model.entity.ClickLink;
import com.linechatbot.repository.ClickLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Click tracking 中心服務：查 link、回傳重導目標，並把實際寫入工作丟給
 * {@link ClickEventWriter}（獨立 bean，跨 bean 呼叫才能讓 Spring 的 @Async / @Transactional
 * proxy 生效；同 bean 內 self-invocation 會繞過 proxy）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClickTrackingService {

    private final ClickLinkRepository linkRepository;
    private final ClickEventWriter eventWriter;

    /** @return 原始 URL，token 不存在回 empty。事件寫入丟給 eventWriter 非同步處理。 */
    public Optional<String> resolveAndRecord(String token, String userAgent, String ip, String referer) {
        Optional<ClickLink> opt = linkRepository.findByToken(token);
        if (opt.isEmpty()) return Optional.empty();

        ClickLink link = opt.get();
        eventWriter.recordEvent(link.getId(), link.getTaskId(), userAgent, ip, referer);
        return Optional.of(link.getTargetUrl());
    }
}
