package com.linechatbot.service;

import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.TagDTO;
import com.linechatbot.model.entity.Tag;
import com.linechatbot.repository.TagRepository;
import com.linechatbot.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 標籤管理服務
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TagService {

    private final TagRepository tagRepository;
    private final CurrentUserService currentUserService;

    /**
     * 取得所有標籤（依建立時間遞減排序）。
     */
    public List<TagDTO> getAll() {
        return tagRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 取得單一標籤。
     */
    public TagDTO getById(Long id) {
        return tagRepository.findById(id)
                .map(this::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Tag", id));
    }

    /**
     * 新增標籤；名稱重複時拋 IllegalArgumentException。
     */
    @Transactional
    public TagDTO create(TagDTO dto) {
        if (tagRepository.existsByName(dto.getName())) {
            throw new IllegalArgumentException("標籤名稱已存在：" + dto.getName());
        }
        Tag tag = Tag.builder()
                .name(dto.getName())
                .color(dto.getColor() != null ? dto.getColor() : "#1677ff")
                .description(dto.getDescription())
                .createdBy(currentUserService.getCurrentUser().orElse(null))
                .build();
        Tag saved = tagRepository.save(tag);
        log.info("建立標籤：name={}, color={}", saved.getName(), saved.getColor());
        return toDTO(saved);
    }

    /**
     * 修改標籤。
     */
    @Transactional
    public TagDTO update(Long id, TagDTO dto) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag", id));

        if (!tag.getName().equals(dto.getName()) && tagRepository.existsByName(dto.getName())) {
            throw new IllegalArgumentException("標籤名稱已存在：" + dto.getName());
        }

        tag.setName(dto.getName());
        if (dto.getColor() != null) tag.setColor(dto.getColor());
        tag.setDescription(dto.getDescription());

        log.info("更新標籤：id={}, name={}", id, tag.getName());
        return toDTO(tag);
    }

    /**
     * 刪除標籤（會自動連動刪除 user_tags 關聯，由 FK ON DELETE CASCADE 處理）。
     */
    @Transactional
    public void delete(Long id) {
        if (!tagRepository.existsById(id)) {
            throw new ResourceNotFoundException("Tag", id);
        }
        tagRepository.deleteById(id);
        log.info("刪除標籤：id={}", id);
    }

    private TagDTO toDTO(Tag tag) {
        TagDTO dto = new TagDTO();
        dto.setId(tag.getId());
        dto.setName(tag.getName());
        dto.setColor(tag.getColor());
        dto.setDescription(tag.getDescription());
        dto.setUserCount(tag.getUserCount());
        dto.setCreatedAt(tag.getCreatedAt());
        dto.setUpdatedAt(tag.getUpdatedAt());
        return dto;
    }
}
