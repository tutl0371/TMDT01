package com.example.bizflow.repository;

import com.example.bizflow.entity.WarrantyPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WarrantyPolicyRepository extends JpaRepository<WarrantyPolicy, Long> {
    List<WarrantyPolicy> findByStatus(String status);
    List<WarrantyPolicy> findByApplicableProductId(Long productId);
    List<WarrantyPolicy> findByApplicableCategoryId(Long categoryId);
}
