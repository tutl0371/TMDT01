package com.example.bizflow.dto;

import com.example.bizflow.entity.Product;
import java.math.BigDecimal;

public class ProductDTO {

    private Long id;
    private Long categoryId;
    private String code;
    private String barcode;
    private String name;
    private BigDecimal price;
    private BigDecimal costPrice; // Chỉ Owner/Admin mới thấy
    private String unit;
    private String description;
    private String status;
    private Integer stock;

    public ProductDTO() {
    }

    // Constructor từ Entity - bao gồm costPrice (cho Owner/Admin)
    public static ProductDTO fromEntity(Product product, boolean includeCostPrice) {
        ProductDTO dto = new ProductDTO();
        dto.setId(product.getId());
        dto.setCategoryId(product.getCategoryId());
        dto.setCode(product.getCode());
        dto.setBarcode(product.getBarcode());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setUnit(product.getUnit());
        dto.setDescription(product.getDescription());
        dto.setStatus(product.getStatus());
        dto.setStock(product.getStock());

        if (includeCostPrice) {
            dto.setCostPrice(product.getCostPrice());
        }
        return dto;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}
