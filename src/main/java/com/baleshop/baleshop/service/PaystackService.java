package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class PaystackService {

    private static final String PAYSTACK_BASE_URL = "https://api.paystack.co";

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.paystack.secret-key:}")
    private String secretKey;

    @Value("${app.frontend-base-url:https://www.yenkasa.xyz/store}")
    private String frontendBaseUrl;

    public PaystackService(OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @PostConstruct
    public void warnIfUnconfigured() {
        if (secretKey == null || secretKey.isBlank()) {
            System.out.println("⚠️ Paystack is not configured. Set PAYSTACK_SECRET_KEY before accepting Paystack payments.");
        }
    }

    public Map<String, Object> initializePayment(Order order, String email) {
        requireConfigured();

        if (order.getTotal() == null || order.getTotal() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order total must be greater than zero");
        }

        if ("paid".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order is already paid");
        }

        String customerEmail = cleanEmail(email);
        if (customerEmail == null && order.getCardEmail() != null) {
            customerEmail = cleanEmail(order.getCardEmail());
        }
        if (customerEmail == null && order.getBuyerEmail() != null) {
            customerEmail = cleanEmail(order.getBuyerEmail());
        }
        if (customerEmail == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required for Paystack payment");
        }

        String reference = "YENKASA-" + order.getId() + "-" + System.currentTimeMillis();
        long amountInPesewas = amountToPesewas(order.getTotal());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", order.getId());
        metadata.put("buyerId", order.getBuyerId());
        metadata.put("sellerId", order.getSellerId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", customerEmail);
        payload.put("amount", amountInPesewas);
        payload.put("currency", "GHS");
        payload.put("reference", reference);
        payload.put("callback_url", frontendBaseUrl + "/paystack/callback");
        payload.put("metadata", metadata);

        JsonNode response = postJson("/transaction/initialize", payload);
        JsonNode data = response.path("data");

        if (!response.path("status").asBoolean(false) || data.isMissingNode()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, response.path("message").asText("Could not initialize Paystack payment"));
        }

        order.setPaymentMethod("paystack");
        order.setPaymentStatus("awaiting_payment");
        order.setPaystackReference(reference);
        order.setPaystackAccessCode(data.path("access_code").asText(null));
        order.setPaystackAuthorizationUrl(data.path("authorization_url").asText(null));
        orderRepository.save(order);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("reference", reference);
        result.put("authorizationUrl", order.getPaystackAuthorizationUrl());
        result.put("accessCode", order.getPaystackAccessCode());
        result.put("amount", order.getTotal());
        return result;
    }

    public Map<String, Object> verifyPayment(String reference) {
        requireConfigured();

        if (reference == null || reference.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment reference is required");
        }

        Order order = orderRepository.findByPaystackReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for payment reference"));

        JsonNode response = getJson("/transaction/verify/" + encodePath(reference));
        JsonNode data = response.path("data");
        String paystackStatus = data.path("status").asText("");

        if (!response.path("status").asBoolean(false) || data.isMissingNode()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, response.path("message").asText("Could not verify Paystack payment"));
        }

        long expectedAmount = amountToPesewas(order.getTotal());
        long paidAmount = data.path("amount").asLong(0);

        if ("success".equalsIgnoreCase(paystackStatus) && expectedAmount == paidAmount) {
            markOrderPaid(order, data);
        } else if ("success".equalsIgnoreCase(paystackStatus)) {
            order.setPaystackGatewayResponse("amount_mismatch");
            orderRepository.save(order);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount does not match order total");
        } else {
            order.setPaystackGatewayResponse(paystackStatus);
            orderRepository.save(order);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("reference", reference);
        result.put("paystackStatus", paystackStatus);
        result.put("paymentStatus", order.getPaymentStatus());
        result.put("paid", "paid".equalsIgnoreCase(order.getPaymentStatus()));
        result.put("order", order);
        return result;
    }

    public boolean isValidWebhookSignature(String payload, String signature) {
        if (secretKey == null || secretKey.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(digest).equalsIgnoreCase(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }

    public void handleWebhook(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook payload");
        }

        String event = root.path("event").asText("");
        String reference = root.path("data").path("reference").asText("");

        if ("charge.success".equalsIgnoreCase(event) && !reference.isBlank()) {
            verifyPayment(reference);
        }
    }

    private void markOrderPaid(Order order, JsonNode data) {
        if ("paid".equalsIgnoreCase(order.getPaymentStatus())) {
            return;
        }

        order.setStatus("paid");
        order.setPaymentStatus("paid");
        order.setPaymentMethod("paystack");
        order.setPaystackGatewayResponse(data.path("gateway_response").asText("success"));
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);
    }

    private JsonNode postJson(String path, Map<String, Object> payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PAYSTACK_BASE_URL + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePaystackResponse(response);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Paystack");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Paystack request was interrupted");
        }
    }

    private JsonNode getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PAYSTACK_BASE_URL + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + secretKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePaystackResponse(response);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Paystack");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Paystack request was interrupted");
        }
    }

    private JsonNode parsePaystackResponse(HttpResponse<String> response) throws JacksonException {
        JsonNode body = objectMapper.readTree(response.body());

        if (response.statusCode() >= 400) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    body.path("message").asText("Paystack request failed")
            );
        }

        return body;
    }

    private void requireConfigured() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Paystack is not configured");
        }
    }

    private long amountToPesewas(Double amount) {
        return BigDecimal.valueOf(amount == null ? 0 : amount)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private String cleanEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
