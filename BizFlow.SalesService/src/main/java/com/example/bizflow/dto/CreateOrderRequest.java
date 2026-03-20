package com.example.bizflow.dto;

import java.util.List;

public class CreateOrderRequest {
    private Long customerId;
    private Long userId;
    private Boolean paid;
    private String paymentMethod;
    private Boolean returnOrder;
    private String orderType;
    private Long originalOrderId;
    private String refundMethod;
    private String returnReason;
    private String returnNote;
    private Boolean usePoints;
    private List<OrderItemRequest> items;

    public CreateOrderRequest() {
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Boolean getPaid() {
        return paid;
    }

    public void setPaid(Boolean paid) {
        this.paid = paid;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public Boolean getReturnOrder() {
        return returnOrder;
    }

    public void setReturnOrder(Boolean returnOrder) {
        this.returnOrder = returnOrder;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public Long getOriginalOrderId() {
        return originalOrderId;
    }

    public void setOriginalOrderId(Long originalOrderId) {
        this.originalOrderId = originalOrderId;
    }

    public String getRefundMethod() {
        return refundMethod;
    }

    public void setRefundMethod(String refundMethod) {
        this.refundMethod = refundMethod;
    }

    public String getReturnReason() {
        return returnReason;
    }

    public void setReturnReason(String returnReason) {
        this.returnReason = returnReason;
    }

    public String getReturnNote() {
        return returnNote;
    }

    public void setReturnNote(String returnNote) {
        this.returnNote = returnNote;
    }

    public Boolean getUsePoints() {
        return usePoints;
    }

    public void setUsePoints(Boolean usePoints) {
        this.usePoints = usePoints;
    }

    public List<OrderItemRequest> getItems() {
        return items;
    }

    public void setItems(List<OrderItemRequest> items) {
        this.items = items;
    }
}
