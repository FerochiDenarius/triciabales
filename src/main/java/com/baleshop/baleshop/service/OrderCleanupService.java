package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OrderCleanupService.class);
    private static final List<String> UNPAID_PAYMENT_STATUSES = List.of(
            "pending",
            "awaiting_payment",
            "failed",
            "cancelled"
    );

    private final OrderRepository orderRepository;
    private final NotificationService notificationService;

    public OrderCleanupService(OrderRepository orderRepository, NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 20 2 * * *")
    public void deleteOldUnpaidOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(15);
        List<Order> oldUnpaidOrders = orderRepository.findByPaymentStatusInAndCreatedAtBefore(
                UNPAID_PAYMENT_STATUSES,
                cutoff
        );

        if (oldUnpaidOrders.isEmpty()) {
            return;
        }

        for (Order order : oldUnpaidOrders) {
            log.info("[Yenkasa Store] Auto-deleting unpaid order id={} paymentStatus={} createdAt={}",
                    order.getId(), order.getPaymentStatus(), order.getCreatedAt());
            orderRepository.delete(order);
        }

        notificationService.notifyRole(
                "SUPER_ADMIN",
                "ORDER_CLEANUP",
                "Old unpaid orders deleted",
                oldUnpaidOrders.size() + " unpaid order(s) older than 15 days were deleted automatically.",
                null
        );
    }
}
