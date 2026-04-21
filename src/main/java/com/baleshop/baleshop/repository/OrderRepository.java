package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);
    List<Order> findBySellerId(Long sellerId);
    @Query("select distinct o from Order o left join o.items i where o.sellerId = :sellerId or i.sellerId = :sellerId")
    List<Order> findOrdersForSeller(@Param("sellerId") Long sellerId);
    List<Order> findByPaymentStatusInAndCreatedAtBefore(List<String> paymentStatuses, LocalDateTime cutoff);
    Optional<Order> findByPaystackReference(String paystackReference);
    Optional<Order> findByExternalDeliveryId(String externalDeliveryId);
}
