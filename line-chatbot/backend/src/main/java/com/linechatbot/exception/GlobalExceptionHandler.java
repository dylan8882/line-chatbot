package com.linechatbot.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全域例外處理器，統一 API 錯誤回應格式
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 建立標準錯誤回應
     */
    private Map<String, Object> errorResponse(String errorCode, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", errorCode);
        response.put("message", message);
        return response;
    }

    /**
     * 資源不存在
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorResponse("NOT_FOUND", ex.getMessage()));
    }

    /**
     * 帳號密碼錯誤
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorResponse("UNAUTHORIZED", ex.getMessage()));
    }

    /**
     * 請求參數驗證失敗
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            errors.put(fieldName, error.getDefaultMessage());
        });

        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "VALIDATION_ERROR");
        response.put("message", "請求參數驗證失敗");
        response.put("details", errors);
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 其他未預期例外
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("未預期錯誤：", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorResponse("INTERNAL_ERROR", "伺服器內部錯誤，請稍後再試"));
    }
}
