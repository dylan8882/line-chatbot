package com.linechatbot.config;

import com.linechatbot.model.entity.User;
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
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
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
}
