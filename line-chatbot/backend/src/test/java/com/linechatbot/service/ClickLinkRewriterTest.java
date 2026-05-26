package com.linechatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.model.entity.ClickLink;
import com.linechatbot.model.entity.LineChannelConfig;
import com.linechatbot.repository.ClickLinkRepository;
import com.linechatbot.repository.LineChannelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClickLinkRewriter 單元測試 — Phase 7 URL 改寫器。
 *
 * <p>主要 invariant：
 * <ul>
 *   <li>無 serverBaseUrl 時不動原始 JSON、不寫 click_links</li>
 *   <li>所有 type=uri 動作的 uri 都應改寫</li>
 *   <li>LINE 自家 line.me 連結不改寫</li>
 *   <li>巢狀 box 內 button 應被遞迴找到</li>
 *   <li>多個 URL 時 linkIndex 遞增</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ClickLinkRewriterTest {

    @Mock ClickLinkRepository linkRepository;
    @Mock LineChannelConfigRepository channelConfigRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ClickLinkRewriter rewriter;

    @BeforeEach
    void setUp() {
        rewriter = new ClickLinkRewriter(linkRepository, channelConfigRepository, objectMapper);
        lenient().when(linkRepository.save(any())).thenAnswer(i -> {
            ClickLink l = i.getArgument(0);
            l.setId(System.nanoTime());
            return l;
        });
    }

    @Test
    @DisplayName("無 serverBaseUrl 時不改寫，回傳原樣")
    void rewrite_noServerBaseUrl_returnsOriginal() {
        when(channelConfigRepository.findById(1L)).thenReturn(Optional.empty());
        String content = simpleButton("https://example.com/a");

        String out = rewriter.rewriteForTask(1L, content);

        assertThat(out).isEqualTo(content);
        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("單一 button URL 應改寫並寫入 click_link")
    void rewrite_singleButton_rewritesAndPersists() throws Exception {
        givenBaseUrl("https://cb.example.com");
        String content = simpleButton("https://example.com/a");

        String out = rewriter.rewriteForTask(42L, content);

        JsonNode tree = objectMapper.readTree(out);
        String uri = tree.get(0).get("contents").get("footer").get("contents")
                .get(0).get("action").get("uri").asText();
        assertThat(uri).startsWith("https://cb.example.com/c/");

        ArgumentCaptor<ClickLink> captor = ArgumentCaptor.forClass(ClickLink.class);
        verify(linkRepository).save(captor.capture());
        ClickLink saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo(42L);
        assertThat(saved.getLinkIndex()).isEqualTo(0);
        assertThat(saved.getTargetUrl()).isEqualTo("https://example.com/a");
        assertThat(saved.getToken()).isNotBlank();
    }

    @Test
    @DisplayName("LINE 自家 line.me 連結不改寫")
    void rewrite_lineMeUrl_skipped() {
        givenBaseUrl("https://cb.example.com");
        String content = simpleButton("https://line.me/R/oaMessage/@bot");

        String out = rewriter.rewriteForTask(1L, content);

        assertThat(out).contains("https://line.me/R/oaMessage/@bot");
        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("非 http/https URL（如 tel:）不改寫")
    void rewrite_nonHttpScheme_skipped() {
        givenBaseUrl("https://cb.example.com");
        // 模擬 phone link 在 LINE 中可能用 tel: scheme
        String content = "[{\"type\":\"flex\",\"contents\":{\"type\":\"bubble\",\"body\":{\"type\":\"box\",\"layout\":\"vertical\",\"contents\":[{\"type\":\"button\",\"action\":{\"type\":\"uri\",\"label\":\"call\",\"uri\":\"tel:0212345678\"}}]}}}]";

        rewriter.rewriteForTask(1L, content);
        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("多個 button URL：linkIndex 從 0 遞增")
    void rewrite_multipleButtons_linkIndexIncrements() throws Exception {
        givenBaseUrl("https://cb.example.com");
        String content = "[{\"type\":\"flex\",\"contents\":{\"type\":\"bubble\",\"body\":{\"type\":\"box\",\"layout\":\"vertical\",\"contents\":["
                + "{\"type\":\"button\",\"action\":{\"type\":\"uri\",\"label\":\"a\",\"uri\":\"https://a.com\"}},"
                + "{\"type\":\"button\",\"action\":{\"type\":\"uri\",\"label\":\"b\",\"uri\":\"https://b.com\"}},"
                + "{\"type\":\"button\",\"action\":{\"type\":\"uri\",\"label\":\"c\",\"uri\":\"https://c.com\"}}"
                + "]}}}]";

        rewriter.rewriteForTask(7L, content);

        ArgumentCaptor<ClickLink> captor = ArgumentCaptor.forClass(ClickLink.class);
        verify(linkRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        List<ClickLink> saved = captor.getAllValues();
        assertThat(saved).extracting(ClickLink::getLinkIndex).containsExactly(0, 1, 2);
        assertThat(saved).extracting(ClickLink::getTargetUrl)
                .containsExactly("https://a.com", "https://b.com", "https://c.com");
    }

    @Test
    @DisplayName("巢狀 box 內 button 應被遞迴找到")
    void rewrite_nestedBox_recurses() throws Exception {
        givenBaseUrl("https://cb.example.com");
        String content = "[{\"type\":\"flex\",\"contents\":{\"type\":\"bubble\",\"body\":"
                + "{\"type\":\"box\",\"layout\":\"vertical\",\"contents\":["
                + "  {\"type\":\"box\",\"layout\":\"horizontal\",\"contents\":["
                + "    {\"type\":\"button\",\"action\":{\"type\":\"uri\",\"label\":\"deep\",\"uri\":\"https://deep.example.com\"}}"
                + "  ]}"
                + "]}}}]";

        String out = rewriter.rewriteForTask(1L, content);

        JsonNode tree = objectMapper.readTree(out);
        String uri = tree.get(0).get("contents").get("body").get("contents").get(0)
                .get("contents").get(0).get("action").get("uri").asText();
        assertThat(uri).startsWith("https://cb.example.com/c/");
        verify(linkRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("純文字訊息無 button 時不改寫、不寫 click_link")
    void rewrite_textOnlyMessage_noChange() {
        givenBaseUrl("https://cb.example.com");
        String content = "[{\"type\":\"text\",\"text\":\"hello with https://example.com inside\"}]";

        String out = rewriter.rewriteForTask(1L, content);
        assertThat(out).isEqualTo(content);
        verify(linkRepository, never()).save(any());
    }

    // ── helpers ─────────────────────────────────────────────────

    private void givenBaseUrl(String baseUrl) {
        LineChannelConfig cfg = new LineChannelConfig();
        cfg.setServerBaseUrl(baseUrl);
        when(channelConfigRepository.findById(1L)).thenReturn(Optional.of(cfg));
    }

    private String simpleButton(String uri) {
        return "[{\"type\":\"flex\",\"contents\":{\"type\":\"bubble\",\"footer\":"
                + "{\"type\":\"box\",\"layout\":\"vertical\",\"contents\":["
                + "{\"type\":\"button\",\"action\":{\"type\":\"uri\",\"label\":\"go\",\"uri\":\"" + uri + "\"}}"
                + "]}}}]";
    }
}
