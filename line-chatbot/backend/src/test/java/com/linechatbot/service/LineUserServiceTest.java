package com.linechatbot.service;

import com.linechatbot.model.dto.BulkTagRequest;
import com.linechatbot.model.entity.LineUser;
import com.linechatbot.model.entity.Tag;
import com.linechatbot.repository.LineUserRepository;
import com.linechatbot.repository.TagRepository;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.UserProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LineUserService 單元測試。
 */
@ExtendWith(MockitoExtension.class)
class LineUserServiceTest {

    @Mock LineUserRepository lineUserRepository;
    @Mock TagRepository tagRepository;
    @Mock MessagingApiClient messagingApiClient;

    @InjectMocks LineUserService service;

    @BeforeEach
    void setUp() {
        // 預設 enrichProfile 會失敗（無 mock），讓邏輯走 catch 分支，
        // 部分測試會單獨 stub。
        lenient().when(messagingApiClient.getProfile(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("profile API not mocked")));
        lenient().when(lineUserRepository.save(any())).thenAnswer(i -> {
            LineUser u = i.getArgument(0);
            if (u.getId() == null) u.setId(System.nanoTime());
            return u;
        });
    }

    @Test
    @DisplayName("onFollow：新用戶建檔並設 status=FOLLOWED")
    void onFollow_newUser_createsAndSetsFollowed() {
        when(lineUserRepository.findByLineUserId("Uabc")).thenReturn(Optional.empty());

        LineUser user = service.onFollow("Uabc");

        assertThat(user.getStatus()).isEqualTo("FOLLOWED");
        assertThat(user.getFollowedAt()).isNotNull();
        verify(lineUserRepository).save(any());
    }

    @Test
    @DisplayName("onFollow：既有 BLOCKED 用戶重新 follow 應切回 FOLLOWED")
    void onFollow_existingBlocked_setsBackToFollowed() {
        LineUser existing = LineUser.builder()
                .id(1L).lineUserId("Uabc").status("BLOCKED").build();
        when(lineUserRepository.findByLineUserId("Uabc")).thenReturn(Optional.of(existing));

        service.onFollow("Uabc");

        assertThat(existing.getStatus()).isEqualTo("FOLLOWED");
        assertThat(existing.getUnfollowedAt()).isNull();
        assertThat(existing.getFollowedAt()).isNotNull();
    }

    @Test
    @DisplayName("onUnfollow：既有用戶標記 BLOCKED")
    void onUnfollow_existingUser_marksBlocked() {
        LineUser existing = LineUser.builder()
                .id(1L).lineUserId("Uabc").status("FOLLOWED").build();
        when(lineUserRepository.findByLineUserId("Uabc")).thenReturn(Optional.of(existing));

        service.onUnfollow("Uabc");

        assertThat(existing.getStatus()).isEqualTo("BLOCKED");
        assertThat(existing.getUnfollowedAt()).isNotNull();
    }

