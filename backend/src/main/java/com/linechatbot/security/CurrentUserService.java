package com.linechatbot.security;

import com.linechatbot.model.entity.User;
import com.linechatbot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 取得目前登入用戶的 User entity（自 SecurityContext 解出 username 後查 DB）。
 *
 * <p>由 JwtAuthenticationFilter 將 authentication.principal 設為 username 字串。
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    /** 系統角色定義（從低到高權限） */
    public static final String ROLE_VIEWER = "VIEWER";
    public static final String ROLE_CS_AGENT = "CS_AGENT";
    public static final String ROLE_MARKETER = "MARKETER";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_ADMIN = "ADMIN";

    private final UserRepository userRepository;

    public Optional<String> getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        Object principal = auth.getPrincipal();
        return Optional.ofNullable(principal instanceof String s ? s : null);
    }

    public Optional<User> getCurrentUser() {
        return getCurrentUsername().flatMap(userRepository::findByUsername);
    }
}
