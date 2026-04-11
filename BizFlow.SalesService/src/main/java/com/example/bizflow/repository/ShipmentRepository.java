package com.example.bizflow.repository;

import com.example.bizflow.entity.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByShipmentNumber(String shipmentNumber);
    List<Shipment> findByOrderId(Long orderId);
    List<Shipment> findByStatus(String status);
    List<Shipment> findByCustomerId(Long customerId);
    List<Shipment> findByCustomerIdOrCreatedBy(Long customerId, Long createdBy);

    @Query("SELECT s FROM Shipment s WHERE " +
           "(:status IS NULL OR s.status = :status) AND " +
           "(:from IS NULL OR s.createdAt >= :from) AND " +
           "(:to IS NULL OR s.createdAt <= :to) " +
           "ORDER BY s.createdAt DESC")
    List<Shipment> searchShipments(@Param("status") String status,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Query("SELECT s FROM Shipment s " +
           "LEFT JOIN Order o ON s.orderId = o.id " +
           "WHERE s.shipmentNumber LIKE %:keyword% OR " +
           "o.invoiceNumber LIKE %:keyword% OR " +
           "s.trackingCode LIKE %:keyword% OR " +
           "s.receiverPhone LIKE %:keyword% OR " +
           "s.receiverName LIKE %:keyword% " +
           "ORDER BY s.createdAt DESC")
    List<Shipment> searchByKeyword(@Param("keyword") String keyword);

    long countByStatus(String status);
}
