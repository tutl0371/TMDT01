package com.example.bizflow.controller;

import com.example.bizflow.entity.WarrantyClaim;
import com.example.bizflow.entity.WarrantyPolicy;
import com.example.bizflow.repository.WarrantyClaimRepository;
import com.example.bizflow.repository.WarrantyPolicyRepository;
import com.example.bizflow.repository.OrderRepository;
import com.example.bizflow.integration.CatalogClient;
import com.example.bizflow.integration.CustomerClient;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/warranties")
public class WarrantyController {

    private final WarrantyClaimRepository claimRepo;
    private final WarrantyPolicyRepository policyRepo;
    private final CatalogClient catalogClient;
    private final CustomerClient customerClient;
    private final OrderRepository orderRepo;

    public WarrantyController(WarrantyClaimRepository claimRepo,
                              WarrantyPolicyRepository policyRepo,
                              CatalogClient catalogClient,
                              CustomerClient customerClient,
                              OrderRepository orderRepo) {
        this.claimRepo = claimRepo;
        this.policyRepo = policyRepo;
        this.catalogClient = catalogClient;
        this.customerClient = customerClient;
        this.orderRepo = orderRepo;
    }

    // ========================
    // WARRANTY POLICIES
    // ========================

    @GetMapping("/policies")
    public ResponseEntity<List<WarrantyPolicy>> getPolicies(
            @RequestParam(required = false, defaultValue = "ACTIVE") String status) {
        List<WarrantyPolicy> policies = status == null ? policyRepo.findAll() : policyRepo.findByStatus(status);
        return ResponseEntity.ok(policies);
    }

