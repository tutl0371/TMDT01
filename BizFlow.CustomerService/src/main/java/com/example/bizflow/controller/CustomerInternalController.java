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
        return ResponseEntity.ok(customerRepository.searchCustomerIds(keyword.trim()));
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
