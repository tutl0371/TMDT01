package com.example.bizflow.repository;

import com.example.bizflow.entity.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByPhone(String phone);

    // 🔒 Khóa bản ghi khi cộng điểm (tránh cộng sai khi nhiều request)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    @Query("""
        select c.id from Customer c
        where (:keyword is null
            or lower(c.name) like lower(concat('%', :keyword, '%'))
            or c.phone like concat('%', :keyword, '%'))
    """)
    List<Long> searchCustomerIds(@Param("keyword") String keyword);
}
