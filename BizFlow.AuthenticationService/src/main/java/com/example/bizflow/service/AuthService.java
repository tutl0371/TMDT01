package com.example.bizflow.service;

import com.example.bizflow.dto.LoginRequest;
import com.example.bizflow.dto.LoginResponse;
import com.example.bizflow.entity.User;
import com.example.bizflow.repository.UserRepository;
import com.example.bizflow.util.JwtUtil;
import com.example.bizflow.util.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Xác thực người dùng và tạo tokens
     * 
     * @param request LoginRequest chứa username và password
     * @return LoginResponse chứa access token, refresh token và thông tin user
     * @throws RuntimeException nếu username không tồn tại hoặc password sai
     */
    public LoginResponse authenticate(LoginRequest request) {
        // Hỗ trợ đăng nhập bằng username, email hoặc số điện thoại
        User user = userRepository.findByUsername(request.getUsername())
                .or(() -> userRepository.findByEmail(request.getUsername()))
                .or(() -> userRepository.findByPhoneNumber(request.getUsername()))
                .orElseThrow(() -> new RuntimeException("Tên đăng nhập hoặc mật khẩu không chính xác"));

        // Kiểm tra tài khoản có bị vô hiệu hóa
        if (!user.getEnabled()) {
            throw new RuntimeException("Tài khoản của bạn đã bị vô hiệu hóa");
        }

        // Kiểm tra password
        String storedHash = user.getPasswordHash();
        if (storedHash == null || storedHash.isBlank()) {
            storedHash = user.getPassword();
        }

        boolean match = passwordEncoder.matches(request.getPassword(), storedHash);
        if (!match) {
            throw new RuntimeException("Tên đăng nhập hoặc mật khẩu không chính xác");
        }

        // Tạo tokens
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        // Trả về response
        return new LoginResponse(accessToken, refreshToken, user.getRole().name(), user.getId(), user.getUsername());
    }

    /**
     * Xác thực token JWT
     * 
     * @param token JWT token
     * @return true nếu token hợp lệ, false nếu không
     */
    public boolean validateToken(String token) {
        return jwtUtil.validateToken(token);
    }

    /**
     * Lấy username từ token
     * 
     * @param token JWT token
     * @return username
     */
    public String getUsernameFromToken(String token) {
        return jwtUtil.getUsernameFromToken(token);
    }
}
