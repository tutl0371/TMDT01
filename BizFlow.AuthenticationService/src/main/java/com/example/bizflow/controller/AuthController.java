package com.example.bizflow.controller;

import com.example.bizflow.dto.LoginRequest;
import com.example.bizflow.dto.LoginResponse;
import com.example.bizflow.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {
    
    private final AuthService authService;
    
    public AuthController(AuthService authService) {
        this.authService = authService;
    }
    
    /**
     * API đăng nhập
     * @param loginRequest username và password
     * @return LoginResponse chứa access token, refresh token và thông tin user
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest) {
        try {
            LoginResponse response = authService.authenticate(loginRequest);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Đã xảy ra lỗi, vui lòng thử lại sau");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
    
    /**
     * API kiểm tra health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("message", "Auth service is running");
        return ResponseEntity.ok(response);
    }
    
    /**
     * API tạo hash BCrypt (CHỈ DÙNG ĐỂ DEBUG - XÓA KHI DEPLOY PRODUCTION)
     */
    @GetMapping("/debug/hash/{password}")
    public ResponseEntity<Map<String, String>> generateHash(@PathVariable String password) {
        com.example.bizflow.util.PasswordEncoder encoder = new com.example.bizflow.util.PasswordEncoder();
        Map<String, String> response = new HashMap<>();
        response.put("password", password);
        response.put("hash", encoder.encode(password));
        return ResponseEntity.ok(response);
    }
}
