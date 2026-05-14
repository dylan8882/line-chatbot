package com.linechatbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 資料庫設定
 * 啟用 JPA 審計功能（created_at, updated_at 自動填充）
 * 啟用事務管理
 * 雙資料庫切換透過 Spring Profile 控制（mysql / postgres）
 */
@Configuration
@EnableJpaAuditing
@EnableTransactionManagement
public class DatabaseConfig {
    // 資料庫連線設定透過 application-mysql.yml / application-postgres.yml 注入
    // HikariCP 連線池設定也在各 profile yml 中設定
}