    @PostMapping("/policies")
    public ResponseEntity<?> createPolicy(@RequestBody WarrantyPolicy policy) {
        if (policy.getName() == null || policy.getName().isBlank()) {
            return ResponseEntity.badRequest().body("Tên chính sách không được trống");
        }
        if (policy.getDurationMonths() == null || policy.getDurationMonths() <= 0) {
            return ResponseEntity.badRequest().body("Thời hạn bảo hành phải > 0");
        }
        policy.setCreatedAt(LocalDateTime.now());
        WarrantyPolicy saved = policyRepo.save(policy);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<?> updatePolicy(@PathVariable Long id, @RequestBody WarrantyPolicy update) {
        return policyRepo.findById(id).map(policy -> {
            if (update.getName() != null) policy.setName(update.getName());
            if (update.getDurationMonths() != null) policy.setDurationMonths(update.getDurationMonths());
            if (update.getDescription() != null) policy.setDescription(update.getDescription());
            if (update.getStatus() != null) policy.setStatus(update.getStatus());
            if (update.getApplicableCategoryId() != null) policy.setApplicableCategoryId(update.getApplicableCategoryId());
            if (update.getApplicableProductId() != null) policy.setApplicableProductId(update.getApplicableProductId());
            policy.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(policyRepo.save(policy));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<?> deletePolicy(@PathVariable Long id) {
        return policyRepo.findById(id).map(policy -> {
            policy.setStatus("INACTIVE");
            policy.setUpdatedAt(LocalDateTime.now());
            policyRepo.save(policy);
            return ResponseEntity.ok("Đã vô hiệu hóa chính sách");
        }).orElse(ResponseEntity.notFound().build());
    }

    // ========================
    // WARRANTY CLAIMS
    // ========================

    @GetMapping("/claims")
    public ResponseEntity<List<Map<String, Object>>> getClaims(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        LocalDateTime from = fromDate != null && !fromDate.isBlank()
                ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to = toDate != null && !toDate.isBlank()
                ? LocalDate.parse(toDate).atTime(LocalTime.MAX) : null;
        List<WarrantyClaim> claims = claimRepo.searchClaims(status, from, to);
        List<Map<String, Object>> result = claims.stream()
                .map(this::enrichClaim)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/claims/{id}")
    public ResponseEntity<?> getClaimById(@PathVariable Long id) {
        return claimRepo.findById(id)
                .map(claim -> ResponseEntity.ok(enrichClaim(claim)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/claims/search")
    public ResponseEntity<List<Map<String, Object>>> searchClaims(@RequestParam String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        String trimmed = keyword.trim();
        List<Long> customerIds = customerClient.searchCustomerIds(trimmed);
        if (customerIds == null || customerIds.isEmpty()) {
            customerIds = List.of(-1L);
        }
        List<WarrantyClaim> claims = claimRepo.searchByKeyword(trimmed, customerIds);
        List<Map<String, Object>> result = claims.stream()
                .map(this::enrichClaim)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/claims/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", claimRepo.countByStatus("PENDING"));
        stats.put("received", claimRepo.countByStatus("RECEIVED"));
        stats.put("processing", claimRepo.countByStatus("PROCESSING"));
        stats.put("completed", claimRepo.countByStatus("COMPLETED"));
        stats.put("rejected", claimRepo.countByStatus("REJECTED"));
        stats.put("total", claimRepo.count());
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/claims")
    @Transactional
    public ResponseEntity<?> createClaim(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body.get("orderId"));
        Long productId = toLong(body.get("productId"));
        if (orderId == null || productId == null) {
            return ResponseEntity.badRequest().body("orderId và productId là bắt buộc");
        }

        WarrantyClaim claim = new WarrantyClaim();
        claim.setOrderId(orderId);
        claim.setProductId(productId);
        claim.setOrderItemId(toLong(body.get("orderItemId")));
        claim.setCustomerId(toLong(body.get("customerId")));
        claim.setPolicyId(toLong(body.get("policyId")));
        claim.setIssueDescription((String) body.get("issueDescription"));
        claim.setCreatedBy(toLong(body.get("createdBy")));
        claim.setStatus("PENDING");

        // Calculate warranty dates
        LocalDate start = LocalDate.now();
        int months = 12; // default
        if (claim.getPolicyId() != null) {
            WarrantyPolicy policy = policyRepo.findById(claim.getPolicyId()).orElse(null);
            if (policy != null) {
                months = policy.getDurationMonths();
            }
        }
        claim.setWarrantyStart(start);
        claim.setWarrantyEnd(start.plusMonths(months));

        // Generate claim number: WR-yyyyMMdd-NNN
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = claimRepo.count() + 1;
        claim.setClaimNumber("WR-" + dateStr + "-" + String.format("%03d", count));

        WarrantyClaim saved = claimRepo.save(claim);
        return ResponseEntity.ok(enrichClaim(saved));
    }

    @PutMapping("/claims/{id}/status")
    @Transactional
    public ResponseEntity<?> updateClaimStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return claimRepo.findById(id).map(claim -> {
            String newStatus = (String) body.get("status");
            if (newStatus == null || newStatus.isBlank()) {
                return ResponseEntity.badRequest().body("status là bắt buộc");
            }
            claim.setStatus(newStatus.toUpperCase());
            if (body.get("resolution") != null) claim.setResolution((String) body.get("resolution"));
            if (body.get("resolutionNote") != null) claim.setResolutionNote((String) body.get("resolutionNote"));

            if ("RECEIVED".equalsIgnoreCase(newStatus)) {
                claim.setReceivedAt(LocalDateTime.now());
            }
            if ("COMPLETED".equalsIgnoreCase(newStatus) || "REJECTED".equalsIgnoreCase(newStatus)) {
                claim.setCompletedAt(LocalDateTime.now());
            }
            claim.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(enrichClaim(claimRepo.save(claim)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/check/{orderId}")
    public ResponseEntity<?> checkWarranty(@PathVariable Long orderId) {
        List<WarrantyClaim> claims = claimRepo.findByOrderId(orderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("claims", claims.stream().map(this::enrichClaim).collect(Collectors.toList()));
        result.put("hasActiveClaim", claims.stream().anyMatch(c ->
                !"COMPLETED".equals(c.getStatus()) && !"REJECTED".equals(c.getStatus())));
        return ResponseEntity.ok(result);
    }

    // ========================
    // HELPERS
    // ========================

    private Map<String, Object> enrichClaim(WarrantyClaim claim) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", claim.getId());
        map.put("claimNumber", claim.getClaimNumber());
        map.put("orderId", claim.getOrderId());
        map.put("orderItemId", claim.getOrderItemId());
        map.put("productId", claim.getProductId());
        map.put("customerId", claim.getCustomerId());
        map.put("policyId", claim.getPolicyId());
        map.put("warrantyStart", claim.getWarrantyStart());
        map.put("warrantyEnd", claim.getWarrantyEnd());
        map.put("issueDescription", claim.getIssueDescription());
        map.put("status", claim.getStatus());
        map.put("resolution", claim.getResolution());
        map.put("resolutionNote", claim.getResolutionNote());
        map.put("receivedAt", claim.getReceivedAt());
        map.put("completedAt", claim.getCompletedAt());
        map.put("createdBy", claim.getCreatedBy());
        map.put("createdAt", claim.getCreatedAt());
        map.put("updatedAt", claim.getUpdatedAt());

        // Enrich product info
        try {
            CatalogClient.ProductSnapshot product = catalogClient.getProduct(claim.getProductId());
            if (product != null) {
                map.put("productName", product.getName());
                map.put("productSku", product.getCode());
            }
        } catch (Exception ignored) {}

        // Enrich customer info
        if (claim.getCustomerId() != null) {
            try {
                CustomerClient.CustomerSnapshot customer = customerClient.getCustomer(claim.getCustomerId());
                if (customer != null) {
                    map.put("customerName", customer.getName());
                    map.put("customerPhone", customer.getPhone());
                }
            } catch (Exception ignored) {}
        }

        // Enrich order info
        if (claim.getOrderId() != null) {
            orderRepo.findById(claim.getOrderId()).ifPresent(order -> {
                map.put("invoiceNumber", order.getInvoiceNumber());
            });
        }

        // Check if warranty is still valid
        if (claim.getWarrantyEnd() != null) {
            map.put("isWarrantyValid", !LocalDate.now().isAfter(claim.getWarrantyEnd()));
        }

        return map;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
    }
}
