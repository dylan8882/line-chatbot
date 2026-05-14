package com.linechatbot.service;

import com.linechatbot.model.dto.LoginRequest;
import com.linechatbot.model.dto.LoginResponse;
import com.linechatbot.model.entity.User;
import com.linechatbot.repository.UserRepository;
import com.linechatbot.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 登入發 JWT、登出把 token 寫入黑名單。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("帳號或密碼錯誤"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("帳號或密碼錯誤");
        }

        String token = jwtTokenProvider.generateToken(user.getUsername(), user.getRole());
        log.info("用戶 {} 登入成功", user.getUsername());

        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(86400000L)
                .username(user.getUsername())
                .role(user.getRole())
                .build();
    }

    public void logout(String token) {
        jwtTokenProvider.blacklistToken(token);
        log.info("Token 已登出並加入黑名單");
    }
}
