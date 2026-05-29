package com.linechatbot.repository;

import com.linechatbot.model.entity.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {
    List<MessageTemplate> findAllByOrderByUpdatedAtDesc();
}
