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
    private static final double PLATFORM_SUBACCOUNT_PERCENTAGE_CHARGE = 10.0;
    private static final String SPLIT_MODE_SUBACCOUNT = "paystack_subaccount";
    private static final String PAYOUT_STATUS_NOT_REQUIRED = "not_required";

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
            throw new PaymentApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ORDER_TOTAL",
                    "Order total must be greater than zero",
                    "Checkout cannot initialize Paystack until the order has a valid positive total."
            );
        }

        if ("paid".equalsIgnoreCase(order.getPaymentStatus())) {
            throw new PaymentApiException(
                    HttpStatus.BAD_REQUEST,
                    "ORDER_ALREADY_PAID",
                    "Order is already paid",
                    "Do not initialize another Paystack transaction for an order that has already been verified as paid."
            );
        }

        String customerEmail = cleanEmail(email);
        if (customerEmail == null && order.getCardEmail() != null) {
            customerEmail = cleanEmail(order.getCardEmail());
        }
        if (customerEmail == null && order.getBuyerEmail() != null) {
            customerEmail = cleanEmail(order.getBuyerEmail());
        }
        if (customerEmail == null) {
            throw new PaymentApiException(
                    HttpStatus.BAD_REQUEST,
                    "CUSTOMER_EMAIL_REQUIRED",
                    "A valid email is required for Paystack payment",
                    "Ask the buyer for a valid email address before checkout."
            );
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
        order.setSellerSubaccountCode(String.join(",", paymentSplit.sellerSubaccountCodes));
        order.setGrossAmount(pesewasToAmount(amountInPesewas));
        order.setPlatformCommissionAmount(pesewasToAmount(paymentSplit.commissionPesewas));
        order.setSellerSettlementAmount(pesewasToAmount(paymentSplit.sellerPayoutPesewas));
        order.setCommissionAmount(pesewasToAmount(paymentSplit.commissionPesewas));
        order.setSellerPayoutAmount(pesewasToAmount(paymentSplit.sellerPayoutPesewas));
        order.setCommissionStatus("split_pending");
        order.setPayoutStatus(PAYOUT_STATUS_NOT_REQUIRED);
        order.setPayoutReleased(false);
        orderRepository.save(order);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("reference", reference);
        result.put("authorizationUrl", order.getPaystackAuthorizationUrl());
        result.put("accessCode", order.getPaystackAccessCode());
        result.put("amount", order.getTotal());
        result.put("splitMode", order.getPaystackSplitMode());
        result.put("sellerCount", paymentSplit.sellerCount);
        result.put("sellerSubaccountCode", order.getSellerSubaccountCode());
        result.put("grossAmount", order.getGrossAmount());
        result.put("platformCommissionAmount", order.getPlatformCommissionAmount());
        result.put("sellerSettlementAmount", order.getSellerSettlementAmount());
        result.put("payoutStatus", order.getPayoutStatus());
        return result;
    }

    public User createOrUpdateSellerSubaccount(User seller) {
        requireConfigured();

        if (seller == null || seller.getId() == null) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "SELLER_REQUIRED", "Seller is required", "A seller account is required before creating a Paystack subaccount.");
        }
        validateSellerPayoutDetails(seller);

        String businessName = firstNonBlank(seller.getShopName(), seller.getName(), "Yenkasa seller " + seller.getId());
        String settlementBank = resolveSettlementBankCode(seller);
        String settlementAccountNumber = seller.getMomoNumber().trim();
        String settlementAccountName = firstNonBlank(seller.getBankAccountName(), seller.getName(), businessName);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sellerId", seller.getId());
        metadata.put("sellerEmail", seller.getEmail());
        metadata.put("payoutMethod", "momo");
        metadata.put("momoNumber", settlementAccountNumber);
        metadata.put("momoNetwork", seller.getMomoNetwork());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("business_name", businessName);
        payload.put("settlement_bank", settlementBank);
        payload.put("account_number", settlementAccountNumber);
        payload.put("percentage_charge", PLATFORM_SUBACCOUNT_PERCENTAGE_CHARGE);
        payload.put("description", "Yenkasa Store seller #" + seller.getId());
        payload.put("primary_contact_email", seller.getEmail());
        payload.put("primary_contact_name", settlementAccountName);
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
            throw new PaymentApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SELLER_SUBACCOUNT_CREATE_FAILED",
                    "Seller Paystack subaccount could not be created",
                    response.path("message").asText("Check the seller MoMo payout settings and Paystack dashboard.")
            );
        }

        seller.setPayoutMethod("momo");
        seller.setBankCode(settlementBank);
        seller.setBankName(normalizeMomoNetworkName(seller.getMomoNetwork()));
        seller.setBankAccountNumber(settlementAccountNumber);
        seller.setBankAccountName(settlementAccountName);
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
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "PAYSTACK_REFERENCE_REQUIRED", "Payment reference is required", "Provide the Paystack reference returned by transaction initialization.");
        }

        Order order = orderRepository.findByPaystackReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for payment reference"));

        JsonNode response = getJson("/transaction/verify/" + encodePath(reference));
        JsonNode data = response.path("data");
        String paystackStatus = data.path("status").asText("");

        if (!response.path("status").asBoolean(false) || data.isMissingNode()) {
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_VERIFY_FAILED", "Paystack transaction verification failed", response.path("message").asText("Check the Paystack transaction reference."));
        }

        long expectedAmount = amountToPesewas(order.getTotal());
        long paidAmount = data.path("amount").asLong(0);

        if ("success".equalsIgnoreCase(paystackStatus) && expectedAmount == paidAmount) {
            validateSplitMetadataForSuccessfulPayment(order);
            markOrderPaid(order, data);
        } else if ("success".equalsIgnoreCase(paystackStatus)) {
            order.setPaystackGatewayResponse("amount_mismatch");
            orderRepository.save(order);
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "PAYSTACK_AMOUNT_MISMATCH", "Payment amount does not match order total", "Do not mark this order paid until the Paystack amount matches the Yenkasa order total.");
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

    public Map<String, Object> auditPayments() {
        return Map.of(
                "success", true,
                "message", "Use /api/paystack/audit/{reference} to verify live Paystack status for a specific real-cash payment.",
                "orders", orderRepository.findAll().stream()
                        .filter(order -> order.getPaystackReference() != null && !order.getPaystackReference().isBlank())
                        .sorted((left, right) -> Long.compare(
                                right.getId() == null ? 0 : right.getId(),
                                left.getId() == null ? 0 : left.getId()
                        ))
                        .map(order -> paymentAudit(order, null))
                        .toList()
        );
    }

    public Map<String, Object> auditPayment(String reference) {
        requireConfigured();
        if (reference == null || reference.isBlank()) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "PAYSTACK_REFERENCE_REQUIRED", "Payment reference is required", "Provide the Paystack reference to audit the transaction.");
        }
        Order order = orderRepository.findByPaystackReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for payment reference"));
        JsonNode response = getJson("/transaction/verify/" + encodePath(reference));
        JsonNode data = response.path("data");
        if (!response.path("status").asBoolean(false) || data.isMissingNode()) {
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_VERIFY_FAILED", "Paystack transaction verification failed", response.path("message").asText("Check the Paystack transaction reference."));
        }
        return paymentAudit(order, data);
    }

    public String transactionStatus(String reference) {
        requireConfigured();
        if (reference == null || reference.isBlank()) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "PAYSTACK_REFERENCE_REQUIRED", "Payment reference is required", "Provide the Paystack reference before changing this order.");
        }

        JsonNode response = getJson("/transaction/verify/" + encodePath(reference));
        JsonNode data = response.path("data");
        if (!response.path("status").asBoolean(false) || data.isMissingNode()) {
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_VERIFY_FAILED", "Paystack transaction verification failed", response.path("message").asText("Check the Paystack transaction reference."));
        }
        return data.path("status").asText("");
    }

    public User ensureSellerSubaccount(User seller) {
        if (seller == null || seller.getId() == null) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "SELLER_REQUIRED", "Seller is required", "A seller account is required before creating a Paystack subaccount.");
        }

        validateSellerPayoutDetails(seller);

        return createOrUpdateSellerSubaccount(seller);
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
        order.setPaystackTransactionId(data.path("id").isMissingNode() ? null : data.path("id").asText(null));
        if (order.getPaystackSplitMode() != null && !order.getPaystackSplitMode().isBlank()) {
            order.setCommissionStatus("split_processed");
        }
        order.setPayoutStatus(PAYOUT_STATUS_NOT_REQUIRED);
        order.setPayoutReleased(false);
        order.setPayoutReleasedAt(null);
        order.setPayoutHeldAt(null);
        order.setPayoutHoldReason(null);
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
            split.mode = SPLIT_MODE_SUBACCOUNT;
            split.singleSellerSubaccount = seller.subaccountCode;
            split.sellerSubaccountCodes.add(seller.subaccountCode);
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
            split.sellerSubaccountCodes.add(seller.subaccountCode);
        }

        split.mode = SPLIT_MODE_SUBACCOUNT;
        split.splitReference = "YSPLIT-" + order.getId() + "-" + System.currentTimeMillis();
        split.feeBearer = "subaccount";
        split.feeBearerSubaccount = feeBearerSubaccount;
        split.commissionPesewas = commissionFor(amountInPesewas);
        split.sellerPayoutPesewas = amountInPesewas - split.commissionPesewas;
        subaccounts = distributeSellerSettlement(sellerShares, split.sellerPayoutPesewas);

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
                .orElseThrow(() -> new PaymentApiException(HttpStatus.BAD_REQUEST, "SELLER_NOT_FOUND", "Seller not found for Paystack split", "Check that every checkout item belongs to an active seller."));

        seller = ensureSellerSubaccount(seller);
        String subaccountCode = seller.getPaystackSubaccountCode();

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

    private void validateSellerPayoutDetails(User seller) {
        if (seller.getMomoNumber() == null || seller.getMomoNumber().isBlank()) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "SELLER_SUBACCOUNT_MISSING", "Seller payout details are missing", "Ask seller to update MoMo payout settings before checkout.");
        }
        if (seller.getMomoNetwork() == null || seller.getMomoNetwork().isBlank()) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "SELLER_SUBACCOUNT_MISSING", "Seller payout details are missing", "Ask seller to update MoMo payout settings before checkout.");
        }
        if (firstNonBlank(seller.getBankAccountName(), seller.getName(), seller.getShopName()) == null) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "SELLER_SUBACCOUNT_MISSING", "Seller payout details are missing", "Ask seller to update MoMo payout settings before checkout.");
        }
        resolveSettlementBankCode(seller);
    }

    private String resolveSettlementBankCode(User seller) {
        String explicitBankCode = seller.getBankCode();
        if (explicitBankCode != null && !explicitBankCode.isBlank()) {
            return explicitBankCode.trim();
        }
        return getBankCode(seller.getMomoNetwork());
    }

    private String getBankCode(String network) {
        if (network == null || network.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported network");
        }

        return switch (network.trim().toLowerCase(Locale.ROOT)) {
            case "mtn", "mtn momo", "mtn mobile money" -> "MTN";
            case "vodafone", "telecel", "vodafone cash", "telecel cash" -> "VOD";
            case "airteltigo", "airtel tigo", "airteltigo money" -> "ATL";
            default -> throw new PaymentApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MOMO_NETWORK", "Seller payout details are missing", "Use MTN, Vodafone/Telecel, or AirtelTigo for seller MoMo payout settings.");
        };
    }

    private void validateSplitMetadataForSuccessfulPayment(Order order) {
        if (order.getPaystackSplitMode() == null || order.getPaystackSplitMode().isBlank()
                || order.getSellerSubaccountCode() == null || order.getSellerSubaccountCode().isBlank()
                || order.getPlatformCommissionAmount() == null
                || order.getSellerSettlementAmount() == null) {
            throw new PaymentApiException(
                    HttpStatus.BAD_REQUEST,
                    "PAYSTACK_SPLIT_METADATA_MISSING",
                    "Payment was successful but split metadata is missing",
                    "Check the Paystack Dashboard before marking this order paid. This order may have been created under the old payout flow."
            );
        }
    }

    private List<Map<String, Object>> distributeSellerSettlement(Map<Long, SellerShare> sellerShares, long totalSettlementPesewas) {
        List<Map<String, Object>> subaccounts = new ArrayList<>();
        long totalGross = sellerShares.values().stream().mapToLong(seller -> seller.grossPesewas).sum();
        long allocated = 0;
        int index = 0;
        int count = sellerShares.size();

        for (SellerShare seller : sellerShares.values()) {
            long share = index == count - 1
                    ? totalSettlementPesewas - allocated
                    : BigDecimal.valueOf(totalSettlementPesewas)
                            .multiply(BigDecimal.valueOf(seller.grossPesewas))
                            .divide(BigDecimal.valueOf(Math.max(totalGross, 1)), 0, RoundingMode.HALF_UP)
                            .longValueExact();
            allocated += share;
            seller.payoutPesewas = share;

            Map<String, Object> subaccount = new LinkedHashMap<>();
            subaccount.put("subaccount", seller.subaccountCode);
            subaccount.put("share", share);
            subaccounts.add(subaccount);
            index++;
        }

        return subaccounts;
    }

    private Map<String, Object> paymentAudit(Order order, JsonNode paystackData) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("orderId", order.getId());
        audit.put("paystackReference", order.getPaystackReference());
        audit.put("paystackTransactionId", order.getPaystackTransactionId());
        audit.put("transactionStatus", paystackData == null ? order.getPaystackGatewayResponse() : paystackData.path("status").asText(""));
        audit.put("buyerEmail", firstNonBlank(order.getCardEmail(), order.getUser() == null ? null : order.getUser().getEmail()));
        audit.put("seller", firstNonBlank(order.getSellerName(), "Multiple sellers"));
        audit.put("sellerSubaccountCode", order.getSellerSubaccountCode());
        audit.put("grossAmount", order.getGrossAmount());
        audit.put("platformCommission", order.getPlatformCommissionAmount());
        audit.put("sellerExpectedSettlement", order.getSellerSettlementAmount());
        audit.put("splitUsed", order.getPaystackSplitMode() != null && !order.getPaystackSplitMode().isBlank());
        audit.put("splitMode", order.getPaystackSplitMode());
        audit.put("payoutStatus", order.getPayoutStatus());
        audit.put("settlementStatus", "handled_by_paystack");
        audit.put("message", legacyPayoutStatus(order)
                ? "This order was created under the old payout flow. Check Paystack Dashboard before taking action."
                : "Payment split handled by Paystack. Manual payout release is not required.");
        return audit;
    }

    private boolean legacyPayoutStatus(Order order) {
        String payoutStatus = order.getPayoutStatus() == null ? "" : order.getPayoutStatus().trim();
        String paymentStatus = order.getPaymentStatus() == null ? "" : order.getPaymentStatus().trim();
        return "pending".equalsIgnoreCase(payoutStatus)
                || "released".equalsIgnoreCase(payoutStatus)
                || "ready_for_payout".equalsIgnoreCase(paymentStatus)
                || "payout_on_hold".equalsIgnoreCase(paymentStatus)
                || "payout_released".equalsIgnoreCase(paymentStatus);
    }

    private String normalizeMomoNetworkName(String network) {
        if (network == null || network.isBlank()) {
            return null;
        }

        return switch (network.trim().toLowerCase(Locale.ROOT)) {
            case "mtn", "mtn momo", "mtn mobile money" -> "MTN";
            case "vodafone", "telecel", "vodafone cash", "telecel cash" -> "Vodafone";
            case "airteltigo", "airtel tigo", "airteltigo money" -> "AirtelTigo";
            default -> network.trim();
        };
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
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_UNREACHABLE", "Paystack transaction initialization failed", "Could not reach Paystack. Try again and check network/API key configuration.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_REQUEST_INTERRUPTED", "Paystack transaction initialization failed", "The Paystack request was interrupted.");
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
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_UNREACHABLE", "Paystack request failed", "Could not reach Paystack. Try again and check network/API key configuration.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_REQUEST_INTERRUPTED", "Paystack request failed", "The Paystack request was interrupted.");
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
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_UNREACHABLE", "Paystack request failed", "Could not reach Paystack. Try again and check network/API key configuration.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PAYSTACK_REQUEST_INTERRUPTED", "Paystack request failed", "The Paystack request was interrupted.");
        }
    }

    private JsonNode parsePaystackResponse(HttpResponse<String> response) throws JacksonException {
        JsonNode body = objectMapper.readTree(response.body());

        if (response.statusCode() >= 400) {
            throw new PaymentApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PAYSTACK_REQUEST_FAILED",
                    body.path("message").asText("Paystack request failed"),
                    body.toString()
            );
        }

        return body;
    }

    private void requireConfigured() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new PaymentApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYSTACK_NOT_CONFIGURED", "Paystack is not configured", "Set PAYSTACK_SECRET_KEY before accepting Paystack payments.");
        }

        if (requireLiveKeys && !isLiveSecretKey()) {
            throw new PaymentApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYSTACK_LIVE_KEY_REQUIRED", "Paystack live secret key is required", "Set PAYSTACK_SECRET_KEY to an sk_live key for real-cash payments.");
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
        return null;
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
        private final List<String> sellerSubaccountCodes = new ArrayList<>();
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
