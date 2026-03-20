package com.example.bizflow.service;

import com.example.bizflow.repository.CustomerRepository;
import com.example.bizflow.entity.Customer;
import com.example.bizflow.entity.CustomerTier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class PointService {
    private final CustomerRepository customerRepository;

    public PointService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional
    public void addPoints(Long customerId, BigDecimal totalAmount, String reference) {
        if (customerId == null || totalAmount == null) {
            return;
        }

        int points = totalAmount.divide(BigDecimal.valueOf(1000), java.math.RoundingMode.DOWN).intValue();
        if (points <= 0) {
            return;
        }

        customerRepository.findByIdForUpdate(customerId).ifPresent(customer -> {
            customer.addPoints(points);
            // Tự động nâng hạng thành viên dựa trên monthlyPoints
            updateCustomerTier(customer);
            customerRepository.save(customer);
        });
    }

    @Transactional
    public int redeemPoints(Long customerId, int points) {
        if (customerId == null || points <= 0) {
            return 0;
        }

        return customerRepository.findByIdForUpdate(customerId).map(customer -> {
            int current = customer.getTotalPoints() == null ? 0 : customer.getTotalPoints();
            int redeem = Math.min(current, points);
            if (redeem <= 0) {
                return 0;
            }
            customer.setTotalPoints(current - redeem);
            Integer monthly = customer.getMonthlyPoints() == null ? 0 : customer.getMonthlyPoints();
            customer.setMonthlyPoints(Math.max(0, monthly - redeem));
            customerRepository.save(customer);
            return redeem;
        }).orElse(0);
    }

    /**
     * Tự động nâng hạng thành viên dựa trên điểm tích lũy trong tháng
     * Quy tắc:
     * - DONG (Bronze): 0-999 điểm
     * - BAC (Silver): 1000-2999 điểm
     * - VANG (Gold): 3000-8999 điểm
     * - BACH_KIM (Platinum): 9000-14999 điểm
     * - KIM_CUONG (Diamond): 15000+ điểm
     */
    private void updateCustomerTier(Customer customer) {
        if (customer == null) {
            return;
        }

        int monthlyPoints = customer.getMonthlyPoints() != null ? customer.getMonthlyPoints() : 0;
        CustomerTier newTier;

        if (monthlyPoints >= 15000) {
            newTier = CustomerTier.KIM_CUONG;
        } else if (monthlyPoints >= 9000) {
            newTier = CustomerTier.BACH_KIM;
        } else if (monthlyPoints >= 3000) {
            newTier = CustomerTier.VANG;
        } else if (monthlyPoints >= 1000) {
            newTier = CustomerTier.BAC;
        } else {
            newTier = CustomerTier.DONG;
        }

        customer.setTier(newTier);
    }
}
