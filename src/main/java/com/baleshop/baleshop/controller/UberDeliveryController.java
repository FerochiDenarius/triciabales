package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.OrderRepository;
import com.baleshop.baleshop.service.SessionAuthService;
import com.baleshop.baleshop.service.UberDirectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/deliveries/uber")
@CrossOrigin(origins = "*")
public class UberDeliveryController {

    private final UberDirectService uberDirectService;
    private final OrderRepository orderRepository;
    private final SessionAuthService sessionAuthService;

    public UberDeliveryController(
            UberDirectService uberDirectService,
            OrderRepository orderRepository,
            SessionAuthService sessionAuthService
    ) {
        this.uberDirectService = uberDirectService;
        this.orderRepository = orderRepository;
        this.sessionAuthService = sessionAuthService;
        System.out.println("✅ UberDeliveryController loaded");
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "ADMIN", "SUPER_ADMIN");
        return ResponseEntity.ok(uberDirectService.configurationStatus());
    }

    @PostMapping("/orders/{orderId}/quote")
    public ResponseEntity<Map<String, Object>> quote(
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        Order order = requireOrder(orderId);
        requireOrderAccess(actor, order);
        return ResponseEntity.ok(uberDirectService.createQuote(order, body));
    }

    @PostMapping("/orders/{orderId}/create")
    public ResponseEntity<Map<String, Object>> createDelivery(
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        Order order = requireOrder(orderId);
        requireDispatchAccess(actor, order);
        return ResponseEntity.ok(uberDirectService.createDelivery(order, body));
    }

    @GetMapping("/orders/{orderId}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable Long orderId,
            HttpServletRequest request
    ) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        Order order = requireOrder(orderId);
        requireOrderAccess(actor, order);
        return ResponseEntity.ok(uberDirectService.refreshDeliveryStatus(order));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestBody String payload,
            @RequestHeader(value = "x-uber-signature", required = false) String signature
    ) {
        return ResponseEntity.ok(uberDirectService.handleWebhook(payload, signature));
    }

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private void requireOrderAccess(User actor, Order order) {
        if (isPrivileged(actor)
                || actor.getId().equals(order.getBuyerId())
                || orderContainsSeller(order, actor.getId())) {
            return;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this order");
    }

    private void requireDispatchAccess(User actor, Order order) {
        if (isPrivileged(actor)
                || ("SELLER".equalsIgnoreCase(actor.getRole()) && orderContainsSeller(order, actor.getId()))) {
            return;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller or admin can create delivery");
    }

    private boolean isPrivileged(User actor) {
        return "ADMIN".equalsIgnoreCase(actor.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(actor.getRole());
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
}
