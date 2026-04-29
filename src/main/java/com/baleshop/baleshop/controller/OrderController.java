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
import com.baleshop.baleshop.service.PaymentApiException;
import com.baleshop.baleshop.service.PaystackService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    @Autowired
    private PaystackService paystackService;

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
        Set<Long> validatedSellerIds = new HashSet<>();

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

            validateSellerPayoutReady(bale.getSellerId(), validatedSellerIds);

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
        }

        if (updates.containsKey("paymentStatus")) {
            if (!isPrivileged) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to update payment status");
            }
            if ("paystack".equalsIgnoreCase(order.getPaymentMethod())) {
                throw new PaymentApiException(
                        HttpStatus.BAD_REQUEST,
                        "PAYSTACK_VERIFY_REQUIRED",
                        "Before changing a Paystack payment status, verify the Paystack transaction by reference",
                        "Use /api/paystack/verify?reference=" + nullToEmpty(order.getPaystackReference()) + ". Only Paystack success verification may mark this order paid."
                );
            }
            order.setPaymentStatus(updates.get("paymentStatus"));
            paymentStatusChanged = true;
        }

        if (updates.containsKey("holdPayout") && "true".equalsIgnoreCase(updates.get("holdPayout"))) {
            throw manualPayoutNotRequired();
        }

        if (updates.containsKey("resumePayout") && "true".equalsIgnoreCase(updates.get("resumePayout"))) {
            throw manualPayoutNotRequired();
        }

        if (updates.containsKey("releasePayout") && "true".equals(updates.get("releasePayout"))) {
            throw manualPayoutNotRequired();
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

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable Long id, HttpServletRequest request) {
        User actor = sessionAuthService.requireRole(request, "SUPER_ADMIN");
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        if (isPaidOrder(order)) {
            throw new PaymentApiException(
                    HttpStatus.BAD_REQUEST,
                    "PAID_ORDER_REFUND_REQUIRED",
                    "Paid orders cannot be cancelled directly",
                    "Refund must be handled from Paystack transaction/refund dashboard or the Yenkasa refund flow using the transaction reference."
            );
        }

        if ("paystack".equalsIgnoreCase(order.getPaymentMethod())
                && order.getPaystackReference() != null
                && !order.getPaystackReference().isBlank()) {
            String paystackStatus = paystackService.transactionStatus(order.getPaystackReference());
            if ("success".equalsIgnoreCase(paystackStatus)) {
                throw new PaymentApiException(
                        HttpStatus.BAD_REQUEST,
                        "PAID_ORDER_REFUND_REQUIRED",
                        "Paystack reports this transaction as successful, so it cannot be cancelled directly",
                        "Use the refund flow with Paystack reference " + order.getPaystackReference() + "."
                );
            }
            order.setPaystackGatewayResponse(paystackStatus);
        }

        order.setStatus("cancelled");
        order.setDeliveryStatus("cancelled");
        order.setPaymentStatus("cancelled");
        order.setPayoutStatus("not_required");
        order.setPayoutHeldAt(null);
        order.setPayoutHoldReason(null);
        orderRepository.save(order);

        notificationService.notifySensitiveActivity(
                actor,
                "Order cancelled",
                actor.getEmail() + " cancelled unpaid order #" + id + "."
        );
        notificationService.notifyOrderStatusChanged(order, "Order status", "cancelled");

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Order cancelled successfully",
                "orderId", id
        ));
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
                || "paid".equalsIgnoreCase(status)
                || order.getPaidAt() != null;
    }

    private void validateSellerPayoutReady(Long sellerId, Set<Long> validatedSellerIds) {
        if (sellerId == null || !validatedSellerIds.add(sellerId)) {
            return;
        }

        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller not found"));

        if (seller.getMomoNumber() == null || seller.getMomoNumber().isBlank()
                || seller.getMomoNetwork() == null || seller.getMomoNetwork().isBlank()) {
            throw new PaymentApiException(
                    HttpStatus.BAD_REQUEST,
                    "SELLER_SUBACCOUNT_MISSING",
                    "Seller payout details are missing",
                    "Ask seller to update MoMo payout settings before checkout."
            );
        }
    }

    private PaymentApiException manualPayoutNotRequired() {
        return new PaymentApiException(
                HttpStatus.BAD_REQUEST,
                "MANUAL_PAYOUT_NOT_REQUIRED",
                "This order uses Paystack split settlement. Manual payout release is not required",
                "Paystack automatically settles 90% to the seller subaccount and 10% to Yenkasa after a verified successful payment."
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
