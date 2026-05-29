package com.linechatbot.repository;

import com.linechatbot.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用戶資料庫存取層
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 根據用戶名稱查詢用戶
     */
    Optional<User> findByUsername(String username);

    /**
     * 確認用戶名稱是否已存在
     */
    boolean existsByUsername(String username);
}
