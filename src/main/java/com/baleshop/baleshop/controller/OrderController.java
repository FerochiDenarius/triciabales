package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.CartItemDto;
import com.baleshop.baleshop.dto.CheckoutRequest;
import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.OrderItem;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.OrderRepository;
import com.baleshop.baleshop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private UserRepository userRepository;

    @PostMapping("/checkout")
    public Order checkout(@RequestBody CheckoutRequest request) {

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

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            order.setUser(user);
        }

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

        for (CartItemDto item : request.getItems()) {

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

        return orderRepository.save(order);
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
    }

    @GetMapping
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @GetMapping("/user/{userId}")
    public List<Order> getUserOrders(@PathVariable Long userId) {
        return orderRepository.findByUserId(userId);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Order> updateOrderStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> updates
    ) {
        Optional<Order> optionalOrder = orderRepository.findById(id);

        if (optionalOrder.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Order order = optionalOrder.get();

        if (updates.containsKey("deliveryStatus")) {
            order.setDeliveryStatus(updates.get("deliveryStatus"));
        }

        if (updates.containsKey("paymentStatus")) {
            order.setPaymentStatus(updates.get("paymentStatus"));
        }

        if (updates.containsKey("releasePayout") && "true".equals(updates.get("releasePayout"))) {
            if (!Boolean.TRUE.equals(order.getConfirmedByBuyer())) {
                return ResponseEntity.badRequest().build();
            }

            double total = order.getTotal() != null ? order.getTotal() : 0.0;
            double commission = total * 0.10;
            double sellerPayout = total - commission;

            order.setCommissionAmount(commission);
            order.setSellerPayoutAmount(sellerPayout);
            order.setPaymentStatus("payout_released");
            order.setPayoutReleased(true);
            order.setPayoutReleasedAt(LocalDateTime.now());
        }

        orderRepository.save(order);

        return ResponseEntity.ok(order);
    }

    @PutMapping("/{id}/confirm-received")
    public ResponseEntity<Order> confirmOrderReceived(@PathVariable Long id) {
        Optional<Order> optionalOrder = orderRepository.findById(id);

        if (optionalOrder.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Order order = optionalOrder.get();

        order.setConfirmedByBuyer(true);
        order.setBuyerConfirmedAt(LocalDateTime.now());
        order.setPaymentStatus("ready_for_payout");

        orderRepository.save(order);

        return ResponseEntity.ok(order);
    }
}
