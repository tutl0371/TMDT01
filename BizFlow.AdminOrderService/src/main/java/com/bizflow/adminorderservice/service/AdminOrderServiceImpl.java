package com.bizflow.adminorderservice.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bizflow.adminorderservice.dto.OrderDetailDto;
import com.bizflow.adminorderservice.dto.OrderItemDto;
import com.bizflow.adminorderservice.dto.OrderPaymentDto;
import com.bizflow.adminorderservice.dto.OrderSummaryDto;
import com.bizflow.adminorderservice.entity.OrderRecord;
import com.bizflow.adminorderservice.exception.OrderNotFoundException;
import com.bizflow.adminorderservice.repository.OrderRecordRepository;
import com.bizflow.adminorderservice.request.OrderStatusUpdateRequest;
import com.bizflow.event.PurchaseEvent;
import com.bizflow.producer.OrderEventProducer;

@Service
public class AdminOrderServiceImpl implements AdminOrderService {

    private static final Logger logger = LoggerFactory.getLogger(AdminOrderServiceImpl.class);
    private final OrderRecordRepository orderRecordRepository;
    private final OrderEventProducer orderEventProducer;
    private final JdbcTemplate jdbcTemplate;

    public AdminOrderServiceImpl(OrderRecordRepository orderRecordRepository,
                                ObjectProvider<OrderEventProducer> orderEventProducerProvider,
                                JdbcTemplate jdbcTemplate) {
        this.orderRecordRepository = orderRecordRepository;
        this.orderEventProducer = orderEventProducerProvider.getIfAvailable();
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderSummaryDto> listOrders(String status, String query) {
        return orderRecordRepository
            .searchOrders(status, query, PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "createdAt")))
            .getContent()
            .stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

        @Override
        @Transactional(readOnly = true)
        public Page<OrderSummaryDto> listOrdersPage(String status, String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);

        return orderRecordRepository
            .searchOrders(status, query, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")))
            .map(this::toDto);
        }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailDto getOrder(Long id) {
        try {
            // Direct SQL from MySQL container (cross-schema joins supported).
            Map<String, Object> orderRow = jdbcTemplate.queryForMap(
                    "SELECT o.id, o.invoice_number, o.status, o.total_amount, o.created_at, " +
                            "o.customer_id, o.user_id, o.customer_name, o.note, o.order_type, o.is_return, " +
                            "o.return_reason, o.return_note, o.refund_method, o.parent_order_id " +
                            "FROM bizflow_sales_db.orders o WHERE o.id = ?",
                    id
            );

            OrderDetailDto dto = new OrderDetailDto();
            dto.setId(((Number) orderRow.get("id")).longValue());
            dto.setInvoiceNumber((String) orderRow.get("invoice_number"));
            dto.setStatus((String) orderRow.get("status"));
            dto.setTotalAmount(orderRow.get("total_amount") == null ? null : ((Number) orderRow.get("total_amount")).doubleValue());
            dto.setCreatedAt(toInstant(orderRow.get("created_at")));

            dto.setCustomerId(toLong(orderRow.get("customer_id")));
            dto.setUserId(toLong(orderRow.get("user_id")));
            dto.setCustomerName((String) orderRow.get("customer_name"));
            dto.setNote((String) orderRow.get("note"));
            dto.setOrderType((String) orderRow.get("order_type"));
            dto.setIsReturn(toBoolean(orderRow.get("is_return")));
            dto.setReturnReason((String) orderRow.get("return_reason"));
            dto.setReturnNote((String) orderRow.get("return_note"));
            dto.setRefundMethod((String) orderRow.get("refund_method"));
            dto.setParentOrderId(toLong(orderRow.get("parent_order_id")));

            // Items + product info from catalog DB
            List<OrderItemDto> items = jdbcTemplate.query(
                    "SELECT oi.id, oi.product_id, oi.quantity, oi.price, " +
                            "p.product_name, p.sku, p.unit " +
                            "FROM bizflow_sales_db.order_items oi " +
                            "LEFT JOIN bizflow_catalog_db.products p ON p.product_id = oi.product_id " +
                            "WHERE oi.order_id = ? ORDER BY oi.id",
                    (rs, rowNum) -> {
                        OrderItemDto item = new OrderItemDto();
                        item.setId(rs.getLong("id"));
                        long productId = rs.getLong("product_id");
                        item.setProductId(rs.wasNull() ? null : productId);
                        item.setQuantity(rs.getInt("quantity"));
                        item.setPrice(rs.getDouble("price"));
                        item.setProductName(rs.getString("product_name"));
                        item.setSku(rs.getString("sku"));
                        item.setUnit(rs.getString("unit"));

                        Double lineTotal = null;
                        if (!rs.wasNull()) {
                            lineTotal = rs.getDouble("price") * rs.getInt("quantity");
                        }
                        item.setLineTotal(lineTotal);
                        return item;
                    },
                    id
            );
            dto.setItems(items);

            // Payments
            List<OrderPaymentDto> payments = jdbcTemplate.query(
                    "SELECT p.id, p.amount, p.method, p.paid_at, p.status, p.token " +
                            "FROM bizflow_sales_db.payments p WHERE p.order_id = ? ORDER BY p.id",
                    (rs, rowNum) -> {
                        OrderPaymentDto payment = new OrderPaymentDto();
                        payment.setId(rs.getLong("id"));
                        payment.setAmount(rs.getDouble("amount"));
                        payment.setMethod(rs.getString("method"));
                        payment.setStatus(rs.getString("status"));
                        payment.setToken(rs.getString("token"));
                        payment.setPaidAt(toInstant(rs.getTimestamp("paid_at")));
                        return payment;
                    },
                    id
            );
            dto.setPayments(payments);

            // Optional customer details (if customer_id exists)
            if (dto.getCustomerId() != null) {
                try {
                    Map<String, Object> customerRow = jdbcTemplate.queryForMap(
                            "SELECT c.id, c.name, c.phone, c.email FROM bizflow_customer_db.customers c WHERE c.id = ?",
                            dto.getCustomerId()
                    );
                    if (dto.getCustomerName() == null || dto.getCustomerName().isBlank()) {
                        dto.setCustomerName((String) customerRow.get("name"));
                    }
                    dto.setCustomerPhone((String) customerRow.get("phone"));
                    dto.setCustomerEmail((String) customerRow.get("email"));
                } catch (EmptyResultDataAccessException ignored) {
                    // leave as-is
                }
            }

            // Optional user details (if user_id exists)
            if (dto.getUserId() != null) {
                try {
                    Map<String, Object> userRow = jdbcTemplate.queryForMap(
                            "SELECT u.id, u.username, u.full_name FROM bizflow_auth_db.users u WHERE u.id = ?",
                            dto.getUserId()
                    );
                    dto.setUsername((String) userRow.get("username"));
                    dto.setUserFullName((String) userRow.get("full_name"));
                } catch (EmptyResultDataAccessException ignored) {
                    // leave as-is
                }
            }

            return dto;
        } catch (EmptyResultDataAccessException e) {
            throw new OrderNotFoundException(id);
        }
    }

