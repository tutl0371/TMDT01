package com.example.bizflow.controller;

import com.example.bizflow.entity.Customer;
import com.example.bizflow.repository.CustomerRepository;
import com.example.bizflow.integration.SalesClient;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final SalesClient salesClient;
    @Value("${app.jwt.secret:my-secret-key-for-jwt-token-generation-and-verification}")
    private String jwtSecret;

    public CustomerController(CustomerRepository customerRepository,
            SalesClient salesClient) {
        this.customerRepository = customerRepository;
        this.salesClient = salesClient;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'OWNER', 'ADMIN')")
    public ResponseEntity<?> getAllCustomers() {
        try {
            List<Customer> customers = customerRepository.findAll();
            return ResponseEntity.ok(customers);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error fetching customers: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'OWNER', 'ADMIN')")
    public ResponseEntity<?> getCustomerById(@PathVariable @NonNull Long id) {
        try {
            return customerRepository.findById(id)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error fetching customer: " + e.getMessage());
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'OWNER', 'ADMIN')")
    public ResponseEntity<?> createCustomer(@RequestBody @NonNull CustomerCreateRequest request) {
        try {
            String name = trimToNull(request.name);
            String phone = trimToNull(request.phone);
            if (name == null) {
                return ResponseEntity.badRequest().body("Name is required.");
            }
            if (phone == null) {
                return ResponseEntity.badRequest().body("Phone is required.");
            }

            Customer customer = new Customer(name, phone);
            customer.setEmail(trimToNull(request.email));
            customer.setAddress(trimToNull(request.address));
            customer.setCccd(trimToNull(request.cccd));
            customer.setGender(trimToNull(request.gender));
            if (trimToNull(request.dob) != null) {
                customer.setDob(LocalDate.parse(request.dob.trim()));
            }

            Customer saved = customerRepository.save(customer);
            return ResponseEntity.ok(saved);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body("Invalid dob format. Use yyyy-MM-dd.");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error creating customer: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/orders")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'OWNER', 'ADMIN')")
    public ResponseEntity<Object> getCustomerOrderHistory(@PathVariable @NonNull Long id) {
        return ResponseEntity.ok(salesClient.getCustomerOrderHistory(id));
    }

    @GetMapping("/by-user/{userId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getCustomerByUserId(@PathVariable @NonNull Long userId, HttpServletRequest request) {
        try {
            // If the caller is a CUSTOMER role, allow only when the requested userId matches the JWT's userId.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean isCustomer = auth != null && auth.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_CUSTOMER".equalsIgnoreCase(a.getAuthority()));
            if (isCustomer) {
                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
                }
                String token = authHeader.substring(7);
                try {
                    Claims claims = Jwts.parserBuilder()
                            .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                            .build()
                            .parseClaimsJws(token)
                            .getBody();
                    Number tokenUserIdNum = claims.get("userId", Number.class);
                    Long tokenUserId = tokenUserIdNum == null ? null : tokenUserIdNum.longValue();
                    if (tokenUserId == null || !tokenUserId.equals(userId)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
                    }
                } catch (Exception ex) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
                }
            }

            return customerRepository.findByUserId(userId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error fetching customer by userId: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'OWNER', 'ADMIN')")
    public ResponseEntity<?> updateCustomer(@PathVariable @NonNull Long id, @RequestBody @NonNull CustomerUpdateRequest request) {
        try {
            return customerRepository.findById(id)
                    .map(customer -> {
                        // Update basic info
                        if (request.name != null && !request.name.trim().isEmpty()) {
                            customer.setName(request.name.trim());
                        }
                        if (request.phone != null && !request.phone.trim().isEmpty()) {
                            customer.setPhone(request.phone.trim());
                        }
                        
                        // Update optional fields
                        customer.setEmail(trimToNull(request.email));
                        customer.setAddress(trimToNull(request.address));
                        customer.setCccd(trimToNull(request.cccd));
                        customer.setGender(trimToNull(request.gender));
                        
                        // Update date of birth
                        if (trimToNull(request.dob) != null) {
                            try {
                                customer.setDob(LocalDate.parse(request.dob.trim()));
                            } catch (Exception e) {
                                // Keep existing date if parse fails
                            }
                        }
                        
                        Customer updated = customerRepository.save(customer);
                        return ResponseEntity.ok(updated);
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error updating customer: " + e.getMessage());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static class CustomerCreateRequest {

        public String name;
        public String phone;
        public String email;
        public String address;
        public String cccd;
        public String dob;
        public String gender;
    }

    private static class CustomerUpdateRequest {

        public String name;
        public String phone;
        public String email;
        public String address;
        public String cccd;
        public String dob;
        public String gender;
    }
}
