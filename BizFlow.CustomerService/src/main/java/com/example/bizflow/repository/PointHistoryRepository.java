package com.example.bizflow.repository;

import com.example.bizflow.entity.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    // ✅ DÙNG ĐỂ CHỐNG CỘNG ĐIỂM TRÙNG
    boolean existsByCustomerIdAndReason(Long customerId, String reason);
}
