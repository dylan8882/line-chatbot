package com.linechatbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linechatbot.exception.ResourceNotFoundException;
import com.linechatbot.model.dto.MessageTemplateDTO;
import com.linechatbot.model.entity.MessageTemplate;
import com.linechatbot.repository.MessageTemplateRepository;
import com.linechatbot.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 訊息模板服務：CRUD + 內容 JSON 格式驗證
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageTemplateService {

    private static final Set<String> ALLOWED_TYPES = Set.of("TEXT", "FLEX", "IMAGE", "TEMPLATE");

    private final MessageTemplateRepository templateRepository;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public List<MessageTemplateDTO> getAll() {
        return templateRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toDTO)
                .toList();
    }

    public MessageTemplateDTO getById(Long id) {
        return templateRepository.findById(id)
                .map(this::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", id));
    }

    public MessageTemplate getEntityById(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", id));
    }

    @Transactional
    public MessageTemplateDTO create(MessageTemplateDTO dto) {
        validate(dto);
        MessageTemplate template = MessageTemplate.builder()
                .name(dto.getName())
                .messageType(dto.getMessageType())
                .content(dto.getContent())
                .thumbnail(dto.getThumbnail())
                .createdBy(currentUserService.getCurrentUser().orElse(null))
                .build();
        MessageTemplate saved = templateRepository.save(template);
        log.info("建立訊息模板：name={}, type={}", saved.getName(), saved.getMessageType());
        return toDTO(saved);
    }

    @Transactional
    public MessageTemplateDTO update(Long id, MessageTemplateDTO dto) {
        validate(dto);
        MessageTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MessageTemplate", id));
        template.setName(dto.getName());
        template.setMessageType(dto.getMessageType());
        template.setContent(dto.getContent());
        template.setThumbnail(dto.getThumbnail());
        return toDTO(template);
    }

    @Transactional
    public void delete(Long id) {
        if (!templateRepository.existsById(id)) {
            throw new ResourceNotFoundException("MessageTemplate", id);
        }
        templateRepository.deleteById(id);
    }

    /**
     * 驗證類型與 JSON 格式（messages 必須是非空陣列）。
     */
    private void validate(MessageTemplateDTO dto) {
        if (!ALLOWED_TYPES.contains(dto.getMessageType())) {
            throw new IllegalArgumentException("不支援的訊息類型：" + dto.getMessageType());
        }
        try {
            JsonNode node = objectMapper.readTree(dto.getContent());
            if (!node.isArray() || node.isEmpty()) {
                throw new IllegalArgumentException("content 必須為非空 JSON 陣列");
            }
            if (node.size() > 5) {
                throw new IllegalArgumentException("LINE 單次最多 5 則訊息");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("content JSON 格式錯誤：" + e.getOriginalMessage());
        }
    }

    private MessageTemplateDTO toDTO(MessageTemplate t) {
        MessageTemplateDTO dto = new MessageTemplateDTO();
        dto.setId(t.getId());
        dto.setName(t.getName());
        dto.setMessageType(t.getMessageType());
        dto.setContent(t.getContent());
        dto.setThumbnail(t.getThumbnail());
        dto.setCreatedAt(t.getCreatedAt());
        dto.setUpdatedAt(t.getUpdatedAt());
        return dto;
    }
}
