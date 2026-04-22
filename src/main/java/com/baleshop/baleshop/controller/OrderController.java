package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.CartItemDto;
import com.baleshop.baleshop.dto.CheckoutRequest;
import com.baleshop.baleshop.dto.DeliveryEstimateRequest;
import com.baleshop.baleshop.dto.RefundRequest;
import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.OrderItem;
import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.BaleRepository;
import com.baleshop.baleshop.repository.OrderRepository;
import com.baleshop.baleshop.repository.UserRepository;
import com.baleshop.baleshop.service.NotificationService;
import com.baleshop.baleshop.service.DeliveryEstimateService;
import com.baleshop.baleshop.service.RefundService;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {

    public OrderController() {
        System.out.println("✅ OrderController loaded");
    }

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private BaleRepository baleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SessionAuthService sessionAuthService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private DeliveryEstimateService deliveryEstimateService;
    @Autowired
    private RefundService refundService;

    @PostMapping("/checkout")
    public Order checkout(@RequestBody CheckoutRequest request, HttpServletRequest httpRequest) {
        User authenticatedUser = sessionAuthService.requireAuthenticatedUser(httpRequest);

        Order order = new Order();
        order.setCustomerName(request.getCustomerName());
        order.setPhone(request.getPhone());
        order.setAddress(request.getAddress());
        order.setRegion(request.getRegion());
        order.setArea(request.getArea());
        order.setLandmark(request.getLandmark());
        order.setNotes(request.getNotes());
        order.setDeliveryAddress(request.getDeliveryAddress());
        order.setDeliveryPlaceId(request.getDeliveryPlaceId());
        order.setDeliveryDistanceKm(request.getDeliveryDistanceKm());

        order.setStatus("pending");

        order.setDeliveryMethod(request.getDeliveryMethod());
        order.setPaymentMethod(request.getPaymentMethod());
        order.setMomoNetwork(request.getMomoNetwork());
        order.setMomoNumber(request.getMomoNumber());
        order.setCardEmail(request.getCardEmail());

        order.setUser(authenticatedUser);

        order.setDeliveryStatus("pending");
        order.setPaymentStatus(initialPaymentStatus(request.getPaymentMethod()));

        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0;
        Map<Long, String> sellers = new LinkedHashMap<>();

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is empty");
        }

        for (CartItemDto item : request.getItems()) {
            if (item.getBaleId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart item is missing product id");
            }

            Bale bale = baleRepository.findById((int) item.getBaleId())
                    .orElseThrow(() -> new RuntimeException("Product not found"));

            if (bale.getSellerId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product " + bale.getId() + " does not have a seller");
            }

            int quantity = item.getQuantity() == null ? 1 : item.getQuantity();
            if (quantity <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid quantity for product " + bale.getId());
            }

            sellers.putIfAbsent(bale.getSellerId(), bale.getSellerName());

            OrderItem orderItem = new OrderItem();
            orderItem.setBaleId(item.getBaleId());
            orderItem.setBaleName(bale.getName());
            orderItem.setPrice(bale.getPrice());
            orderItem.setQuantity(quantity);
            orderItem.setSellerId(bale.getSellerId());
            orderItem.setSellerName(bale.getSellerName());
            orderItem.setSelectedSize(item.getSelectedSize());
            orderItem.setLineTotal(bale.getPrice() * quantity);
            orderItem.setOrder(order);

            total += orderItem.getLineTotal();

            orderItems.add(orderItem);
        }

        double productSubtotal = total;
        applyDeliveryEstimate(order, request);
        order.setItems(orderItems);
        double deliveryFee = order.getDeliveryFee() == null ? 0.0 : order.getDeliveryFee();
        order.setTotal(roundMoney(productSubtotal + deliveryFee));
        order.setSellerCount(sellers.size());
        if (sellers.size() == 1) {
            Map.Entry<Long, String> seller = sellers.entrySet().iterator().next();
            order.setSellerId(seller.getKey());
            order.setSellerName(seller.getValue());
        } else {
            order.setSellerId(null);
            order.setSellerName("Multiple sellers");
        }
        order.setCommissionAmount(roundMoney(productSubtotal * 0.10));
        order.setSellerPayoutAmount(roundMoney(productSubtotal - order.getCommissionAmount()));

        Order savedOrder = orderRepository.save(order);
        notificationService.notifyOrderCreated(savedOrder);

        return savedOrder;
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
    }

    @GetMapping
    public List<Order> getAllOrders(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "ADMIN", "SUPER_ADMIN");
        return sortOrdersByNewestFirst(orderRepository.findAll());
    }

    @GetMapping("/user/{userId}")
    public List<Order> getUserOrders(@PathVariable Long userId, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);

        if (!userId.equals(actor.getId()) && !"ADMIN".equalsIgnoreCase(actor.getRole()) && !"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own orders");
        }

        return sortOrdersByNewestFirst(orderRepository.findByUserId(userId));
    }

    @GetMapping("/seller/{sellerId}")
    public List<Order> getSellerOrders(@PathVariable Long sellerId, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);

        if (!sellerId.equals(actor.getId()) && !"ADMIN".equalsIgnoreCase(actor.getRole()) && !"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own seller orders");
        }

        return sortOrdersByNewestFirst(orderRepository.findOrdersForSeller(sellerId));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Order> updateOrderStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> updates,
            HttpServletRequest request
    ) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        Optional<Order> optionalOrder = orderRepository.findById(id);

        if (optionalOrder.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Order order = optionalOrder.get();

        boolean isPrivileged = "ADMIN".equalsIgnoreCase(actor.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(actor.getRole());
        boolean isSellerOwner = "SELLER".equalsIgnoreCase(actor.getRole()) && orderContainsSeller(order, actor.getId());

        boolean deliveryStatusChanged = false;
        boolean paymentStatusChanged = false;
        boolean payoutChanged = false;

        if (updates.containsKey("deliveryStatus")) {
            if (!isSellerOwner && !isPrivileged) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to update this order");
            }
            order.setDeliveryStatus(updates.get("deliveryStatus"));
            deliveryStatusChanged = true;

            if ("delivered".equalsIgnoreCase(order.getDeliveryStatus()) && canMoveDeliveredOrderToPayoutQueue(order)) {
                order.setPaymentStatus("ready_for_payout");
                payoutChanged = true;
            }
        }

        if (updates.containsKey("paymentStatus")) {
            if (!isPrivileged) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to update payment status");
            }
            order.setPaymentStatus(updates.get("paymentStatus"));
            paymentStatusChanged = true;
        }

        if (updates.containsKey("holdPayout") && "true".equalsIgnoreCase(updates.get("holdPayout"))) {
            if (!"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can hold payouts");
            }
            if (!isPayoutReleaseEligible(order)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order must be delivered or confirmed by buyer before payout hold");
            }

            order.setPaymentStatus("payout_on_hold");
            order.setPayoutHeldAt(LocalDateTime.now());
            order.setPayoutHoldReason(updates.getOrDefault("payoutHoldReason", "Held by super admin"));
            payoutChanged = true;
        }

        if (updates.containsKey("resumePayout") && "true".equalsIgnoreCase(updates.get("resumePayout"))) {
            if (!"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can resume payouts");
            }
            if (!isPayoutReleaseEligible(order)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order must be delivered or confirmed by buyer before payout release");
            }

            order.setPaymentStatus("ready_for_payout");
            order.setPayoutHeldAt(null);
            order.setPayoutHoldReason(null);
            payoutChanged = true;
        }

        if (updates.containsKey("releasePayout") && "true".equals(updates.get("releasePayout"))) {
            if (!"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can release payouts");
            }
            if (!isPayoutReleaseEligible(order)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order must be delivered or confirmed by buyer before payout");
            }

            double total = order.getTotal() != null ? order.getTotal() : 0.0;
            double commission = total * 0.10;
            double sellerPayout = total - commission;

            order.setCommissionAmount(commission);
            order.setSellerPayoutAmount(sellerPayout);
            order.setPaymentStatus("payout_released");
            order.setPayoutReleased(true);
            order.setPayoutReleasedAt(LocalDateTime.now());
            order.setPayoutHeldAt(null);
            order.setPayoutHoldReason(null);
            payoutChanged = true;
        }

        orderRepository.save(order);

        if (deliveryStatusChanged) {
            notificationService.notifyOrderStatusChanged(order, "Delivery status", order.getDeliveryStatus());
        }
        if (paymentStatusChanged) {
            notificationService.notifyOrderStatusChanged(order, "Payment status", order.getPaymentStatus());
        }
        if (payoutChanged) {
            notificationService.notifyOrderStatusChanged(order, "Payout status", order.getPaymentStatus());
        }

        return ResponseEntity.ok(order);
    }

    @PutMapping("/{id}/confirm-received")
    public ResponseEntity<Order> confirmOrderReceived(@PathVariable Long id, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        Optional<Order> optionalOrder = orderRepository.findById(id);

        if (optionalOrder.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Order order = optionalOrder.get();

        if ((order.getUser() == null || !actor.getId().equals(order.getUser().getId()))
                && !"ADMIN".equalsIgnoreCase(actor.getRole())
                && !"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only confirm your own orders");
        }

        order.setConfirmedByBuyer(true);
        order.setBuyerConfirmedAt(LocalDateTime.now());
        order.setPaymentStatus("ready_for_payout");

        orderRepository.save(order);
        notificationService.notifyOrderStatusChanged(order, "Buyer confirmation", "received");

        return ResponseEntity.ok(order);
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Map<String, Object>> requestRefund(
            @PathVariable Long id,
            @RequestBody RefundRequest refundRequest,
            HttpServletRequest request
    ) {
        User actor = sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.status(HttpStatus.CREATED).body(refundService.requestRefund(id, refundRequest, actor));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteUnpaidOrder(@PathVariable Long id, HttpServletRequest request) {
        User actor = sessionAuthService.requireRole(request, "SUPER_ADMIN");
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        if (isPaidOrder(order)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paid orders cannot be deleted");
        }

        orderRepository.delete(order);
        notificationService.notifySensitiveActivity(
                actor,
                "Unpaid order deleted",
                actor.getEmail() + " deleted unpaid order #" + id + "."
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Unpaid order deleted successfully",
                "orderId", id
        ));
    }

    private List<Order> sortOrdersByNewestFirst(List<Order> orders) {
        return orders.stream()
                .sorted(Comparator.comparing(Order::getId, Comparator.nullsLast(Long::compareTo)).reversed())
                .toList();
    }

    private String initialPaymentStatus(String paymentMethod) {
        if ("cash".equalsIgnoreCase(paymentMethod)) {
            return "pending";
        }
        if ("bank".equalsIgnoreCase(paymentMethod)) {
            return "awaiting_transfer";
        }
        return "awaiting_payment";
    }

    private void applyDeliveryEstimate(Order order, CheckoutRequest request) {
        if ("pickup".equalsIgnoreCase(request.getDeliveryMethod())) {
            order.setDeliveryFee(0.0);
            order.setDeliveryDistanceKm(null);
            order.setDeliveryAddress(null);
            return;
        }

        DeliveryEstimateRequest estimateRequest = new DeliveryEstimateRequest();
        estimateRequest.setAddress(request.getAddress());
        estimateRequest.setArea(request.getArea());
        estimateRequest.setRegion(request.getRegion());
        estimateRequest.setLandmark(request.getLandmark());
        estimateRequest.setPlaceId(request.getDeliveryPlaceId());
        estimateRequest.setItems(request.getItems());

        Map<String, Object> estimate = deliveryEstimateService.estimate(estimateRequest);
        order.setDeliveryFee(numberValue(estimate.get("deliveryFee"), 0.0));
        order.setDeliveryDistanceKm(numberValue(estimate.get("distanceKm"), null));
        order.setDeliveryAddress(String.valueOf(estimate.getOrDefault("buyerAddress", "")));
    }

    private Double numberValue(Object value, Double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean orderContainsSeller(Order order, Long sellerId) {
        if (sellerId == null || order == null) {
            return false;
        }
        if (sellerId.equals(order.getSellerId())) {
            return true;
        }
        return order.getItems() != null
                && order.getItems().stream().anyMatch(item -> sellerId.equals(item.getSellerId()));
    }

    private double roundMoney(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean isPaidOrder(Order order) {
        String paymentStatus = order.getPaymentStatus() == null ? "" : order.getPaymentStatus().trim();
        String status = order.getStatus() == null ? "" : order.getStatus().trim();

        return "paid".equalsIgnoreCase(paymentStatus)
                || "ready_for_payout".equalsIgnoreCase(paymentStatus)
                || "payout_on_hold".equalsIgnoreCase(paymentStatus)
                || "payout_released".equalsIgnoreCase(paymentStatus)
                || "paid".equalsIgnoreCase(status)
                || order.getPaidAt() != null;
    }

    private boolean canMoveDeliveredOrderToPayoutQueue(Order order) {
        String paymentStatus = order.getPaymentStatus() == null ? "" : order.getPaymentStatus().trim();

        return isPaidOrder(order)
                && !"ready_for_payout".equalsIgnoreCase(paymentStatus)
                && !"payout_on_hold".equalsIgnoreCase(paymentStatus)
                && !"payout_released".equalsIgnoreCase(paymentStatus)
                && !Boolean.TRUE.equals(order.getPayoutReleased());
    }

    private boolean isPayoutReleaseEligible(Order order) {
        return Boolean.TRUE.equals(order.getConfirmedByBuyer())
                || "delivered".equalsIgnoreCase(order.getDeliveryStatus());
    }
}
