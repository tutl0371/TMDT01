package com.example.bizflow.repository;

import com.example.bizflow.entity.ShipmentTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ShipmentTrackingRepository extends JpaRepository<ShipmentTracking, Long> {
    List<ShipmentTracking> findByShipmentIdOrderByCreatedAtDesc(Long shipmentId);
}
