package com.linechatbot.config;

import com.linechatbot.model.entity.AiConfig;
import com.linechatbot.model.entity.User;
import com.linechatbot.repository.AiConfigRepository;
import com.linechatbot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 應用程式啟動時自動建立初始管理員帳號。
 * 讀取 ADMIN_USERNAME / ADMIN_PASSWORD 環境變數，密碼以 BCrypt 加密後存入資料庫。
 * 若帳號已存在則跳過，不覆蓋。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AiConfigRepository aiConfigRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        initAdmin();
        initAiConfig();
    }

    private void initAdmin() {
        if (userRepository.existsByUsername(adminUsername)) {
            log.info("管理員帳號已存在，跳過初始化：{}", adminUsername);
            return;
        }

        User admin = User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .role("ADMIN")
                .build();

        userRepository.save(admin);
        log.info("初始管理員帳號已建立：{}", adminUsername);
    }

    /**
     * 確保 ai_config 表有一筆預設記錄（id=1，enabled=true、apiKey/baseUrl/model 為空）。
     * 後台「AI 設定」頁讀到空欄位時自動 fallback 至 .env 環境變數。
     */
    private void initAiConfig() {
        if (aiConfigRepository.existsById(1L)) {
            log.info("AI 設定已存在，跳過初始化");
            return;
        }
        AiConfig defaults = AiConfig.builder()
                .id(1L)
                .enabled(true)
                .provider("openai")
                .build();
        aiConfigRepository.save(defaults);
        log.info("初始 AI 設定已建立（enabled=true，欄位皆空，將 fallback 至環境變數）");
    }
}