    @Override
    @Transactional
    public OrderSummaryDto updateOrderStatus(Long id, OrderStatusUpdateRequest request) {
        OrderRecord record = orderRecordRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        record.setStatus(request.getStatus().toUpperCase(Locale.ROOT));
        OrderRecord updatedRecord = orderRecordRepository.save(record);
        
        // Publish event to Kafka (optional)
        if (orderEventProducer != null) {
            try {
            PurchaseEvent event = new PurchaseEvent();
            event.setOrderId(updatedRecord.getId());
            // Note: customerId is extracted from invoice number pattern or order history
            event.setInvoiceNumber(updatedRecord.getInvoiceNumber());
            if (updatedRecord.getTotalAmount() != null) {
                event.setTotalAmount(new java.math.BigDecimal(updatedRecord.getTotalAmount()));
            }
            event.setStatus(updatedRecord.getStatus());
            if (updatedRecord.getCreatedAt() != null) {
                event.setCreatedAt(java.time.LocalDateTime.ofInstant(updatedRecord.getCreatedAt(), java.time.ZoneId.systemDefault()));
            }
            
            orderEventProducer.publishPurchaseEvent(event);
            logger.info("Published purchase event for order {} with status {}", id, updatedRecord.getStatus());
            } catch (Exception e) {
                logger.warn("Failed to publish Kafka event for order {}", id, e);
                // Don't fail the order status update if Kafka fails
            }
        }
        
        return toDto(updatedRecord);
    }

    private OrderSummaryDto toDto(OrderRecord record) {
        return new OrderSummaryDto(
                record.getId(),
                record.getInvoiceNumber(),
                record.getStatus(),
                record.getCustomerName(),
                record.getTotalAmount(),
                record.getCreatedAt()
        );
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean toBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        // MySQL BIT(1) often comes back as byte[] or numeric.
        if (value instanceof Number n) return n.intValue() != 0;
        if (value instanceof byte[] bytes) {
            return bytes.length > 0 && bytes[0] != 0;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        return s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("t");
    }

    private static java.time.Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof java.time.Instant i) return i;
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.time.LocalDateTime ldt) {
            // MySQL DATETIME has no timezone; treat it as UTC to match other services config.
            return ldt.toInstant(java.time.ZoneOffset.UTC);
        }
        return null;
    }
}
