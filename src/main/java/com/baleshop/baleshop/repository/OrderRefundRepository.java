package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.OrderRefund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRefundRepository extends JpaRepository<OrderRefund, Long> {

    List<OrderRefund> findAllByOrderByCreatedAtDesc();
    Optional<OrderRefund> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(Long orderId, List<String> statuses);
    Optional<OrderRefund> findFirstByOrderPaystackReferenceOrderByCreatedAtDesc(String paystackReference);
}
