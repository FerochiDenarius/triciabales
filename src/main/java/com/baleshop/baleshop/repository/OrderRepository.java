package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);
    List<Order> findBySellerId(Long sellerId);
    List<Order> findByPaymentStatusInAndCreatedAtBefore(List<String> paymentStatuses, LocalDateTime cutoff);
    Optional<Order> findByPaystackReference(String paystackReference);
    Optional<Order> findByExternalDeliveryId(String externalDeliveryId);
}
