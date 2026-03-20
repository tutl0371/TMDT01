package com.example.bizflow.service;

import com.example.bizflow.dto.CreateUserRequest;
import com.example.bizflow.entity.Branch;
import com.example.bizflow.entity.Role;
import com.example.bizflow.entity.User;
import com.example.bizflow.repository.BranchRepository;
import com.example.bizflow.repository.UserRepository;
import com.example.bizflow.util.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BranchRepository branchRepository;

    @SuppressWarnings("null")
    public User createUser(CreateUserRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new RuntimeException("Username already exists");
        }

        User user = new User();
        user.setUsername(request.getUsername());

        String encodedPassword = new PasswordEncoder().encode(request.getPassword());
        user.setPassword(encodedPassword);
        user.setPasswordHash(encodedPassword);
        user.setEmail(request.getEmail());
        user.setFullName(request.getFullName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setRole(Role.valueOf(request.getRole().toUpperCase()));
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());

        if (request.getBranchId() != null) {
            Branch branch = branchRepository.findById(request.getBranchId()).orElse(null);
            user.setBranch(branch);
        }

        return userRepository.save(user);
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getUserById(@NonNull Long id) {
        return userRepository.findById(id).orElse(null);
    }

    @SuppressWarnings("null")
    public User updateUser(@NonNull Long id, @NonNull CreateUserRequest request) {
        User user = userRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
        user.setEmail(request.getEmail());
        user.setFullName(request.getFullName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setRole(Role.valueOf(request.getRole().toUpperCase()));
        user.setUpdatedAt(LocalDateTime.now());

        if (request.getBranchId() != null) {
            Branch branch = branchRepository.findById(request.getBranchId()).orElse(null);
            user.setBranch(branch);
        }

        return userRepository.save(user);
    }

    public void deleteUser(@NonNull Long id) {
        userRepository.deleteById(id);
    }

    public User setUserEnabled(@NonNull Long id, boolean enabled) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setEnabled(enabled);
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    public User toggleUserEnabled(@NonNull Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setEnabled(!user.getEnabled());
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    // Admin dashboard methods
    public long getUsersCount() {
        return userRepository.count();
    }

    public long getStaffCount() {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.EMPLOYEE)
                .count();
    }

    public List<User> getRecentUsers(int limit) {
        List<User> allUsers = userRepository.findAll();
        allUsers.sort((a, b) -> {
            if (b.getCreatedAt() == null)
                return -1;
            if (a.getCreatedAt() == null)
                return 1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });
        return allUsers.stream().limit(limit).toList();
    }
}
