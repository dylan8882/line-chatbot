package com.linechatbot.repository;

import com.linechatbot.model.entity.AiConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiConfigRepository extends JpaRepository<AiConfig, Long> {
}
