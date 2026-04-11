package com.example.bizflow.controller;

import com.example.bizflow.entity.Shipment;
import com.example.bizflow.entity.ShipmentTracking;
import com.example.bizflow.repository.ShipmentRepository;
import com.example.bizflow.repository.ShipmentTrackingRepository;
import com.example.bizflow.integration.CustomerClient;
import com.example.bizflow.repository.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentRepository shipmentRepo;
    private final ShipmentTrackingRepository trackingRepo;
    private final CustomerClient customerClient;
    private final OrderRepository orderRepo;

    public ShipmentController(ShipmentRepository shipmentRepo,
                              ShipmentTrackingRepository trackingRepo,
                              CustomerClient customerClient,
                              OrderRepository orderRepo) {
        this.shipmentRepo = shipmentRepo;
        this.trackingRepo = trackingRepo;
        this.customerClient = customerClient;
        this.orderRepo = orderRepo;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getShipments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        LocalDateTime from = fromDate != null && !fromDate.isBlank()
                ? LocalDate.parse(fromDate).atStartOfDay() : null;
        LocalDateTime to = toDate != null && !toDate.isBlank()
                ? LocalDate.parse(toDate).atTime(LocalTime.MAX) : null;
        List<Shipment> shipments = shipmentRepo.searchShipments(status, from, to);
        List<Map<String, Object>> result = shipments.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getShipmentById(@PathVariable Long id) {
        return shipmentRepo.findById(id)
                .map(shipment -> {
                    Map<String, Object> response = toResponse(shipment);
                    // Include full tracking history
                    List<ShipmentTracking> tracking = trackingRepo.findByShipmentIdOrderByCreatedAtDesc(id);
                    response.put("trackingHistory", tracking.stream().map(t -> {
                        Map<String, Object> tm = new LinkedHashMap<>();
                        tm.put("id", t.getId());
                        tm.put("status", t.getStatus());
                        tm.put("location", t.getLocation());
                        tm.put("note", t.getNote());
                        tm.put("updatedBy", t.getUpdatedBy());
                        tm.put("createdAt", t.getCreatedAt());
                        return tm;
                    }).collect(Collectors.toList()));
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchShipments(
            @RequestParam String keyword,
            @RequestParam(required = false) Long customerId) {
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        List<Shipment> shipments = shipmentRepo.searchByKeyword(keyword.trim());
        
        // BẢO MẬT: Nếu là khách hàng (có customerId), chỉ trả về đơn thuộc về họ hoặc họ tạo ra
        if (customerId != null) {
            shipments = shipments.stream()
                    .filter(s -> customerId.equals(s.getCustomerId()) || customerId.equals(s.getCreatedBy()))
                    .collect(Collectors.toList());
        }
        
        List<Map<String, Object>> result = shipments.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<Map<String, Object>>> getByOrder(@PathVariable Long orderId) {
        List<Shipment> shipments = shipmentRepo.findByOrderId(orderId);
        List<Map<String, Object>> result = shipments.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Map<String, Object>>> getByCustomer(@PathVariable Long customerId) {
        List<Shipment> shipments = shipmentRepo.findByCustomerId(customerId);
        List<Map<String, Object>> result = shipments.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", shipmentRepo.countByStatus("PENDING"));
        stats.put("confirmed", shipmentRepo.countByStatus("CONFIRMED"));
        stats.put("pickedUp", shipmentRepo.countByStatus("PICKED_UP"));
        stats.put("inTransit", shipmentRepo.countByStatus("IN_TRANSIT"));
        stats.put("delivered", shipmentRepo.countByStatus("DELIVERED"));
        stats.put("failed", shipmentRepo.countByStatus("FAILED"));
        stats.put("cancelled", shipmentRepo.countByStatus("CANCELLED"));
        stats.put("total", shipmentRepo.count());
        return ResponseEntity.ok(stats);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createShipment(@RequestBody Map<String, Object> body) {
        Long orderId = toLong(body.get("orderId"));
        String address = (String) body.get("receiverAddress");
        if (orderId == null) {
            return ResponseEntity.badRequest().body("orderId là bắt buộc");
        }
        if (address == null || address.isBlank()) {
            return ResponseEntity.badRequest().body("Địa chỉ giao hàng là bắt buộc");
        }

        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setCustomerId(toLong(body.get("customerId")));
        shipment.setReceiverName((String) body.get("receiverName"));
        shipment.setReceiverPhone((String) body.get("receiverPhone"));
        shipment.setReceiverAddress(address);
        shipment.setShippingMethod((String) body.getOrDefault("shippingMethod", "STORE_DELIVERY"));
        shipment.setNote((String) body.get("note"));
        shipment.setCreatedBy(toLong(body.get("createdBy")));
        shipment.setStatus("PENDING");

        if (body.get("shippingFee") != null) {
            shipment.setShippingFee(new BigDecimal(body.get("shippingFee").toString()));
        }
        if (body.get("estimatedDelivery") != null) {
            shipment.setEstimatedDelivery(LocalDate.parse(body.get("estimatedDelivery").toString()));
        } else {
            // Default: 3 ngày sau
            shipment.setEstimatedDelivery(LocalDate.now().plusDays(3));
        }

        // Generate shipment number: SH-yyyyMMdd-NNN
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = shipmentRepo.count() + 1;
        shipment.setShipmentNumber("SH-" + dateStr + "-" + String.format("%03d", count));

        Shipment saved = shipmentRepo.save(shipment);

        // Create initial tracking entry
        ShipmentTracking tracking = new ShipmentTracking();
        tracking.setShipment(saved);
        tracking.setStatus("PENDING");
        tracking.setNote("Đơn hàng đã được tạo, chờ xác nhận");
        tracking.setUpdatedBy(shipment.getCreatedBy());
        trackingRepo.save(tracking);

        return ResponseEntity.ok(toResponse(saved));
    }

    @PutMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return shipmentRepo.findById(id).map(shipment -> {
            String newStatus = (String) body.get("status");
            if (newStatus == null || newStatus.isBlank()) {
                return ResponseEntity.badRequest().body("status là bắt buộc");
            }
            newStatus = newStatus.toUpperCase();
            shipment.setStatus(newStatus);
            shipment.setUpdatedAt(LocalDateTime.now());

            if ("DELIVERED".equals(newStatus)) {
                shipment.setActualDelivery(LocalDateTime.now());
            }

            if (body.get("trackingCode") != null) {
                shipment.setTrackingCode((String) body.get("trackingCode"));
            }

            shipmentRepo.save(shipment);

            // Add tracking entry
            ShipmentTracking tracking = new ShipmentTracking();
            tracking.setShipment(shipment);
            tracking.setStatus(newStatus);
            tracking.setLocation((String) body.get("location"));
            tracking.setNote((String) body.getOrDefault("note", getStatusNote(newStatus)));
            tracking.setUpdatedBy(toLong(body.get("updatedBy")));
            trackingRepo.save(tracking);

            return ResponseEntity.ok(toResponse(shipment));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/tracking")
    @Transactional
    public ResponseEntity<?> addTracking(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return shipmentRepo.findById(id).map(shipment -> {
            ShipmentTracking tracking = new ShipmentTracking();
            tracking.setShipment(shipment);
            tracking.setStatus((String) body.getOrDefault("status", shipment.getStatus()));
            tracking.setLocation((String) body.get("location"));
            tracking.setNote((String) body.get("note"));
            tracking.setUpdatedBy(toLong(body.get("updatedBy")));
            trackingRepo.save(tracking);
            return ResponseEntity.ok("Đã cập nhật tracking");
        }).orElse(ResponseEntity.notFound().build());
    }

    // ========================
    // HELPERS
    // ========================

    private Map<String, Object> toResponse(Shipment s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("shipmentNumber", s.getShipmentNumber());
        map.put("orderId", s.getOrderId());
        map.put("customerId", s.getCustomerId());
        map.put("receiverName", s.getReceiverName());
        map.put("receiverPhone", s.getReceiverPhone());
        map.put("receiverAddress", s.getReceiverAddress());
        map.put("shippingMethod", s.getShippingMethod());
        map.put("shippingFee", s.getShippingFee());
        map.put("estimatedDelivery", s.getEstimatedDelivery());
        map.put("actualDelivery", s.getActualDelivery());
        map.put("status", s.getStatus());
        map.put("trackingCode", s.getTrackingCode());
        map.put("note", s.getNote());
        map.put("createdBy", s.getCreatedBy());
        map.put("createdAt", s.getCreatedAt());
        map.put("updatedAt", s.getUpdatedAt());

        // Enrich customer info
        if (s.getCustomerId() != null) {
            try {
                CustomerClient.CustomerSnapshot customer = customerClient.getCustomer(s.getCustomerId());
                if (customer != null) {
                    map.put("customerName", customer.getName());
                    map.put("customerPhone", customer.getPhone());
                }
            } catch (Exception ignored) {}
        }

        // Enrich order info
        if (s.getOrderId() != null) {
            orderRepo.findById(s.getOrderId()).ifPresent(order -> {
                map.put("invoiceNumber", order.getInvoiceNumber());
            });
        }

        // Shopee-style status label
        map.put("statusLabel", getStatusLabel(s.getStatus()));
        map.put("shippingMethodLabel", getMethodLabel(s.getShippingMethod()));

        return map;
    }

    private String getStatusLabel(String status) {
        if (status == null) return "Không rõ";
        return switch (status) {
            case "PENDING" -> "Chờ xác nhận";
            case "CONFIRMED" -> "Đã xác nhận";
            case "PICKED_UP" -> "Đã lấy hàng";
            case "IN_TRANSIT" -> "Đang giao hàng";
            case "DELIVERED" -> "Đã giao hàng";
            case "FAILED" -> "Giao thất bại";
            case "CANCELLED" -> "Đã hủy";
            case "RETURNING" -> "Đang hoàn hàng";
            default -> status;
        };
    }

    private String getMethodLabel(String method) {
        if (method == null) return "Không rõ";
        return switch (method) {
            case "STORE_DELIVERY" -> "Cửa hàng giao";
            case "GHN" -> "Giao Hàng Nhanh";
            case "GHTK" -> "Giao Hàng Tiết Kiệm";
            case "VIETTEL_POST" -> "Viettel Post";
            case "SELF_PICKUP" -> "Nhận tại cửa hàng";
            default -> method;
        };
    }

    private String getStatusNote(String status) {
        return switch (status) {
            case "CONFIRMED" -> "Đơn hàng đã được xác nhận";
            case "PICKED_UP" -> "Shipper đã lấy hàng";
            case "IN_TRANSIT" -> "Đang vận chuyển đến người nhận";
            case "DELIVERED" -> "Đã giao hàng thành công";
            case "FAILED" -> "Giao hàng thất bại";
            case "CANCELLED" -> "Đơn hàng đã bị hủy";
            case "RETURNING" -> "Đang hoàn hàng về kho";
            default -> "Cập nhật trạng thái: " + status;
        };
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
    }
}
