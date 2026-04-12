package com.example.bizflow.controller;

import com.example.bizflow.repository.CustomerRepository;
import com.example.bizflow.service.PointService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/customers")
public class CustomerInternalController {

    private final PointService pointService;
    private final CustomerRepository customerRepository;

    public CustomerInternalController(PointService pointService,
                                      CustomerRepository customerRepository) {
        this.pointService = pointService;
        this.customerRepository = customerRepository;
    }

    @PostMapping("/points/add")
    public ResponseEntity<Void> addPoints(@RequestBody PointAddRequest request) {
        if (request == null || request.customerId == null || request.totalAmount == null) {
            return ResponseEntity.badRequest().build();
        }
        pointService.addPoints(request.customerId, request.totalAmount, request.reference);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/points/redeem")
    public ResponseEntity<Map<String, Integer>> redeemPoints(@RequestBody PointRedeemRequest request) {
        if (request == null || request.customerId == null || request.points == null) {
            return ResponseEntity.badRequest().build();
        }
        int redeemed = pointService.redeemPoints(request.customerId, request.points);
        return ResponseEntity.ok(Map.of("redeemed", redeemed));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Long>> searchCustomers(@RequestParam(name = "keyword", required = false) String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        String trimmed = keyword.trim();
        // If the keyword contains digits, treat it as a phone search and normalize digits-only
        if (trimmed.matches(".*\\d.*")) {
            String digits = normalizePhone(trimmed);
            return ResponseEntity.ok(customerRepository.searchCustomerIds(digits));
        }
        return ResponseEntity.ok(customerRepository.searchCustomerIds(trimmed));
    }

    @PostMapping("/upsert")
    public ResponseEntity<?> upsertCustomer(@RequestBody UpsertRequest request) {
        if (request == null || request.phone == null || request.phone.isBlank()) {
            return ResponseEntity.badRequest().body("Phone is required");
        }
        String raw = request.phone.trim();
        String phone = normalizePhone(raw);
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body("Phone is required");
        }
        // try find existing by normalized phone first, then fallback to raw
        java.util.Optional<com.example.bizflow.entity.Customer> found = customerRepository.findByPhone(phone);
        if (found.isEmpty()) {
            found = customerRepository.findByPhone(raw);
        }
        // try find existing by exact phone
        return found
                .map(existing -> {
                    boolean changed = false;
                    if (request.name != null && !request.name.isBlank()) {
                        existing.setName(request.name.trim());
                        changed = true;
                    }
                    if (request.email != null) {
                        existing.setEmail(trimToNull(request.email));
                        changed = true;
                    }
                    if (request.address != null) {
                        existing.setAddress(trimToNull(request.address));
                        changed = true;
                    }
                    // ensure phone is stored normalized
                    if (existing.getPhone() == null || !existing.getPhone().equals(phone)) {
                        existing.setPhone(phone);
                        changed = true;
                    }
                    if (changed) customerRepository.save(existing);
                    return ResponseEntity.ok(existing);
                })
                .orElseGet(() -> {
                    String name = request.name != null && !request.name.isBlank() ? request.name.trim() : "Khách hàng";
                    com.example.bizflow.entity.Customer created = new com.example.bizflow.entity.Customer(name, phone);
                    created.setEmail(trimToNull(request.email));
                    created.setAddress(trimToNull(request.address));
                    com.example.bizflow.entity.Customer saved = customerRepository.save(created);
                    return ResponseEntity.ok(saved);
                });
    }

    @PostMapping("/upsertByUser")
    public ResponseEntity<?> upsertCustomerByUser(@RequestBody UpsertByUserRequest request) {
        if (request == null || request.userId == null) {
            return ResponseEntity.badRequest().body("userId is required");
        }

        // Try find by userId first, then by username
        java.util.Optional<com.example.bizflow.entity.Customer> found = customerRepository.findByUserId(request.userId);
        if (found.isEmpty() && request.username != null && !request.username.isBlank()) {
            found = customerRepository.findByUsername(request.username.trim());
        }

        return found
                .map(existing -> {
                    boolean changed = false;
                    if (request.name != null && !request.name.isBlank()) {
                        existing.setName(request.name.trim());
                        changed = true;
                    }
                    if (request.email != null) {
                        existing.setEmail(trimToNull(request.email));
                        changed = true;
                    }
                    if (request.address != null) {
                        existing.setAddress(trimToNull(request.address));
                        changed = true;
                    }
                    if (existing.getUserId() == null || !existing.getUserId().equals(request.userId)) {
                        existing.setUserId(request.userId);
                        changed = true;
                    }
                    if (request.username != null && !request.username.isBlank()
                            && (existing.getUsername() == null || !existing.getUsername().equals(request.username.trim()))) {
                        existing.setUsername(request.username.trim());
                        changed = true;
                    }
                    if (changed) customerRepository.save(existing);
                    return ResponseEntity.ok(existing);
                })
                .orElseGet(() -> {
                    String name = request.name != null && !request.name.isBlank() ? request.name.trim() : "Khách hàng";
                    com.example.bizflow.entity.Customer created = new com.example.bizflow.entity.Customer(name, null);
                    created.setUserId(request.userId);
                    created.setUsername(trimToNull(request.username));
                    created.setEmail(trimToNull(request.email));
                    created.setAddress(trimToNull(request.address));
                    com.example.bizflow.entity.Customer saved = customerRepository.save(created);
                    return ResponseEntity.ok(saved);
                });
    }

    private static String normalizePhone(String phone) {
        if (phone == null) return null;
        return phone.replaceAll("\\D", "");
    }

    private static class UpsertRequest {
        public String name;
        public String phone;
        public String email;
        public String address;
    }

    private static class UpsertByUserRequest {
        public Long userId;
        public String username;
        public String name;
        public String email;
        public String address;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static class PointAddRequest {
        public Long customerId;
        public BigDecimal totalAmount;
        public String reference;
    }

    private static class PointRedeemRequest {
        public Long customerId;
        public Integer points;
    }
}
