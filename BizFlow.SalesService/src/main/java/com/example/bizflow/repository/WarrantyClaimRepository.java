package com.example.bizflow.repository;

import com.example.bizflow.entity.WarrantyClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WarrantyClaimRepository extends JpaRepository<WarrantyClaim, Long> {
    Optional<WarrantyClaim> findByClaimNumber(String claimNumber);
    List<WarrantyClaim> findByStatus(String status);
    List<WarrantyClaim> findByCustomerId(Long customerId);
    List<WarrantyClaim> findByOrderId(Long orderId);

    @Query("SELECT w FROM WarrantyClaim w WHERE " +
           "(:status IS NULL OR w.status = :status) AND " +
           "(:from IS NULL OR w.createdAt >= :from) AND " +
           "(:to IS NULL OR w.createdAt <= :to) " +
           "ORDER BY w.createdAt DESC")
    List<WarrantyClaim> searchClaims(@Param("status") String status,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    @Query("SELECT w FROM WarrantyClaim w " +
           "LEFT JOIN Order o ON w.orderId = o.id " +
           "WHERE w.claimNumber LIKE %:keyword% OR " +
           "o.invoiceNumber LIKE %:keyword% OR " +
           "w.customerId IN :customerIds " +
           "ORDER BY w.createdAt DESC")
    List<WarrantyClaim> searchByKeyword(@Param("keyword") String keyword,
                                        @Param("customerIds") List<Long> customerIds);

    long countByStatus(String status);
}
