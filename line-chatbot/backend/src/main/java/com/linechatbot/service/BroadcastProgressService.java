package com.linechatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.model.dto.BroadcastProgressEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 推播進度事件 hub：
 *
 * <ul>
 *   <li>{@link #publish(BroadcastProgressEvent)}：將事件 PUBLISH 到 Redis Pub/Sub
 *       對應的 channel（{@code broadcast:progress:{taskId}}）。</li>
 *   <li>{@link #subscribe(Long)}：取得單一 task 的 SseEmitter，供 controller 回傳給前端。</li>
 *   <li>內部 MessageListener 訂閱 {@code broadcast:progress:*} pattern，
 *       收到 Redis 廣播後分派給該 task 的所有 emitter。</li>
 * </ul>
 *
 * <p>多後端實例下也能正確分發：worker 在 A 實例 PUBLISH，瀏覽器連到 B 實例的 SSE，
 * 因為兩邊都訂閱同一個 Redis channel，B 實例會收到事件並推給瀏覽器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastProgressService {

    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000; // 30 分鐘

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    /** taskId → 同一任務的所有活躍 emitter 清單 */
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private MessageListener listener;

    @PostConstruct
    public void init() {
        listener = (message, pattern) -> {
            String channel = new String(message.getChannel());
            Long taskId = BroadcastEventChannels.parseTaskId(channel);
            if (taskId == null) return;

            String body = new String(message.getBody());
            try {
                BroadcastProgressEvent event = objectMapper.readValue(body, BroadcastProgressEvent.class);
                dispatchToEmitters(taskId, event);
            } catch (JsonProcessingException e) {
                log.warn("解析 progress 事件失敗：channel={}, body={}", channel, body, e);
            }
        };
        listenerContainer.addMessageListener(listener, new PatternTopic(BroadcastEventChannels.PROGRESS_PATTERN));
        log.info("BroadcastProgressService 已訂閱 pattern：{}", BroadcastEventChannels.PROGRESS_PATTERN);
    }

    @PreDestroy
    public void shutdown() {
        emitters.values().forEach(list -> list.forEach(SseEmitter::complete));
        emitters.clear();
    }

    /** 任何訂閱實例（包括自己）都會收到、跨後端實例分發靠這條。 */
    public void publish(BroadcastProgressEvent event) {
        if (event.getTimestamp() == null) event.setTimestamp(System.currentTimeMillis());
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(BroadcastEventChannels.progress(event.getTaskId()), json);
        } catch (JsonProcessingException e) {
            log.warn("序列化 progress 事件失敗：taskId={}, type={}", event.getTaskId(), event.getType(), e);
        }
    }

    /** caller 直接 return 給 controller；下游關閉時自動從 emitters map 移除。 */
    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable remove = () -> {
            List<SseEmitter> list = emitters.get(taskId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) emitters.remove(taskId);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());

        // 連線建立時送一筆 hello 事件，前端可確認連線 OK
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            log.debug("SSE hello 失敗：{}", e.getMessage());
        }
        return emitter;
    }

    /** 寫入失敗代表 client 斷線了，直接從清單移除避免下次再嘗試。 */
    private void dispatchToEmitters(Long taskId, BroadcastProgressEvent event) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) return;

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(event));
            } catch (Exception e) {
                log.debug("SSE 寫入失敗，移除 emitter：taskId={}, error={}", taskId, e.getMessage());
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) { }
                list.remove(emitter);
            }
        }
    }
}
