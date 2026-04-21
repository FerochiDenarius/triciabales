package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.OrderItem;
import com.baleshop.baleshop.model.OrderRefund;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.OrderRefundRepository;
import com.baleshop.baleshop.repository.OrderRepository;
import com.baleshop.baleshop.repository.UserRepository;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PaystackService {

    private static final String PAYSTACK_BASE_URL = "https://api.paystack.co";
    private static final BigDecimal PLATFORM_COMMISSION_RATE = BigDecimal.valueOf(0.10);

    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.paystack.secret-key:}")
    private String secretKey;

    @Value("${app.paystack.public-key:}")
    private String publicKey;

    @Value("${app.paystack.require-live-keys:false}")
    private boolean requireLiveKeys;

    @Value("${app.frontend-base-url:https://www.yenkasa.xyz/store}")
    private String frontendBaseUrl;

    public PaystackService(
            OrderRepository orderRepository,
            OrderRefundRepository refundRepository,
            UserRepository userRepository,
            NotificationService notificationService,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @PostConstruct
    public void warnIfUnconfigured() {
        if (secretKey == null || secretKey.isBlank()) {
            System.out.println("⚠️ Paystack is not configured. Set PAYSTACK_SECRET_KEY before accepting Paystack payments.");
        } else if (requireLiveKeys && !isLiveSecretKey()) {
            System.out.println("⚠️ Paystack live keys are required, but PAYSTACK_SECRET_KEY is not an sk_live key.");
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", secretKey != null && !secretKey.isBlank());
        result.put("secretKeyMode", keyMode(secretKey, "sk"));
        result.put("publicKeyConfigured", publicKey != null && !publicKey.isBlank());
        result.put("publicKeyMode", keyMode(publicKey, "pk"));
        result.put("requireLiveKeys", requireLiveKeys);
        result.put("webhookPath", "/api/paystack/webhook");
        result.put("storeWebhookUrl", "https://www.yenkasa.xyz/triciabales-api/api/paystack/webhook");
        result.put("callbackUrl", frontendBaseUrl + "/paystack/callback");
        return result;
    }

    public Map<String, Object> listGhanaBanks() {
        requireConfigured();

        JsonNode response = getJson("/bank?currency=GHS&type=ghipss");
        JsonNode data = response.path("data");
        List<Map<String, Object>> banks = new ArrayList<>();

        if (data.isArray()) {
            for (JsonNode bank : data) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", bank.path("name").asText(""));
                item.put("code", bank.path("code").asText(""));
                item.put("slug", bank.path("slug").asText(""));
                item.put("currency", bank.path("currency").asText("GHS"));
                item.put("type", bank.path("type").asText("ghipss"));
                if (!String.valueOf(item.get("name")).isBlank() && !String.valueOf(item.get("code")).isBlank()) {
                    banks.add(item);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", response.path("status").asBoolean(false));
        result.put("currency", "GHS");
        result.put("type", "ghipss");
        result.put("banks", banks);
        return result;
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
        PaymentSplit paymentSplit = buildPaymentSplit(order, amountInPesewas);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", order.getId());
        metadata.put("buyerId", order.getBuyerId());
        metadata.put("sellerId", order.getSellerId());
        metadata.put("sellerCount", paymentSplit.sellerCount);
        metadata.put("splitMode", paymentSplit.mode);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", customerEmail);
        payload.put("amount", amountInPesewas);
        payload.put("currency", "GHS");
        payload.put("reference", reference);
        payload.put("callback_url", frontendBaseUrl + "/paystack/callback");
        payload.put("metadata", metadata);
        if (paymentSplit.singleSellerSubaccount != null) {
            payload.put("subaccount", paymentSplit.singleSellerSubaccount);
            payload.put("transaction_charge", paymentSplit.commissionPesewas);
            payload.put("bearer", "subaccount");
        }
        if (paymentSplit.dynamicSplit != null) {
            payload.put("split", paymentSplit.dynamicSplit);
        }

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
        order.setPaystackSplitMode(paymentSplit.mode);
        order.setPaystackSplitReference(paymentSplit.splitReference);
        order.setPaystackFeeBearer(paymentSplit.feeBearerSubaccount == null
                ? paymentSplit.feeBearer
                : paymentSplit.feeBearer + ":" + paymentSplit.feeBearerSubaccount);
        order.setPaystackSplitPayload(paymentSplit.dynamicSplit == null ? null : toJsonString(paymentSplit.dynamicSplit));
        order.setCommissionAmount(pesewasToAmount(paymentSplit.commissionPesewas));
        order.setSellerPayoutAmount(pesewasToAmount(paymentSplit.sellerPayoutPesewas));
        order.setCommissionStatus("paystack_split_pending");
        orderRepository.save(order);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("reference", reference);
        result.put("authorizationUrl", order.getPaystackAuthorizationUrl());
        result.put("accessCode", order.getPaystackAccessCode());
        result.put("amount", order.getTotal());
        result.put("splitMode", order.getPaystackSplitMode());
        result.put("sellerCount", paymentSplit.sellerCount);
        return result;
    }

    public User createOrUpdateSellerSubaccount(User seller) {
        requireConfigured();

        if (seller == null || seller.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller is required");
        }
        if (seller.getBankCode() == null || seller.getBankCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller bank code is required for Paystack subaccount setup");
        }
        if (seller.getBankAccountNumber() == null || seller.getBankAccountNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller account number is required for Paystack subaccount setup");
        }

        String businessName = firstNonBlank(seller.getShopName(), seller.getName(), "Yenkasa seller " + seller.getId());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sellerId", seller.getId());
        metadata.put("sellerEmail", seller.getEmail());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("business_name", businessName);
        payload.put("settlement_bank", seller.getBankCode().trim());
        payload.put("account_number", seller.getBankAccountNumber().trim());
        payload.put("percentage_charge", PLATFORM_COMMISSION_RATE.multiply(BigDecimal.valueOf(100)).doubleValue());
        payload.put("description", "Yenkasa Store seller #" + seller.getId());
        payload.put("primary_contact_email", seller.getEmail());
        payload.put("primary_contact_name", seller.getName());
        payload.put("primary_contact_phone", seller.getPhone());
        payload.put("metadata", toJsonString(metadata));

        JsonNode response;
        if (seller.getPaystackSubaccountCode() == null || seller.getPaystackSubaccountCode().isBlank()) {
            response = postJson("/subaccount", payload);
        } else {
            response = putJson("/subaccount/" + encodePath(seller.getPaystackSubaccountCode()), payload);
        }

        JsonNode data = response.path("data");
        if (!response.path("status").asBoolean(false) || data.isMissingNode()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, response.path("message").asText("Could not configure seller Paystack subaccount"));
        }

        seller.setPaystackSubaccountCode(data.path("subaccount_code").asText(seller.getPaystackSubaccountCode()));
        seller.setPaystackSubaccountId(data.path("id").asText(seller.getPaystackSubaccountId()));
        seller.setPaystackSubaccountVerified(data.path("is_verified").asBoolean(false));
        seller.setPaystackSubaccountStatus(data.path("active").asBoolean(false) ? "active" : "inactive");
        if (data.path("account_name").asText(null) != null && !data.path("account_name").asText("").isBlank()) {
            seller.setBankAccountName(data.path("account_name").asText());
        }

        return userRepository.save(seller);
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
            return timingSafeEquals(toHex(digest), signature);
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

        if (event != null && event.toLowerCase(Locale.ROOT).startsWith("refund.")) {
            updateRefundFromWebhook(root.path("data"));
        }
    }

    public Map<String, Object> createRefund(Order order, Double amount, String reason, String actorEmail) {
        requireConfigured();

        if (order.getPaystackReference() == null || order.getPaystackReference().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order does not have a Paystack transaction reference");
        }

        if (amount == null || amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund amount must be greater than zero");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transaction", order.getPaystackReference());
        payload.put("amount", amountToPesewas(amount));
        payload.put("currency", "GHS");
        payload.put("customer_note", reason == null || reason.isBlank() ? "Refund for order #" + order.getId() : reason);
        payload.put("merchant_note", "Refund for order #" + order.getId() + " by " + (actorEmail == null ? "Yenkasa Store" : actorEmail));

        JsonNode response = postJson("/refund", payload);
        JsonNode data = response.path("data");

        if (!response.path("status").asBoolean(false) || data.isMissingNode()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, response.path("message").asText("Could not create Paystack refund"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", data.path("id").asText(null));
        result.put("status", data.path("status").asText(null));
        result.put("amount", data.path("amount").asLong(0));
        result.put("currency", data.path("currency").asText(null));
        result.put("message", response.path("message").asText("Refund queued"));
        result.put("raw", data.toString());
        return result;
    }

    private void markOrderPaid(Order order, JsonNode data) {
        if ("paid".equalsIgnoreCase(order.getPaymentStatus())) {
            return;
        }

        order.setStatus("paid");
        order.setPaymentStatus("paid");
        order.setPaymentMethod("paystack");
        order.setPaystackGatewayResponse(data.path("gateway_response").asText("success"));
        if (order.getPaystackSplitMode() != null && !order.getPaystackSplitMode().isBlank()) {
            order.setCommissionStatus("collected");
        }
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);
        notificationService.notifyPaymentReceived(order);
    }

    private PaymentSplit buildPaymentSplit(Order order, long amountInPesewas) {
        Map<Long, SellerShare> sellerShares = new LinkedHashMap<>();

        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getSellerId() == null) {
                    continue;
                }

                long lineTotal = amountToPesewas(lineTotal(item));
                SellerShare share = sellerShares.computeIfAbsent(item.getSellerId(), this::sellerShare);
                share.grossPesewas += lineTotal;
                share.items.add(item);
            }
        }

        if (sellerShares.isEmpty() && order.getSellerId() != null) {
            SellerShare share = sellerShare(order.getSellerId());
            share.grossPesewas = amountInPesewas;
            sellerShares.put(order.getSellerId(), share);
        }

        if (sellerShares.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order does not have seller information for Paystack split");
        }

        PaymentSplit split = new PaymentSplit();
        split.sellerCount = sellerShares.size();

        if (sellerShares.size() == 1) {
            SellerShare seller = sellerShares.values().iterator().next();
            split.mode = "single_subaccount";
            split.singleSellerSubaccount = seller.subaccountCode;
            split.feeBearer = "subaccount";
            split.commissionPesewas = commissionFor(amountInPesewas);
            split.sellerPayoutPesewas = amountInPesewas - split.commissionPesewas;
            applyItemSplitAmounts(sellerShares, split.commissionPesewas, split.sellerPayoutPesewas);
            return split;
        }

        List<Map<String, Object>> subaccounts = new ArrayList<>();
        long totalSellerPayout = 0;
        String feeBearerSubaccount = null;
        long largestSellerPayout = 0;
        for (SellerShare seller : sellerShares.values()) {
            long commission = commissionFor(seller.grossPesewas);
            long sellerPayout = seller.grossPesewas - commission;
            seller.commissionPesewas = commission;
            seller.payoutPesewas = sellerPayout;
            totalSellerPayout += sellerPayout;
            if (sellerPayout > largestSellerPayout) {
                largestSellerPayout = sellerPayout;
                feeBearerSubaccount = seller.subaccountCode;
            }

            Map<String, Object> subaccount = new LinkedHashMap<>();
            subaccount.put("subaccount", seller.subaccountCode);
            subaccount.put("share", sellerPayout);
            subaccounts.add(subaccount);
        }

        split.mode = "dynamic_multi_split";
        split.splitReference = "YSPLIT-" + order.getId() + "-" + System.currentTimeMillis();
        split.feeBearer = "subaccount";
        split.feeBearerSubaccount = feeBearerSubaccount;
        split.commissionPesewas = amountInPesewas - totalSellerPayout;
        split.sellerPayoutPesewas = totalSellerPayout;

        Map<String, Object> dynamicSplit = new LinkedHashMap<>();
        dynamicSplit.put("type", "flat");
        dynamicSplit.put("bearer_type", split.feeBearer);
        dynamicSplit.put("bearer_subaccount", split.feeBearerSubaccount);
        dynamicSplit.put("reference", split.splitReference);
        dynamicSplit.put("subaccounts", subaccounts);
        split.dynamicSplit = dynamicSplit;

        applyItemSplitAmounts(sellerShares, split.commissionPesewas, split.sellerPayoutPesewas);
        return split;
    }

    private SellerShare sellerShare(Long sellerId) {
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller not found for Paystack split"));

        String subaccountCode = seller.getPaystackSubaccountCode();
        if (subaccountCode == null || subaccountCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seller #" + sellerId + " does not have an active Paystack subaccount");
        }

        SellerShare share = new SellerShare();
        share.sellerId = sellerId;
        share.subaccountCode = subaccountCode.trim();
        return share;
    }

    private void applyItemSplitAmounts(Map<Long, SellerShare> sellerShares, long orderCommissionPesewas, long orderSellerPayoutPesewas) {
        for (SellerShare seller : sellerShares.values()) {
            for (OrderItem item : seller.items) {
                long lineTotal = amountToPesewas(lineTotal(item));
                long commission = commissionFor(lineTotal);
                long payout = lineTotal - commission;
                item.setLineTotal(pesewasToAmount(lineTotal));
                item.setCommissionAmount(pesewasToAmount(commission));
                item.setSellerPayoutAmount(pesewasToAmount(payout));
                item.setPaystackSubaccountCode(seller.subaccountCode);
            }
        }
    }

    private long commissionFor(long amountInPesewas) {
        return BigDecimal.valueOf(amountInPesewas)
                .multiply(PLATFORM_COMMISSION_RATE)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private double lineTotal(OrderItem item) {
        double price = item.getPrice() == null ? 0.0 : item.getPrice();
        int quantity = item.getQuantity() == null ? 1 : Math.max(1, item.getQuantity());
        return item.getLineTotal() != null ? item.getLineTotal() : price * quantity;
    }

    private void updateRefundFromWebhook(JsonNode data) {
        String transactionReference = data.path("transaction_reference").asText("");
        if (transactionReference.isBlank()) {
            transactionReference = data.path("transaction").path("reference").asText("");
        }
        if (transactionReference.isBlank()) {
            return;
        }

        OrderRefund refund = refundRepository
                .findFirstByOrderPaystackReferenceOrderByCreatedAtDesc(transactionReference)
                .orElse(null);
        if (refund == null) {
            return;
        }

        String paystackStatus = data.path("status").asText("");
        refund.setPaystackRefundStatus(paystackStatus);
        refund.setPaystackGatewayResponse(data.toString());

        String refundId = data.path("id").asText("");
        if (refundId.isBlank()) {
            refundId = data.path("refund_reference").asText("");
        }
        if (!refundId.isBlank()) {
            refund.setPaystackRefundId(refundId);
        }

        if ("processed".equalsIgnoreCase(paystackStatus)) {
            refund.setStatus("PROCESSED");
            refund.setProcessedAt(LocalDateTime.now());
        } else if ("failed".equalsIgnoreCase(paystackStatus)) {
            refund.setStatus("FAILED");
        } else if ("needs-attention".equalsIgnoreCase(paystackStatus)) {
            refund.setStatus("NEEDS_ATTENTION");
        } else if (!paystackStatus.isBlank()) {
            refund.setStatus("PROCESSING");
        }

        Order order = refund.getOrder();
        if (order != null) {
            order.setRefundStatus(refund.getStatus());
            order.setRefundProcessedAt(refund.getProcessedAt());
            if ("PROCESSED".equalsIgnoreCase(refund.getStatus())) {
                applyRefundedPaymentStatus(order, refund.getAmount());
            }
            orderRepository.save(order);
        }

        refundRepository.save(refund);
    }

    private void applyRefundedPaymentStatus(Order order, Double refundAmount) {
        double total = order.getTotal() == null ? 0.0 : order.getTotal();
        double amount = refundAmount == null ? 0.0 : refundAmount;

        order.setPaymentStatus(amount >= total ? "refunded" : "partially_refunded");
        order.setPayoutHeldAt(null);
        order.setPayoutHoldReason(null);
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

    private JsonNode putJson(String path, Map<String, Object> payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PAYSTACK_BASE_URL + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
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

        if (requireLiveKeys && !isLiveSecretKey()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Paystack live secret key is required");
        }
    }

    private long amountToPesewas(Double amount) {
        return BigDecimal.valueOf(amount == null ? 0 : amount)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private double pesewasToAmount(long amountInPesewas) {
        return BigDecimal.valueOf(amountInPesewas)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .doubleValue();
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private boolean timingSafeEquals(String expected, String received) {
        String cleanExpected = expected == null ? "" : expected.trim().toLowerCase(Locale.ROOT);
        String cleanReceived = received == null ? "" : received.trim().toLowerCase(Locale.ROOT);

        if (cleanExpected.length() != cleanReceived.length()) {
            return false;
        }

        return MessageDigest.isEqual(
                cleanExpected.getBytes(StandardCharsets.UTF_8),
                cleanReceived.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean isLiveSecretKey() {
        return secretKey != null && secretKey.trim().startsWith("sk_live_");
    }

    private String keyMode(String key, String prefix) {
        if (key == null || key.isBlank()) {
            return "missing";
        }

        String trimmed = key.trim();
        if (trimmed.startsWith(prefix + "_live_")) {
            return "live";
        }
        if (trimmed.startsWith(prefix + "_test_")) {
            return "test";
        }
        return "unknown";
    }

    private static class PaymentSplit {
        private String mode;
        private int sellerCount;
        private String singleSellerSubaccount;
        private Map<String, Object> dynamicSplit;
        private String splitReference;
        private String feeBearer;
        private String feeBearerSubaccount;
        private long commissionPesewas;
        private long sellerPayoutPesewas;
    }

    private static class SellerShare {
        private Long sellerId;
        private String subaccountCode;
        private long grossPesewas;
        private long commissionPesewas;
        private long payoutPesewas;
        private final List<OrderItem> items = new ArrayList<>();
    }
}