    @Test
    @DisplayName("onUnfollow：未知用戶不應拋例外")
    void onUnfollow_unknownUser_noOp() {
        when(lineUserRepository.findByLineUserId("Uxxx")).thenReturn(Optional.empty());
        service.onUnfollow("Uxxx"); // should not throw
        verify(lineUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("touchOnMessage：未知用戶會兜底建檔並更新 last_message_at")
    void touchOnMessage_unknownUser_createsAndTouches() {
        when(lineUserRepository.findByLineUserId("Unew")).thenReturn(Optional.empty());

        service.touchOnMessage("Unew");

        ArgumentCaptor<LineUser> captor = ArgumentCaptor.forClass(LineUser.class);
        verify(lineUserRepository).save(captor.capture());
        LineUser saved = captor.getValue();
        assertThat(saved.getLineUserId()).isEqualTo("Unew");
        assertThat(saved.getLastMessageAt()).isNotNull();
    }

    @Test
    @DisplayName("touchOnMessage：既有用戶只更新 last_message_at")
    void touchOnMessage_existingUser_updatesTimestamp() {
        LineUser existing = LineUser.builder()
                .id(1L).lineUserId("Uabc").status("FOLLOWED").build();
        when(lineUserRepository.findByLineUserId("Uabc")).thenReturn(Optional.of(existing));

        service.touchOnMessage("Uabc");

        assertThat(existing.getLastMessageAt()).isNotNull();
    }

    @Test
    @DisplayName("assignTags：覆寫式以傳入 tagIds 為準")
    void assignTags_overwritesTagSet() {
        Tag oldTag = Tag.builder().id(1L).name("old").build();
        LineUser user = LineUser.builder()
                .id(10L).lineUserId("Uabc")
                .tags(new HashSet<>(Set.of(oldTag)))
                .build();
        Tag newTag1 = Tag.builder().id(2L).name("new1").build();
        Tag newTag2 = Tag.builder().id(3L).name("new2").build();
        when(lineUserRepository.findById(10L)).thenReturn(Optional.of(user));
        when(tagRepository.findAllById(List.of(2L, 3L))).thenReturn(List.of(newTag1, newTag2));

        service.assignTags(10L, List.of(2L, 3L));

        assertThat(user.getTags()).hasSize(2);
        assertThat(user.getTags()).extracting(Tag::getId).containsExactlyInAnyOrder(2L, 3L);
        // 應同時更新被影響的 1L（移除）+ 2L、3L（新增）共 3 個標籤的 user_count
        verify(tagRepository).refreshUserCount(any());
    }

    @Test
    @DisplayName("bulkTag ADD：使用者加上所選標籤")
    void bulkTag_add_appendsTags() {
        Tag tag = Tag.builder().id(1L).name("vip").build();
        LineUser user = LineUser.builder()
                .id(10L).lineUserId("Uabc").tags(new HashSet<>())
                .build();
        when(lineUserRepository.findAllById(List.of(10L))).thenReturn(List.of(user));
        when(tagRepository.findAllById(List.of(1L))).thenReturn(List.of(tag));

        BulkTagRequest req = new BulkTagRequest();
        req.setUserIds(List.of(10L));
        req.setTagIds(List.of(1L));
        req.setAction(BulkTagRequest.Action.ADD);

        int affected = service.bulkTag(req);

        assertThat(affected).isEqualTo(1);
        assertThat(user.getTags()).contains(tag);
    }

    @Test
    @DisplayName("bulkTag REMOVE：使用者移除所選標籤")
    void bulkTag_remove_removesTags() {
        Tag tag = Tag.builder().id(1L).name("vip").build();
        LineUser user = LineUser.builder()
                .id(10L).lineUserId("Uabc")
                .tags(new HashSet<>(Set.of(tag)))
                .build();
        when(lineUserRepository.findAllById(List.of(10L))).thenReturn(List.of(user));
        when(tagRepository.findAllById(List.of(1L))).thenReturn(List.of(tag));

        BulkTagRequest req = new BulkTagRequest();
        req.setUserIds(List.of(10L));
        req.setTagIds(List.of(1L));
        req.setAction(BulkTagRequest.Action.REMOVE);

        service.bulkTag(req);

        assertThat(user.getTags()).isEmpty();
    }

    @Test
    @DisplayName("enrichProfile 成功時應寫入 displayName / pictureUrl 等欄位")
    void onFollow_withProfileApiSuccess_enrichesUser() throws Exception {
        when(lineUserRepository.findByLineUserId("Uok")).thenReturn(Optional.empty());

        UserProfileResponse profile = new UserProfileResponse(
                "暱稱",
                "Uok",
                java.net.URI.create("https://example.com/avatar.jpg"),
                "Hello status",
                "zh-TW");
        Result<UserProfileResponse> result = new Result<>("req-1", null, profile);
        when(messagingApiClient.getProfile("Uok"))
                .thenReturn(CompletableFuture.completedFuture(result));

        LineUser user = service.onFollow("Uok");

        assertThat(user.getDisplayName()).isEqualTo("暱稱");
        assertThat(user.getPictureUrl()).isEqualTo("https://example.com/avatar.jpg");
        assertThat(user.getStatusMessage()).isEqualTo("Hello status");
        assertThat(user.getLanguage()).isEqualTo("zh-TW");
    }
}
