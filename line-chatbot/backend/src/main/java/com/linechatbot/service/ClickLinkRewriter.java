package com.linechatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linechatbot.model.entity.ClickLink;
import com.linechatbot.repository.ClickLinkRepository;
import com.linechatbot.repository.LineChannelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * URL 改寫器：將 LINE messages JSON 內所有 {type:"uri", uri:"..."} 動作改寫為
 * tracking URL（{serverBaseUrl}/c/{token}），並把 (taskId, linkIndex, targetUrl, token)
 * 落地到 click_links 表。
 *
 * <p>遞迴掃描整個 messages 樹，找到所有 type=uri 的 action 節點。
 * 同一個 URL 出現多次（例如多個按鈕指同一站）會建立各自的 click_link，
 * 點擊統計可分別呈現。
 *
 * <p><b>不會改寫</b>純文字訊息內的 URL 字串（沒有結構化 action node）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClickLinkRewriter {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 9; // base64-urlsafe 後 ~12 字元
    private static final String LINE_API_HOST = "line.me"; // LINE 自家連結不改寫

    private final ClickLinkRepository linkRepository;
    private final LineChannelConfigRepository channelConfigRepository;
    private final ObjectMapper objectMapper;

    /**
     * 改寫一個 task 的 messageContent，回傳新 JSON 字串。
     *
     * @return 改寫後 JSON；若無可改寫 URL 或 serverBaseUrl 未設定則回傳原樣
     */
    public String rewriteForTask(Long taskId, String messageContent) {
        String baseUrl = serverBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            log.warn("serverBaseUrl 未設定，跳過 click tracking 改寫：taskId={}", taskId);
            return messageContent;
        }

        try {
            JsonNode root = objectMapper.readTree(messageContent);
            AtomicInteger linkIndex = new AtomicInteger(0);
            int rewritten = visit(root, taskId, baseUrl, linkIndex);
            if (rewritten == 0) return messageContent;
            String out = objectMapper.writeValueAsString(root);
            log.info("已改寫 {} 個 URL 為 tracking link：taskId={}", rewritten, taskId);
            return out;
        } catch (Exception e) {
            log.error("URL 改寫失敗，使用原始 content：taskId={}", taskId, e);
            return messageContent;
        }
    }

    // ── 私有 ──────────────────────────────────────────────────

    private int visit(JsonNode node, Long taskId, String baseUrl, AtomicInteger linkIndex) {
        int count = 0;
        if (node.isArray()) {
            for (JsonNode child : node) count += visit(child, taskId, baseUrl, linkIndex);
        } else if (node.isObject()) {
            // type=uri 動作（button.action / image.action / 等都有相同結構）
            if ("uri".equals(text(node, "type")) && node.has("uri")) {
                String original = node.get("uri").asText();
                if (shouldRewrite(original)) {
                    String token = createLink(taskId, linkIndex.getAndIncrement(), original);
                    ((ObjectNode) node).put("uri", baseUrl + "/c/" + token);
                    count++;
                }
            }
            node.fields().forEachRemaining(e -> {
                // 已處理 uri 字串本身，不需要再遞迴進去
                if (!"uri".equals(e.getKey()) && !"type".equals(e.getKey())) {
                    // visit child — but can't easily accumulate count via lambda; do directly
                }
            });
            // 重新遞迴所有子節點（不靠 lambda 累加）
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue().isContainerNode()) {
                    count += visit(e.getValue(), taskId, baseUrl, linkIndex);
                }
            }
        }
        return count;
    }

    private boolean shouldRewrite(String url) {
        if (!StringUtils.hasText(url)) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        // LINE 自家連結（line.me、liff）不改寫，避免破壞 deeplink 行為
        if (url.contains(LINE_API_HOST)) return false;
        if (url.startsWith("line://") || url.startsWith("https://line.me/")) return false;
        return true;
    }

    private String createLink(Long taskId, int linkIndex, String targetUrl) {
        String token = randomToken();
        ClickLink link = ClickLink.builder()
                .taskId(taskId)
                .linkIndex(linkIndex)
                .targetUrl(truncate(targetUrl, 2000))
                .token(token)
                .build();
        linkRepository.save(link);
        return token;
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String serverBaseUrl() {
        return channelConfigRepository.findById(1L)
                .map(c -> c.getServerBaseUrl())
                .orElse(null);
    }
}
