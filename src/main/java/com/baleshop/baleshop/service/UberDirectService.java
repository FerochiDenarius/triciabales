package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.OrderItem;
import com.baleshop.baleshop.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UberDirectService {

    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.uber.client-id:}")
    private String clientId;

    @Value("${app.uber.client-secret:}")
    private String clientSecret;

    @Value("${app.uber.scope:eats.deliveries}")
    private String scope;

    @Value("${app.uber.token-url:https://auth.uber.com/oauth/v2/token}")
    private String tokenUrl;

    @Value("${app.uber.api-base-url:https://api.uber.com/v1}")
    private String apiBaseUrl;

    @Value("${app.uber.customer-id:}")
    private String customerId;

    @Value("${app.uber.pickup-name:Yenkasa Store}")
    private String defaultPickupName;

    @Value("${app.uber.pickup-phone:}")
    private String defaultPickupPhone;

    @Value("${app.uber.pickup-address:}")
    private String defaultPickupAddress;

    @Value("${app.uber.webhook-signing-key:}")
    private String webhookSigningKey;

    private String cachedAccessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public UberDirectService(
            OrderRepository orderRepository,
            NotificationService notificationService,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @PostConstruct
    public void warnIfUnconfigured() {
        if (clientId == null || clientId.isBlank()
                || clientSecret == null || clientSecret.isBlank()
                || customerId == null || customerId.isBlank()) {
            System.out.println("⚠️ Uber Direct is not fully configured. Set UBER_CLIENT_ID, UBER_CLIENT_SECRET, and UBER_CUSTOMER_ID.");
        }
    }

    public Map<String, Object> createQuote(Order order, Map<String, Object> request) {
        requireConfigured();

        String pickupAddress = text(request.get("pickupAddress"), order.getPickupAddress(), defaultPickupAddress);
        String dropoffAddress = text(request.get("dropoffAddress"), order.getDropoffAddress(), buildDropoffAddress(order));

        if (pickupAddress == null || dropoffAddress == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pickupAddress and dropoffAddress are required for Uber delivery quote");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pickup_address", pickupAddress);
        payload.put("dropoff_address", dropoffAddress);

        copyIfPresent(request, payload, "pickupLatitude", "pickup_latitude");
        copyIfPresent(request, payload, "pickupLongitude", "pickup_longitude");
        copyIfPresent(request, payload, "dropoffLatitude", "dropoff_latitude");
        copyIfPresent(request, payload, "dropoffLongitude", "dropoff_longitude");

        JsonNode response = postJson(customerPath("/delivery_quotes"), payload);

        order.setDeliveryProvider("UBER_DIRECT");
        order.setDeliveryMethod("delivery");
        order.setDeliveryStatus("quote_created");
        order.setPickupAddress(pickupAddress);
        order.setDropoffAddress(dropoffAddress);
        order.setRecipientName(text(request.get("recipientName"), order.getRecipientName(), order.getCustomerName()));
        order.setRecipientPhone(text(request.get("recipientPhone"), order.getRecipientPhone(), order.getPhone()));
        order.setUberQuoteId(response.path("id").asText(null));
        order.setDeliveryFee(readMoney(response));
        order.setDeliveryGatewayResponse(truncate(response.toString()));
        orderRepository.save(order);

        notificationService.notifyOrderStatusChanged(order, "Uber delivery quote", "created");
        return mapOrderDelivery(order, response);
    }

    public Map<String, Object> createDelivery(Order order, Map<String, Object> request) {
        requireConfigured();

        String quoteId = text(request.get("quoteId"), order.getUberQuoteId(), null);
        if (quoteId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uber quoteId is required before creating delivery");
        }

        String pickupName = text(request.get("pickupName"), null, defaultPickupName);
        String pickupPhone = text(request.get("pickupPhone"), null, defaultPickupPhone);
        String pickupAddress = text(request.get("pickupAddress"), order.getPickupAddress(), defaultPickupAddress);
        String dropoffName = text(request.get("recipientName"), order.getRecipientName(), order.getCustomerName());
        String dropoffPhone = text(request.get("recipientPhone"), order.getRecipientPhone(), order.getPhone());
        String dropoffAddress = text(request.get("dropoffAddress"), order.getDropoffAddress(), buildDropoffAddress(order));

        if (pickupPhone == null || pickupAddress == null || dropoffName == null || dropoffPhone == null || dropoffAddress == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pickup and recipient contact details are required for Uber delivery");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("quote_id", quoteId);
        payload.put("pickup_name", pickupName);
        payload.put("pickup_phone_number", pickupPhone);
        payload.put("pickup_address", pickupAddress);
        payload.put("dropoff_name", dropoffName);
        payload.put("dropoff_phone_number", dropoffPhone);
        payload.put("dropoff_address", dropoffAddress);
        payload.put("manifest_items", manifestItems(order));

        if (request.get("pickupNotes") instanceof String pickupNotes && !pickupNotes.isBlank()) {
            payload.put("pickup_notes", pickupNotes);
        }
        if (request.get("dropoffNotes") instanceof String dropoffNotes && !dropoffNotes.isBlank()) {
            payload.put("dropoff_notes", dropoffNotes);
        }

        JsonNode response = postJson(customerPath("/deliveries"), payload);

        order.setDeliveryProvider("UBER_DIRECT");
        order.setDeliveryMethod("delivery");
        order.setDeliveryStatus(response.path("status").asText("created"));
        order.setPickupAddress(pickupAddress);
        order.setDropoffAddress(dropoffAddress);
        order.setRecipientName(dropoffName);
        order.setRecipientPhone(dropoffPhone);
        order.setExternalDeliveryId(response.path("id").asText(null));
        order.setTrackingUrl(response.path("tracking_url").asText(null));
        order.setDeliveryFee(readMoney(response));
        order.setDeliveryGatewayResponse(truncate(response.toString()));
        orderRepository.save(order);

        notificationService.notifyOrderStatusChanged(order, "Uber delivery", order.getDeliveryStatus());
        return mapOrderDelivery(order, response);
    }

    public Map<String, Object> refreshDeliveryStatus(Order order) {
        requireConfigured();

        if (order.getExternalDeliveryId() == null || order.getExternalDeliveryId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order does not have an Uber delivery ID");
        }

        JsonNode response = getJson(customerPath("/deliveries/" + encodePath(order.getExternalDeliveryId())));
        String status = response.path("status").asText(order.getDeliveryStatus());

        order.setDeliveryStatus(status);
        order.setTrackingUrl(response.path("tracking_url").asText(order.getTrackingUrl()));
        order.setDeliveryGatewayResponse(truncate(response.toString()));
        orderRepository.save(order);

        notificationService.notifyOrderStatusChanged(order, "Uber delivery status", status);
        return mapOrderDelivery(order, response);
    }

    public Map<String, Object> handleWebhook(String payload, String signature) {
        if (!isValidWebhookSignature(payload, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Uber webhook signature");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Uber webhook payload");
        }

        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        String deliveryId = firstText(data, "id", "delivery_id", "external_delivery_id");
        String status = firstText(data, "status", "delivery_status");

        if (deliveryId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uber webhook missing delivery id");
        }

        Order order = orderRepository.findByExternalDeliveryId(deliveryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for Uber delivery"));

        if (status != null) {
            order.setDeliveryStatus(status);
        }
        String trackingUrl = firstText(data, "tracking_url", "trackingUrl");
        if (trackingUrl != null) {
            order.setTrackingUrl(trackingUrl);
        }
        order.setDeliveryGatewayResponse(truncate(root.toString()));
        orderRepository.save(order);

        notificationService.notifyOrderStatusChanged(order, "Uber webhook delivery status", order.getDeliveryStatus());
        return mapOrderDelivery(order, root);
    }

    public Map<String, Object> configurationStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", isConfigured());
        status.put("hasClientId", clientId != null && !clientId.isBlank());
        status.put("hasClientSecret", clientSecret != null && !clientSecret.isBlank());
        status.put("hasCustomerId", customerId != null && !customerId.isBlank());
        status.put("hasWebhookSigningKey", webhookSigningKey != null && !webhookSigningKey.isBlank());
        status.put("scope", scope);
        status.put("tokenUrl", tokenUrl);
        status.put("apiBaseUrl", apiBaseUrl);
        return status;
    }

    private boolean isValidWebhookSignature(String payload, String signature) {
        if (webhookSigningKey == null || webhookSigningKey.isBlank()) {
            return true;
        }

        if (payload == null || signature == null || signature.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSigningKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(digest).equalsIgnoreCase(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }

    private JsonNode postJson(String path, Map<String, Object> payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseUberResponse(response);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Uber Direct");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Uber Direct request was interrupted");
        }
    }

    private JsonNode getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseUberResponse(response);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Uber Direct");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Uber Direct request was interrupted");
        }
    }

    private synchronized String accessToken() {
        if (cachedAccessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
            return cachedAccessToken;
        }

        try {
            String form = "client_id=" + formEncode(clientId)
                    + "&client_secret=" + formEncode(clientSecret)
                    + "&grant_type=client_credentials"
                    + "&scope=" + formEncode(scope);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = parseUberResponse(response);

            cachedAccessToken = body.path("access_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(3600);
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn));

            if (cachedAccessToken == null || cachedAccessToken.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Uber token response did not include access_token");
            }

            return cachedAccessToken;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach Uber auth");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Uber auth request was interrupted");
        }
    }

    private JsonNode parseUberResponse(HttpResponse<String> response) throws JacksonException {
        JsonNode body = response.body() == null || response.body().isBlank()
                ? objectMapper.readTree("{}")
                : objectMapper.readTree(response.body());

        if (response.statusCode() >= 400) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    body.path("message").asText(body.path("error").asText("Uber Direct request failed"))
            );
        }

        return body;
    }

    private List<Map<String, Object>> manifestItems(Order order) {
        List<Map<String, Object>> items = new ArrayList<>();

        if (order.getItems() == null || order.getItems().isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("name", "Yenkasa Store order " + order.getId());
            fallback.put("quantity", 1);
            items.add(fallback);
            return items;
        }

        for (OrderItem item : order.getItems()) {
            Map<String, Object> manifestItem = new LinkedHashMap<>();
            manifestItem.put("name", item.getBaleName() == null ? "Product" : item.getBaleName());
            manifestItem.put("quantity", item.getQuantity() == null ? 1 : item.getQuantity());
            items.add(manifestItem);
        }

        return items;
    }

    private Map<String, Object> mapOrderDelivery(Order order, JsonNode response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("deliveryProvider", order.getDeliveryProvider());
        result.put("deliveryStatus", order.getDeliveryStatus());
        result.put("externalDeliveryId", order.getExternalDeliveryId());
        result.put("uberQuoteId", order.getUberQuoteId());
        result.put("deliveryFee", order.getDeliveryFee());
        result.put("trackingUrl", order.getTrackingUrl());
        result.put("uber", response);
        return result;
    }

    private String buildDropoffAddress(Order order) {
        StringBuilder builder = new StringBuilder();
        appendAddressPart(builder, order.getAddress());
        appendAddressPart(builder, order.getArea());
        appendAddressPart(builder, order.getRegion());
        return builder.isEmpty() ? null : builder.toString();
    }

    private void appendAddressPart(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(", ");
        }
        builder.append(value.trim());
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null && !(value instanceof String text && text.isBlank())) {
            target.put(targetKey, value);
        }
    }

    private Double readMoney(JsonNode response) {
        if (response.path("fee").isNumber()) {
            return response.path("fee").asDouble() / 100.0;
        }
        if (response.path("fee_amount").isNumber()) {
            return response.path("fee_amount").asDouble() / 100.0;
        }
        return null;
    }

    private String customerPath(String path) {
        return "/customers/" + encodePath(customerId) + path;
    }

    private String text(Object value, String fallback, String defaultValue) {
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        if (defaultValue != null && !defaultValue.isBlank()) {
            return defaultValue.trim();
        }
        return null;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1900) {
            return value;
        }
        return value.substring(0, 1900);
    }

    private boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && customerId != null && !customerId.isBlank();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Uber Direct is not configured");
        }
    }

    private String formEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
