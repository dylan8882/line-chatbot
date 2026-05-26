package com.linechatbot.service;

import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.TagDTO;
import com.linechatbot.model.entity.Tag;
import com.linechatbot.repository.TagRepository;
import com.linechatbot.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagService 單元測試。
 */
@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock TagRepository tagRepository;
    @Mock CurrentUserService currentUserService;

    @InjectMocks TagService tagService;

    @BeforeEach
    void setUp() {
        lenient().when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("create：成功建立並回傳 DTO")
    void create_success() {
        when(tagRepository.existsByName("VIP")).thenReturn(false);
        when(tagRepository.save(any())).thenAnswer(i -> {
            Tag t = i.getArgument(0);
            t.setId(10L);
            return t;
        });

        TagDTO dto = new TagDTO();
        dto.setName("VIP");
        dto.setColor("#ff0000");

        TagDTO out = tagService.create(dto);

        assertThat(out.getId()).isEqualTo(10L);
        assertThat(out.getName()).isEqualTo("VIP");
        assertThat(out.getColor()).isEqualTo("#ff0000");
    }

    @Test
    @DisplayName("create：名稱重複應拋 IllegalArgumentException")
    void create_duplicateName_throws() {
        when(tagRepository.existsByName("VIP")).thenReturn(true);
        TagDTO dto = new TagDTO();
        dto.setName("VIP");

        assertThatThrownBy(() -> tagService.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("create：color 為 null 時帶入預設色")
    void create_nullColor_usesDefault() {
        when(tagRepository.existsByName("X")).thenReturn(false);
        when(tagRepository.save(any())).thenAnswer(i -> {
            Tag t = i.getArgument(0);
            t.setId(1L);
            return t;
        });

        TagDTO dto = new TagDTO();
        dto.setName("X");
        dto.setColor(null);

        TagDTO out = tagService.create(dto);
        assertThat(out.getColor()).isEqualTo("#1677ff");
    }

    @Test
    @DisplayName("update：成功更新欄位")
    void update_success() {
        Tag existing = Tag.builder().id(1L).name("old").color("#000000").build();
        when(tagRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tagRepository.existsByName("new")).thenReturn(false);

        TagDTO dto = new TagDTO();
        dto.setName("new");
        dto.setColor("#ffffff");
        dto.setDescription("new desc");

        TagDTO out = tagService.update(1L, dto);

        assertThat(out.getName()).isEqualTo("new");
        assertThat(out.getColor()).isEqualTo("#ffffff");
        assertThat(existing.getDescription()).isEqualTo("new desc");
    }

    @Test
    @DisplayName("update：新名稱與其他標籤衝突應拋例外")
    void update_nameConflict_throws() {
        Tag existing = Tag.builder().id(1L).name("old").color("#000000").build();
        when(tagRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tagRepository.existsByName("taken")).thenReturn(true);

        TagDTO dto = new TagDTO();
        dto.setName("taken");

        assertThatThrownBy(() -> tagService.update(1L, dto))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update：不存在的 id 應拋 ResourceNotFoundException")
    void update_notFound_throws() {
        when(tagRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tagService.update(99L, new TagDTO()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("delete：成功時呼叫 repository.deleteById")
    void delete_success() {
        when(tagRepository.existsById(1L)).thenReturn(true);
        tagService.delete(1L);
        verify(tagRepository).deleteById(1L);
    }

    @Test
    @DisplayName("delete：不存在拋例外")
    void delete_notFound_throws() {
        when(tagRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> tagService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getById：不存在拋例外")
    void getById_notFound_throws() {
        when(tagRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tagService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
