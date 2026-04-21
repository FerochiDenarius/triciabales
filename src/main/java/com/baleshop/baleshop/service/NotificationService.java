package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.AppNotification;
import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.AppNotificationRepository;
import com.baleshop.baleshop.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    private final AppNotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final AccountEmailService accountEmailService;

    public NotificationService(
            AppNotificationRepository notificationRepository,
            UserRepository userRepository,
            AccountEmailService accountEmailService
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.accountEmailService = accountEmailService;
    }

    public AppNotification notifyUser(Long userId, String type, String title, String message, String metadataJson) {
        if (userId == null) {
            return null;
        }

        AppNotification notification = new AppNotification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setMetadataJson(metadataJson);
        AppNotification saved = notificationRepository.save(notification);

        log.info("[Yenkasa Store] Internal notification saved userId={} type={} title={}", userId, type, title);
        return saved;
    }

    public AppNotification notifyRole(String role, String type, String title, String message, String metadataJson) {
        AppNotification notification = new AppNotification();
        notification.setRecipientRole(normalizeRole(role));
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setMetadataJson(metadataJson);
        AppNotification saved = notificationRepository.save(notification);

        log.info("[Yenkasa Store] Internal notification saved role={} type={} title={}", role, type, title);
        return saved;
    }

    public void notifyOrderCreated(Order order) {
        String orderId = orderLabel(order);
        String total = money(order.getTotal());
        String metadataJson = orderMetadata(order);

        notifyUser(
                order.getBuyerId(),
                "ORDER_CREATED",
                "Order received " + orderId,
                "Your order " + orderId + " has been received. Total: " + total + ".",
                metadataJson
        );

        for (Long sellerId : sellerIds(order)) {
            notifyUser(
                    sellerId,
                    "SELLER_ORDER_CREATED",
                    "New order " + orderId,
                    "A buyer placed order " + orderId + " for " + total + ". Prepare the products for delivery.",
                    metadataJson
            );
        }

        notifyRole(
                SUPER_ADMIN_ROLE,
                "ORDER_CREATED",
                "New marketplace order " + orderId,
                "Order " + orderId + " was placed by " + safe(order.getCustomerName()) + " for " + total + ".",
                metadataJson
        );

        emailBuyer(order, "Order received " + orderId,
                "Your Yenkasa Store order " + orderId + " has been received.\n\nTotal: " + total + "\nPayment status: " + safe(order.getPaymentStatus()));
        emailSeller(order, "New order " + orderId,
                "A buyer placed order " + orderId + " on Yenkasa Store.\n\nTotal: " + total + "\nDelivery status: " + safe(order.getDeliveryStatus()));
        emailSuperAdmins("New marketplace order " + orderId,
                "Order " + orderId + " was placed by " + safe(order.getCustomerName()) + ".\n\nTotal: " + total + "\nSeller: " + safe(order.getSellerName()));
    }

    public void notifyPaymentReceived(Order order) {
        String orderId = orderLabel(order);
        String total = money(order.getTotal());
        String metadataJson = orderMetadata(order);

        notifyUser(
                order.getBuyerId(),
                "PAYMENT_RECEIVED",
                "Payment received " + orderId,
                "Payment for order " + orderId + " has been confirmed. Amount: " + total + ".",
                metadataJson
        );

        for (Long sellerId : sellerIds(order)) {
            notifyUser(
                    sellerId,
                    "SELLER_PAYMENT_CONFIRMED",
                    "Payment confirmed " + orderId,
                    "Payment has been confirmed for order " + orderId + ". Prepare the order for delivery.",
                    metadataJson
            );
        }

        notifyRole(
                SUPER_ADMIN_ROLE,
                "PAYMENT_RECEIVED",
                "Payment confirmed " + orderId,
                "Payment was confirmed for order " + orderId + ". Amount: " + total + ".",
                metadataJson
        );

        emailBuyer(order, "Payment confirmed " + orderId,
                "Your payment for Yenkasa Store order " + orderId + " has been confirmed.\n\nAmount: " + total);
        emailSeller(order, "Payment confirmed " + orderId,
                "Payment has been confirmed for order " + orderId + ". Please prepare the order for delivery.");
        emailSuperAdmins("Payment confirmed " + orderId,
                "Payment was confirmed for order " + orderId + ".\n\nAmount: " + total + "\nPaystack reference: " + safe(order.getPaystackReference()));
    }

    public void notifyOrderStatusChanged(Order order, String changedField, String newValue) {
        String orderId = orderLabel(order);
        String title = "Order update " + orderId;
        String message = changedField + " for order " + orderId + " changed to " + safe(newValue) + ".";
        String metadataJson = orderMetadata(order);

        notifyUser(order.getBuyerId(), "ORDER_STATUS_CHANGED", title, message, metadataJson);
        for (Long sellerId : sellerIds(order)) {
            notifyUser(sellerId, "ORDER_STATUS_CHANGED", title, message, metadataJson);
        }
        notifyRole(SUPER_ADMIN_ROLE, "ORDER_STATUS_CHANGED", title, message, metadataJson);
    }

    public void notifySensitiveActivity(User actor, String title, String message) {
        Long actorId = actor != null ? actor.getId() : null;
        notifyUser(actorId, "SENSITIVE_ACTIVITY", title, message, null);
        notifyRole(SUPER_ADMIN_ROLE, "SENSITIVE_ACTIVITY", title, message, null);
        emailSuperAdmins(title, message);
    }

    private void emailBuyer(Order order, String subject, String body) {
        if (order.getBuyerEmail() != null) {
            accountEmailService.sendNotificationEmail(order.getBuyerEmail(), subject, body);
        }
    }

    private void emailSeller(Order order, String subject, String body) {
        for (Long sellerId : sellerIds(order)) {
            userRepository.findById(sellerId)
                    .map(User::getEmail)
                    .filter(email -> email != null && !email.isBlank())
                    .ifPresent(email -> accountEmailService.sendNotificationEmail(email, subject, body));
        }
    }

    private void emailSuperAdmins(String subject, String body) {
        List<User> superAdmins = userRepository.findByRoleIgnoreCase(SUPER_ADMIN_ROLE);
        for (User admin : superAdmins) {
            if (admin.getEmail() != null && !admin.getEmail().isBlank()) {
                accountEmailService.sendNotificationEmail(admin.getEmail(), subject, body);
            }
        }
    }

    private String orderMetadata(Order order) {
        return "{\"orderId\":" + order.getId()
                + ",\"buyerId\":" + nullableNumber(order.getBuyerId())
                + ",\"sellerId\":" + nullableNumber(order.getSellerId())
                + ",\"sellerCount\":" + nullableNumber(order.getSellerCount() == null ? null : order.getSellerCount().longValue())
                + ",\"paymentStatus\":\"" + jsonEscape(order.getPaymentStatus()) + "\""
                + ",\"deliveryStatus\":\"" + jsonEscape(order.getDeliveryStatus()) + "\""
                + "}";
    }

    private Set<Long> sellerIds(Order order) {
        Set<Long> sellerIds = new LinkedHashSet<>();
        if (order.getSellerId() != null) {
            sellerIds.add(order.getSellerId());
        }
        if (order.getItems() != null) {
            order.getItems().forEach(item -> {
                if (item.getSellerId() != null) {
                    sellerIds.add(item.getSellerId());
                }
            });
        }
        return sellerIds;
    }

    private String orderLabel(Order order) {
        return "#" + order.getId();
    }

    private String money(Double amount) {
        return "GHS " + String.format(Locale.ROOT, "%.2f", amount == null ? 0.0 : amount);
    }

    private String nullableNumber(Long value) {
        return value == null ? "null" : value.toString();
    }

    private String normalizeRole(String role) {
        return role == null ? null : role.trim().toUpperCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
