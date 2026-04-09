package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.CartItemDto;
import com.baleshop.baleshop.dto.CheckoutRequest;
import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.OrderItem;
import com.baleshop.baleshop.model.Bale;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.BaleRepository;
import com.baleshop.baleshop.repository.OrderRepository;
import com.baleshop.baleshop.repository.UserRepository;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

        order.setStatus("pending");

        order.setDeliveryMethod(request.getDeliveryMethod());
        order.setPaymentMethod(request.getPaymentMethod());
        order.setMomoNetwork(request.getMomoNetwork());
        order.setMomoNumber(request.getMomoNumber());
        order.setCardEmail(request.getCardEmail());

        order.setUser(authenticatedUser);

        order.setDeliveryStatus("pending");
        order.setPaymentStatus(
                request.getPaymentStatus() != null
                        ? request.getPaymentStatus()
                        : (
                        "cash".equalsIgnoreCase(request.getPaymentMethod())
                                ? "pending"
                                : "awaiting_payment"
                )
        );

        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0;
        Long sellerId = null;
        String sellerName = null;

        for (CartItemDto item : request.getItems()) {
            Bale bale = baleRepository.findById((int) item.getBaleId())
                    .orElseThrow(() -> new RuntimeException("Product not found"));

            if (sellerId == null && bale.getSellerId() != null) {
                sellerId = bale.getSellerId();
                sellerName = bale.getSellerName();
            }

            if (sellerId != null && bale.getSellerId() != null && !sellerId.equals(bale.getSellerId())) {
                throw new RuntimeException("Cart items from multiple sellers are not supported yet");
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setBaleId(item.getBaleId());
            orderItem.setBaleName(item.getBaleName());
            orderItem.setPrice(item.getPrice());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setOrder(order);

            total += item.getPrice() * item.getQuantity();

            orderItems.add(orderItem);
        }

        order.setItems(orderItems);
        order.setTotal(total);
        order.setSellerId(sellerId);
        order.setSellerName(sellerName);

        return orderRepository.save(order);
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
    }

    @GetMapping
    public List<Order> getAllOrders(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "ADMIN", "SUPER_ADMIN");
        return orderRepository.findAll();
    }

    @GetMapping("/user/{userId}")
    public List<Order> getUserOrders(@PathVariable Long userId, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);

        if (!userId.equals(actor.getId()) && !"ADMIN".equalsIgnoreCase(actor.getRole()) && !"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own orders");
        }

        return orderRepository.findByUserId(userId);
    }

    @GetMapping("/seller/{sellerId}")
    public List<Order> getSellerOrders(@PathVariable Long sellerId, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);

        if (!sellerId.equals(actor.getId()) && !"ADMIN".equalsIgnoreCase(actor.getRole()) && !"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own seller orders");
        }

        return orderRepository.findBySellerId(sellerId);
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
        boolean isSellerOwner = "SELLER".equalsIgnoreCase(actor.getRole()) && actor.getId().equals(order.getSellerId());

        if (updates.containsKey("deliveryStatus")) {
            if (!isSellerOwner && !isPrivileged) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to update this order");
            }
            order.setDeliveryStatus(updates.get("deliveryStatus"));
        }

        if (updates.containsKey("paymentStatus")) {
            if (!isPrivileged) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to update payment status");
            }
            order.setPaymentStatus(updates.get("paymentStatus"));
        }

        if (updates.containsKey("holdPayout") && "true".equalsIgnoreCase(updates.get("holdPayout"))) {
            if (!"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can hold payouts");
            }
            if (!Boolean.TRUE.equals(order.getConfirmedByBuyer())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Buyer must confirm receipt before payout hold");
            }

            order.setPaymentStatus("payout_on_hold");
            order.setPayoutHeldAt(LocalDateTime.now());
            order.setPayoutHoldReason(updates.getOrDefault("payoutHoldReason", "Held by super admin"));
        }

        if (updates.containsKey("resumePayout") && "true".equalsIgnoreCase(updates.get("resumePayout"))) {
            if (!"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can resume payouts");
            }
            if (!Boolean.TRUE.equals(order.getConfirmedByBuyer())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Buyer must confirm receipt before payout release");
            }

            order.setPaymentStatus("ready_for_payout");
            order.setPayoutHeldAt(null);
            order.setPayoutHoldReason(null);
        }

        if (updates.containsKey("releasePayout") && "true".equals(updates.get("releasePayout"))) {
            if (!"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only SUPER_ADMIN can release payouts");
            }
            if (!Boolean.TRUE.equals(order.getConfirmedByBuyer())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Buyer must confirm receipt before payout");
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
        }

        orderRepository.save(order);

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

        return ResponseEntity.ok(order);
    }
}
