package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.CartItemDto;
import com.baleshop.baleshop.dto.CheckoutRequest;
import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.OrderItem;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.OrderRepository;
import com.baleshop.baleshop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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
        if (request.getPaymentStatus() != null && !request.getPaymentStatus().isBlank()) {
            order.setPaymentStatus(request.getPaymentStatus());
        } else {
            order.setPaymentStatus(
                    "cash".equalsIgnoreCase(request.getPaymentMethod())
                            ? "unpaid"
                            : "pending"
            );
        }

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

    @GetMapping("/user/{userId}")
    public List<Order> getUserOrders(@PathVariable Long userId) {
        return orderRepository.findByUserId(userId);
    }
}
