package com.example.bizflow.controller;

import com.example.bizflow.dto.CreateOrderRequest;
import com.example.bizflow.dto.CreateOrderResponse;
import com.example.bizflow.dto.CartStateRequest;
import com.example.bizflow.dto.CartStateResponse;
import com.example.bizflow.dto.OrderItemRequest;
import com.example.bizflow.dto.OrderItemResponse;
import com.example.bizflow.dto.OrderMessage;
import com.example.bizflow.dto.OrderResponse;
import com.example.bizflow.dto.OrderSummaryResponse;
import com.example.bizflow.dto.PayOrderRequest;
import com.example.bizflow.entity.CustomerTier;
import com.example.bizflow.entity.Order;
import com.example.bizflow.entity.OrderItem;
import com.example.bizflow.entity.Payment;
import com.example.bizflow.integration.CatalogClient;
import com.example.bizflow.integration.CustomerClient;
import com.example.bizflow.integration.InventoryClient;
import com.example.bizflow.integration.PromotionClient;
import com.example.bizflow.integration.UserClient;
import com.example.bizflow.repository.OrderItemRepository;
import com.example.bizflow.repository.OrderRepository;
import com.example.bizflow.repository.PaymentRepository;
import com.example.bizflow.service.OrderMessageProducer;
import com.example.bizflow.service.OrderService;
import com.example.bizflow.service.UserCartService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final InventoryClient inventoryClient;
    private final CatalogClient catalogClient;
    private final CustomerClient customerClient;
    private final UserClient userClient;
    private final PromotionClient promotionClient;
    private final OrderMessageProducer orderMessageProducer;
    private final UserCartService userCartService;

    public OrderController(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PaymentRepository paymentRepository,
            OrderService orderService,
            InventoryClient inventoryClient,
            CatalogClient catalogClient,
            CustomerClient customerClient,
            UserClient userClient,
            PromotionClient promotionClient,
                OrderMessageProducer orderMessageProducer,
                UserCartService userCartService
    ) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.orderService = orderService;
        this.inventoryClient = inventoryClient;
        this.catalogClient = catalogClient;
        this.customerClient = customerClient;
        this.userClient = userClient;
        this.promotionClient = promotionClient;
        this.orderMessageProducer = orderMessageProducer;
        this.userCartService = userCartService;
    }

    @GetMapping("/cart/{userId}")
    public ResponseEntity<?> getUserCart(@PathVariable("userId") Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest().body("User id is required");
        }

        UserClient.UserSnapshot user = userClient.getUser(userId);
        if (user == null) {
            return ResponseEntity.badRequest().body("User not found");
        }

        return userCartService.getCartState(userId)
                .<ResponseEntity<?>>map(snapshot -> ResponseEntity.ok(new CartStateResponse(
                        snapshot.getUserId(),
                        snapshot.getUsername(),
                        snapshot.getState(),
                        snapshot.getUpdatedAt()
                )))
                .orElseGet(() -> ResponseEntity.ok(new CartStateResponse(
                        userId,
                        resolveUserName(user),
                        null,
                        null
                )));
    }

    @PostMapping("/cart/{userId}")
    public ResponseEntity<?> saveUserCart(@PathVariable("userId") Long userId,
                                          @RequestBody(required = false) CartStateRequest request) {
        if (userId == null) {
            return ResponseEntity.badRequest().body("User id is required");
        }

        UserClient.UserSnapshot user = userClient.getUser(userId);
        if (user == null) {
            return ResponseEntity.badRequest().body("User not found");
        }

        Object state = request == null ? null : request.getState();
        UserCartService.UserCartSnapshot snapshot = userCartService.saveCartState(userId, resolveUserName(user), state);

        return ResponseEntity.ok(new CartStateResponse(
                snapshot.getUserId(),
                snapshot.getUsername(),
                snapshot.getState(),
                snapshot.getUpdatedAt()
        ));
    }

    @PostMapping("/{id}/pay")
    @Transactional
    public ResponseEntity<?> payOrder(@PathVariable("id") Long orderId,
                                      @RequestBody(required = false) PayOrderRequest request) {
        if (orderId == null) {
            return ResponseEntity.badRequest().body("Order id is required");
        }

        Order order = orderRepository.findByIdWithDetails(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        if ("PAID".equalsIgnoreCase(order.getStatus())) {
            return ResponseEntity.ok("Order already paid");
        }

        order.setStatus("PAID");
        orderRepository.save(order);

        inventoryClient.applySale(toSaleItems(order.getItems()), order.getId(), order.getUserId());

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setMethod(request == null || request.getMethod() == null ? "TRANSFER" : request.getMethod());
        payment.setAmount(order.getTotalAmount());
        payment.setStatus("PAID");
        payment.setPaidAt(LocalDateTime.now());
        if (request != null && request.getToken() != null && !request.getToken().isBlank()) {
            payment.setToken(request.getToken().trim());
        }
        paymentRepository.save(payment);

        if (order.getCustomerId() != null) {
            customerClient.addPoints(
                    order.getCustomerId(),
                    order.getTotalAmount(),
                    "ORDER_" + order.getId()
            );
        }

        return ResponseEntity.ok("Payment confirmed");
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {

        if (request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body("Order items are required");
        }

        Long userId = request.getUserId();
        UserClient.UserSnapshot user = userClient.getUser(userId);

        Long customerId = request.getCustomerId();
        CustomerClient.CustomerSnapshot customer = customerClient.getCustomer(customerId);

        boolean paid = Boolean.TRUE.equals(request.getPaid());

        Order order = new Order();
        order.setUserId(userId);
        order.setCustomerId(customerId);
        order.setStatus(paid ? "PAID" : "UNPAID");
        order.setInvoiceNumber(orderService.generateInvoiceNumberForDate(LocalDate.now()));
        order.setReturnOrder(Boolean.TRUE.equals(request.getReturnOrder()));
        order.setOrderType(request.getOrderType());
        order.setRefundMethod(request.getRefundMethod());
        order.setReturnReason(request.getReturnReason());
        order.setReturnNote(request.getReturnNote());
        if (request.getOriginalOrderId() != null) {
            orderRepository.findById(request.getOriginalOrderId())
                    .ifPresent(order::setParentOrder);
        }

        Set<Long> productIds = request.getItems().stream()
                .map(OrderItemRequest::getProductId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<Long, CatalogClient.ProductSnapshot> products = new HashMap<>();
        for (Long productId : productIds) {
            CatalogClient.ProductSnapshot snapshot = catalogClient.getProduct(productId);
            if (snapshot != null) {
                products.put(productId, snapshot);
            }
        }

        Map<Long, Integer> stockByProduct = new HashMap<>();
        if (paid && !productIds.isEmpty()) {
            for (InventoryClient.StockItem stockItem : inventoryClient.getStocks(new ArrayList<>(productIds))) {
                if (stockItem != null && stockItem.getProductId() != null) {
                    Integer stock = stockItem.getStock();
                    stockByProduct.put(stockItem.getProductId(), stock != null ? stock : 0);
                }
            }
        }

        List<PromotionClient.CartItem> promoItems = request.getItems().stream()
                .map(item -> {
                    CatalogClient.ProductSnapshot product = products.get(item.getProductId());
                    if (product == null) {
                        return null;
                    }
                    int promoQty = Math.abs(item.getQuantity());
                    if (promoQty <= 0) {
                        return null;
                    }
                    PromotionClient.CartItem cartItem = new PromotionClient.CartItem();
                    cartItem.setProductId(product.getId());
                    cartItem.setCategoryId(product.getCategoryId());
                    cartItem.setBasePrice(product.getPrice());
                    cartItem.setQuantity(promoQty);
                    return cartItem;
                })
                .filter(item -> item != null)
                .collect(Collectors.toList());

        Map<Long, PromotionClient.CartItemPriceResponse> promoPrices = promotionClient.calculatePrices(promoItems);

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();

        boolean allowNegativeQty = "EXCHANGE".equalsIgnoreCase(request.getOrderType())
                || "RETURN".equalsIgnoreCase(request.getOrderType());

        for (OrderItemRequest req : request.getItems()) {

            Long productId = req.getProductId();
            if (productId == null) {
                return ResponseEntity.badRequest().body("Product is required");
            }

            CatalogClient.ProductSnapshot product = products.get(productId);
            if (product == null) {
                return ResponseEntity.badRequest().body("Product not found");
            }

            int qty = req.getQuantity();
            if (qty == 0) {
                return ResponseEntity.badRequest().body("Quantity must be > 0");
            }
            if (qty < 0 && !allowNegativeQty) {
                return ResponseEntity.badRequest().body("Quantity must be > 0");
            }

            if (paid && qty > 0) {
                int currentStock = stockByProduct.getOrDefault(productId, inventoryClient.getStock(productId));
                if (currentStock < qty) {
                    return ResponseEntity.badRequest().body("Insufficient stock for product: " + product.getName());
                }
            }

            PromotionClient.CartItemPriceResponse priceInfo = promoPrices.get(productId);
            BigDecimal unitPrice = priceInfo == null ? product.getPrice() : priceInfo.getFinalPrice();
            if (unitPrice == null) {
                unitPrice = product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
            }

            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty));
            total = total.add(lineTotal);

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(productId);
            item.setQuantity(qty);
            item.setPrice(unitPrice);
            items.add(item);
        }

        int pointsUsed = 0;
        if (paid && customer != null && Boolean.TRUE.equals(request.getUsePoints())) {
            DiscountResult result = resolveMemberDiscount(customer, total);
            BigDecimal memberDiscount = result.discount;
            pointsUsed = result.pointsUsed;
            total = total.subtract(memberDiscount).max(BigDecimal.ZERO);
        }

        order.setTotalAmount(total);
        Order savedOrder = orderRepository.save(order);

        items.forEach(i -> i.setOrder(savedOrder));
        orderItemRepository.saveAll(items);

        if (paid) {
            inventoryClient.applySale(toSaleItems(items), savedOrder.getId(), userId);

            Payment payment = new Payment();
            payment.setOrder(savedOrder);
            payment.setMethod(request.getPaymentMethod() == null ? "CASH" : request.getPaymentMethod());
            payment.setAmount(total);
            payment.setStatus("PAID");
            payment.setPaidAt(LocalDateTime.now());
            paymentRepository.save(payment);

            if (customer != null) {
                if (pointsUsed > 0) {
                    customerClient.redeemPoints(customer.getId(), pointsUsed);
                }
                customerClient.addPoints(
                        customer.getId(),
                        total,
                        "ORDER_" + savedOrder.getId()
                );
            }
        }

        try {
            Map<Long, CatalogClient.ProductSnapshot> productLookup = buildProductCache(items);
            OrderMessage orderMessage = new OrderMessage(
                    savedOrder.getId(),
                    savedOrder.getInvoiceNumber(),
                    customer != null ? customer.getId() : null,
                    customer != null ? customer.getName() : "Khach le",
                    customer != null ? customer.getPhone() : "-",
                    total,
                    items.size(),
                    user != null ? resolveUserName(user) : "system",
                    LocalDateTime.now(),
                    items.stream()
                            .map(item -> {
                                CatalogClient.ProductSnapshot product = productLookup.get(item.getProductId());
                                return new OrderMessage.OrderItemMessage(
                                        item.getProductId(),
                                        product == null ? null : product.getName(),
                                        item.getQuantity(),
                                        item.getPrice()
                                );
                            })
                            .collect(Collectors.toList())
            );

            orderMessageProducer.sendOrderCreatedMessage(orderMessage);
        } catch (Exception e) {
            System.err.println("Failed to send RabbitMQ message: " + e.getMessage());
        }

        return ResponseEntity.ok(
                new CreateOrderResponse(
                        savedOrder.getId(),
                        total,
                        items.size(),
                        paid,
                        null,
                        savedOrder.getInvoiceNumber()
                )
        );
    }

    @GetMapping("/summary")
    public ResponseEntity<List<OrderSummaryResponse>> getOrderSummary() {
        List<Order> orders = orderRepository.findAllWithDetails();
        Map<Long, UserClient.UserSnapshot> users = new HashMap<>();
        Map<Long, CustomerClient.CustomerSnapshot> customers = new HashMap<>();

        List<OrderSummaryResponse> result = orders.stream()
                .map(order -> toSummaryResponse(order, users, customers))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/customer/{id}")
    public ResponseEntity<List<OrderResponse>> getOrdersByCustomer(@PathVariable("id") Long customerId) {
        if (customerId == null) {
            return ResponseEntity.badRequest().body(List.of());
        }
        List<Order> orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        List<OrderResponse> responses = orders == null ? List.of() : orders.stream()
                .map(this::toOrderResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/internal")
    public ResponseEntity<List<OrderResponse>> getOrdersForReport(
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate
    ) {
        LocalDateTime from = null;
        LocalDateTime to = null;
        if (fromDate != null && !fromDate.isBlank()) {
            from = LocalDate.parse(fromDate.trim()).atStartOfDay();
        }
        if (toDate != null && !toDate.isBlank()) {
            to = LocalDate.parse(toDate.trim()).atTime(LocalTime.MAX);
        }
        List<Order> orders = orderRepository.searchOrders(null, null, from, to);
        List<OrderResponse> result = orders.stream()
                .map(this::toOrderResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/returns/search")
    public ResponseEntity<List<OrderSummaryResponse>> searchReturnOrders(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate
    ) {
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        if (trimmedKeyword != null && trimmedKeyword.isBlank()) {
            trimmedKeyword = null;
        }

        LocalDateTime from = null;
        LocalDateTime to = null;
        if (fromDate != null && !fromDate.isBlank()) {
            from = LocalDate.parse(fromDate.trim()).atStartOfDay();
        }
        if (toDate != null && !toDate.isBlank()) {
            to = LocalDate.parse(toDate.trim()).atTime(LocalTime.MAX);
        }

        List<Long> customerIds = trimmedKeyword == null ? List.of() : customerClient.searchCustomerIds(trimmedKeyword);
        if (customerIds == null || customerIds.isEmpty()) {
            customerIds = null;
        }

        List<Order> orders = orderRepository.searchOrders(trimmedKeyword, customerIds, from, to);
        Map<Long, UserClient.UserSnapshot> users = new HashMap<>();
        Map<Long, CustomerClient.CustomerSnapshot> customers = new HashMap<>();
        List<OrderSummaryResponse> result = orders.stream()
                .filter(order -> "PAID".equalsIgnoreCase(order.getStatus()))
                .filter(order -> !Boolean.TRUE.equals(order.getReturnOrder()))
                .map(order -> toSummaryResponse(order, users, customers))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long id) {
        return orderRepository.findByIdWithDetails(id)
                .map(order -> ResponseEntity.ok(toOrderResponse(order)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private DiscountResult resolveMemberDiscount(CustomerClient.CustomerSnapshot customer, BigDecimal total) {
        if (customer == null || total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            return new DiscountResult(BigDecimal.ZERO, 0);
        }
        int points = customer.getTotalPoints() != null ? customer.getTotalPoints() : 0;
        if (points < 100) {
            return new DiscountResult(BigDecimal.ZERO, 0);
        }

        CustomerTier tier = customer.getTier();
        if (tier == null) {
            tier = resolveTierByPoints(points);
        }
        if (tier == null) {
            return new DiscountResult(BigDecimal.ZERO, 0);
        }

        int rate = tier.discountValue;
        if (rate <= 0) {
            return new DiscountResult(BigDecimal.ZERO, 0);
        }

        int stepsByPoints = points / 100;
        int stepsByTotal = total.divide(BigDecimal.valueOf(rate), 0, RoundingMode.DOWN).intValue();
        int stepsUsed = Math.max(0, Math.min(stepsByPoints, stepsByTotal));
        if (stepsUsed <= 0) {
            return new DiscountResult(BigDecimal.ZERO, 0);
        }

        BigDecimal discount = BigDecimal.valueOf((long) stepsUsed * rate);
        int pointsUsed = stepsUsed * 100;
        return new DiscountResult(discount, pointsUsed);
    }

    private CustomerTier resolveTierByPoints(int points) {
        CustomerTier selected = null;
        for (CustomerTier tier : CustomerTier.values()) {
            if (points >= tier.monthlyLimit) {
                if (selected == null || tier.monthlyLimit > selected.monthlyLimit) {
                    selected = tier;
                }
            }
        }
        return selected;
    }

    private static class DiscountResult {
        private final BigDecimal discount;
        private final int pointsUsed;

        private DiscountResult(BigDecimal discount, int pointsUsed) {
            this.discount = discount;
            this.pointsUsed = pointsUsed;
        }
    }

    private OrderSummaryResponse toSummaryResponse(Order order,
                                                   Map<Long, UserClient.UserSnapshot> users,
                                                   Map<Long, CustomerClient.CustomerSnapshot> customers) {
        UserClient.UserSnapshot user = resolveUser(order.getUserId(), users);
        CustomerClient.CustomerSnapshot customer = resolveCustomer(order.getCustomerId(), customers);
        String userName = user == null ? null : resolveUserName(user);
        String customerName = customer == null ? null : customer.getName();
        String customerPhone = customer == null ? null : customer.getPhone();
        int itemCount = order.getItems() == null
                ? 0
                : order.getItems().stream()
                .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();

        return new OrderSummaryResponse(
                order.getId(),
                order.getUserId(),
                userName,
                order.getCustomerId(),
                customerName,
                customerPhone,
                order.getTotalAmount(),
                order.getCreatedAt(),
                itemCount,
                order.getInvoiceNumber(),
                order.getStatus(),
                userName,
                userName,
                order.getNote()
        );
    }

    private OrderResponse toOrderResponse(Order order) {
        Map<Long, UserClient.UserSnapshot> users = new HashMap<>();
        Map<Long, CustomerClient.CustomerSnapshot> customers = new HashMap<>();
        Map<Long, CatalogClient.ProductSnapshot> products = buildProductCache(order.getItems());

        UserClient.UserSnapshot user = resolveUser(order.getUserId(), users);
        CustomerClient.CustomerSnapshot customer = resolveCustomer(order.getCustomerId(), customers);
        String userName = user == null ? null : resolveUserName(user);
        String customerName = customer == null ? null : customer.getName();
        String customerPhone = customer == null ? null : customer.getPhone();
        List<OrderItemResponse> items = order.getItems() == null
                ? new ArrayList<>()
                : order.getItems().stream()
                .map(item -> toOrderItemResponse(item, products))
                .collect(Collectors.toList());

        OrderResponse response = new OrderResponse(
                order.getId(),
                order.getUserId(),
                userName,
                order.getCustomerId(),
                customerName,
                customerPhone,
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getInvoiceNumber(),
                order.getStatus(),
                userName,
                userName,
                items,
                order.getNote()
        );
        response.setReturnOrder(order.getReturnOrder());
        response.setOrderType(order.getOrderType());
        response.setRefundMethod(order.getRefundMethod());
        return response;
    }

    private OrderItemResponse toOrderItemResponse(OrderItem item,
                                                  Map<Long, CatalogClient.ProductSnapshot> products) {
        BigDecimal lineTotal = item.getPrice() == null || item.getQuantity() == null
                ? BigDecimal.ZERO
                : item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));

        CatalogClient.ProductSnapshot product = products.get(item.getProductId());

        return new OrderItemResponse(
                item.getId(),
                item.getProductId(),
                product == null ? null : product.getName(),
                item.getQuantity(),
                item.getPrice(),
                lineTotal,
                product == null ? null : product.getCode(),
                product == null ? null : product.getBarcode(),
                product == null ? null : product.getUnit(),
                null,
                null,
                null
        );
    }

    private UserClient.UserSnapshot resolveUser(Long userId, Map<Long, UserClient.UserSnapshot> cache) {
        if (userId == null) {
            return null;
        }
        if (cache.containsKey(userId)) {
            return cache.get(userId);
        }
        UserClient.UserSnapshot user = userClient.getUser(userId);
        cache.put(userId, user);
        return user;
    }

    private CustomerClient.CustomerSnapshot resolveCustomer(Long customerId,
                                                           Map<Long, CustomerClient.CustomerSnapshot> cache) {
        if (customerId == null) {
            return null;
        }
        if (cache.containsKey(customerId)) {
            return cache.get(customerId);
        }
        CustomerClient.CustomerSnapshot customer = customerClient.getCustomer(customerId);
        cache.put(customerId, customer);
        return customer;
    }

    private String resolveUserName(UserClient.UserSnapshot user) {
        if (user == null) return null;
        if (user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName();
        }
        return user.getUsername();
    }

    private Map<Long, CatalogClient.ProductSnapshot> buildProductCache(List<OrderItem> items) {
        Map<Long, CatalogClient.ProductSnapshot> products = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return products;
        }
        Set<Long> productIds = new HashSet<>();
        for (OrderItem item : items) {
            if (item.getProductId() != null) {
                productIds.add(item.getProductId());
            }
        }
        for (Long productId : productIds) {
            CatalogClient.ProductSnapshot product = catalogClient.getProduct(productId);
            if (product != null) {
                products.put(productId, product);
            }
        }
        return products;
    }

    private List<InventoryClient.SaleItem> toSaleItems(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().map(item -> {
            InventoryClient.SaleItem saleItem = new InventoryClient.SaleItem();
            saleItem.setProductId(item.getProductId());
            saleItem.setQuantity(item.getQuantity());
            saleItem.setUnitPrice(item.getPrice());
            return saleItem;
        }).collect(Collectors.toList());
    }
}
