package com.linechatbot.service;

import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.BulkTagRequest;
import com.linechatbot.model.dto.LineUserDTO;
import com.linechatbot.model.dto.TagDTO;
import com.linechatbot.model.entity.LineChannelConfig;
import com.linechatbot.model.entity.LineUser;
import com.linechatbot.model.entity.Tag;
import com.linechatbot.repository.LineChannelConfigRepository;
import com.linechatbot.repository.LineUserRepository;
import com.linechatbot.repository.TagRepository;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.PushMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.messaging.model.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LINE 用戶資料服務
 * - Follow / Unfollow 事件時的 upsert
 * - 收到訊息時的活躍度更新
 * - 後台查詢、標籤指派
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LineUserService {

    private final LineUserRepository lineUserRepository;
    private final TagRepository tagRepository;
    private final MessagingApiClient messagingApiClient;
    private final LineChannelConfigRepository channelConfigRepository;

    /**
     * Follow 事件處理：建立或重新啟用用戶，並嘗試抓取 LINE Profile。
     */
    @Transactional
    public LineUser onFollow(String lineUserId) {
        LineUser user = lineUserRepository.findByLineUserId(lineUserId)
                .orElseGet(() -> LineUser.builder().lineUserId(lineUserId).build());

        user.setStatus("FOLLOWED");
        user.setFollowedAt(LocalDateTime.now());
        user.setUnfollowedAt(null);

        enrichProfile(user);

        LineUser saved = lineUserRepository.save(user);
        log.info("LINE 用戶 Follow：userId={}, displayName={}", lineUserId, saved.getDisplayName());

        sendGreetingIfConfigured(lineUserId);
        return saved;
    }

    /**
     * 若 LineChannelConfig.greetingEnabled = true 且 greetingMessage 非空，
     * 透過 pushMessage 發送歡迎訊息。
     * 例外吞掉只記 warn，避免影響 Follow 事件主流程（DB 已存）。
     */
    private void sendGreetingIfConfigured(String lineUserId) {
        try {
            LineChannelConfig config = channelConfigRepository.findById(1L).orElse(null);
            if (config == null
                    || !Boolean.TRUE.equals(config.getGreetingEnabled())
                    || !StringUtils.hasText(config.getGreetingMessage())) {
                return;
            }
            UUID retryKey = UUID.nameUUIDFromBytes(("greeting-" + lineUserId).getBytes());
            messagingApiClient.pushMessage(retryKey, new PushMessageRequest(
                    lineUserId,
                    List.of(new TextMessage(config.getGreetingMessage())),
                    false,
                    null
            )).get();
            log.info("已發送歡迎訊息：userId={}", lineUserId);
        } catch (Exception e) {
            log.warn("發送歡迎訊息失敗：userId={}, error={}", lineUserId, e.getMessage());
        }
    }

    /**
     * Unfollow 事件處理：標記為 BLOCKED，保留歷史資料。
     */
    @Transactional
    public void onUnfollow(String lineUserId) {
        lineUserRepository.findByLineUserId(lineUserId).ifPresent(user -> {
            user.setStatus("BLOCKED");
            user.setUnfollowedAt(LocalDateTime.now());
            log.info("LINE 用戶 Unfollow：userId={}", lineUserId);
        });
    }

    /**
     * 收到訊息時呼叫：若用戶不存在則建立，並更新 last_message_at。
     */
    @Transactional
    public void touchOnMessage(String lineUserId) {
        LineUser user = lineUserRepository.findByLineUserId(lineUserId)
                .orElseGet(() -> {
                    LineUser u = LineUser.builder()
                            .lineUserId(lineUserId)
                            .status("FOLLOWED")
                            .followedAt(LocalDateTime.now())
                            .build();
                    enrichProfile(u);
                    return u;
                });
        user.setLastMessageAt(LocalDateTime.now());
        lineUserRepository.save(user);
    }

    /**
     * 後台分頁查詢，支援暱稱關鍵字 / 狀態 / 標籤篩選。
     *
     * <p>{@code @Transactional(readOnly = true)} 確保整個 method 在同一個 Hibernate session 內，
     * DTO 轉換時走 LAZY 的 {@code user.getTags()} 才能正確初始化（避免 LazyInitializationException）。
     */
    @Transactional(readOnly = true)
    public Page<LineUserDTO> search(String keyword, String status, List<Long> tagIds, Pageable pageable) {
        String kw = (keyword != null && !keyword.isBlank()) ? keyword : null;
        String st = (status != null && !status.isBlank()) ? status : null;
        List<Long> tags = (tagIds != null && !tagIds.isEmpty()) ? tagIds : null;
        return lineUserRepository.search(kw, st, tags, pageable).map(this::toDTO);
    }

    /**
     * 指派標籤到單一用戶（覆寫式：以傳入的 tagIds 為準）。
     */
    @Transactional
    public LineUserDTO assignTags(Long userId, List<Long> tagIds) {
        LineUser user = lineUserRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("LineUser", userId));

        Set<Long> oldTagIds = user.getTags().stream().map(Tag::getId).collect(Collectors.toSet());
        Set<Tag> newTags = new HashSet<>(tagRepository.findAllById(tagIds));
        user.setTags(newTags);

        // 統計受影響的標籤（新增 + 移除），統一刷新 user_count
        Set<Long> affected = new HashSet<>(oldTagIds);
        affected.addAll(newTags.stream().map(Tag::getId).toList());
        if (!affected.isEmpty()) {
            tagRepository.refreshUserCount(affected.stream().toList());
        }

        log.info("用戶標籤已更新：userId={}, tagIds={}", userId, tagIds);
        return toDTO(user);
    }

    /**
     * 批量貼 / 移除標籤（ADD / REMOVE）。
     */
    @Transactional
    public int bulkTag(BulkTagRequest req) {
        List<LineUser> users = lineUserRepository.findAllById(req.getUserIds());
        Set<Tag> tags = new HashSet<>(tagRepository.findAllById(req.getTagIds()));

        int affected = 0;
        for (LineUser user : users) {
            if (req.getAction() == BulkTagRequest.Action.ADD) {
                if (user.getTags().addAll(tags)) affected++;
            } else {
                if (user.getTags().removeAll(tags)) affected++;
            }
        }

        if (!req.getTagIds().isEmpty()) {
            tagRepository.refreshUserCount(req.getTagIds());
        }

        log.info("批量標籤操作：action={}, users={}, tags={}, 影響筆數={}",
                req.getAction(), req.getUserIds().size(), req.getTagIds().size(), affected);
        return affected;
    }

    /**
     * 取得單一用戶。
     * 同理需要 transaction 才能 lazy load tags。
     */
    @Transactional(readOnly = true)
    public LineUserDTO getById(Long id) {
        return lineUserRepository.findById(id)
                .map(this::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("LineUser", id));
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    /**
     * 呼叫 LINE Profile API 補充顯示名稱、頭像等資訊；失敗時不阻塞主流程。
     */
    private void enrichProfile(LineUser user) {
        try {
            UserProfileResponse profile = messagingApiClient.getProfile(user.getLineUserId())
                    .get()
                    .body();
            if (profile != null) {
                user.setDisplayName(profile.displayName());
                user.setPictureUrl(profile.pictureUrl() != null ? profile.pictureUrl().toString() : null);
                user.setStatusMessage(profile.statusMessage());
                user.setLanguage(profile.language());
            }
        } catch (Exception e) {
            log.warn("取得 LINE Profile 失敗：userId={}, error={}", user.getLineUserId(), e.getMessage());
        }
    }

    private LineUserDTO toDTO(LineUser user) {
        LineUserDTO dto = new LineUserDTO();
        dto.setId(user.getId());
        dto.setLineUserId(user.getLineUserId());
        dto.setDisplayName(user.getDisplayName());
        dto.setPictureUrl(user.getPictureUrl());
        dto.setStatusMessage(user.getStatusMessage());
        dto.setLanguage(user.getLanguage());
        dto.setStatus(user.getStatus());
        dto.setFollowedAt(user.getFollowedAt());
        dto.setUnfollowedAt(user.getUnfollowedAt());
        dto.setLastMessageAt(user.getLastMessageAt());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setTags(user.getTags().stream().map(this::toTagDTO).toList());
        return dto;
    }

    private TagDTO toTagDTO(Tag tag) {
        TagDTO dto = new TagDTO();
        dto.setId(tag.getId());
        dto.setName(tag.getName());
        dto.setColor(tag.getColor());
        dto.setDescription(tag.getDescription());
        dto.setUserCount(tag.getUserCount());
        return dto;
    }
}
