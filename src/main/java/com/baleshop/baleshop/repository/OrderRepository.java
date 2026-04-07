package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
