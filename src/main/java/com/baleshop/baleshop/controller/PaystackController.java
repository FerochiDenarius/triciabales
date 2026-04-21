package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.OrderRepository;
import com.baleshop.baleshop.service.PaystackService;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/paystack")
@CrossOrigin(origins = "*")
public class PaystackController {

    private final PaystackService paystackService;
    private final OrderRepository orderRepository;
    private final SessionAuthService sessionAuthService;

    public PaystackController(
            PaystackService paystackService,
            OrderRepository orderRepository,
            SessionAuthService sessionAuthService
    ) {
        this.paystackService = paystackService;
        this.orderRepository = orderRepository;
        this.sessionAuthService = sessionAuthService;
        System.out.println("✅ PaystackController loaded");
    }

    @PostMapping("/initialize")
    public ResponseEntity<Map<String, Object>> initialize(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest
    ) {
        User actor = sessionAuthService.requireAuthenticatedUser(httpRequest);
        Long orderId = longValue(request.get("orderId"));

        if (orderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId is required");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        if ((order.getUser() == null || !actor.getId().equals(order.getUser().getId()))
                && !"ADMIN".equalsIgnoreCase(actor.getRole())
                && !"SUPER_ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only pay for your own order");
        }

        String email = request.get("email") instanceof String value ? value : null;
        return ResponseEntity.ok(paystackService.initializePayment(order, email));
    }

    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestParam String reference) {
        return ResponseEntity.ok(paystackService.verifyPayment(reference));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(paystackService.status());
    }

    @GetMapping("/banks")
    public ResponseEntity<Map<String, Object>> banks(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "SELLER", "ADMIN", "SUPER_ADMIN");
        return ResponseEntity.ok(paystackService.listGhanaBanks());
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestBody String payload,
            @RequestHeader(value = "x-paystack-signature", required = false) String signature
    ) {
        if (!paystackService.isValidWebhookSignature(payload, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Paystack signature");
        }

        paystackService.handleWebhook(payload);
        return ResponseEntity.ok(Map.of("received", true));
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handlePaystackError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "success", false,
                "status", exception.getStatusCode().value(),
                "error", exception.getReason() != null ? exception.getReason() : "Paystack request failed"
        ));
    }
}
