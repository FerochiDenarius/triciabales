package com.baleshop.baleshop.service;

import com.baleshop.baleshop.dto.RefundRequest;
import com.baleshop.baleshop.model.Order;
import com.baleshop.baleshop.model.OrderRefund;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.OrderRefundRepository;
import com.baleshop.baleshop.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RefundService {

    private static final String STATUS_REQUESTED = "REQUESTED";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_PROCESSED = "PROCESSED";

    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;
    private final PaystackService paystackService;
    private final NotificationService notificationService;

    public RefundService(
            OrderRepository orderRepository,
            OrderRefundRepository refundRepository,
            PaystackService paystackService,
            NotificationService notificationService
    ) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.paystackService = paystackService;
        this.notificationService = notificationService;
    }

    public List<Map<String, Object>> listRefunds() {
        return refundRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::serialize)
                .toList();
    }

    public Map<String, Object> requestRefund(Long orderId, RefundRequest request, User actor) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        double amount = request != null && request.getAmount() != null ? request.getAmount() : 0.0;
        String reason = request == null || request.getReason() == null ? "" : request.getReason().trim();

        validateRefundableOrder(order, amount, reason);

        OrderRefund refund = refundRepository
                .findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(orderId, List.of(STATUS_REQUESTED, STATUS_APPROVED, STATUS_PROCESSING))
                .orElseGet(OrderRefund::new);

        boolean isNewRefund = refund.getId() == null;
        refund.setOrder(order);
        refund.setAmount(amount);
        refund.setReason(reason);

        if (isNewRefund) {
            refund.setStatus(STATUS_REQUESTED);
            refund.setRequestedByUserId(actor.getId());
            refund.setRequestedByEmail(actor.getEmail());
        }

        OrderRefund saved = refundRepository.save(refund);

        order.setRefundRequested(true);
        order.setRefundAmount(amount);
        order.setRefundReason(reason);
        order.setRefundStatus(saved.getStatus());
        if (order.getRefundRequestedAt() == null) {
            order.setRefundRequestedAt(LocalDateTime.now());
        }
        if (!Boolean.TRUE.equals(order.getPayoutReleased()) && isPaidOrder(order)) {
            order.setPaymentStatus("payout_on_hold");
            order.setPayoutHeldAt(LocalDateTime.now());
            order.setPayoutHoldReason("Refund request pending review");
        }
        orderRepository.save(order);

        notificationService.notifySensitiveActivity(
                actor,
                "Refund requested for order #" + order.getId(),
                actor.getEmail() + " requested a refund of GHS " + money(amount) + " for order #" + order.getId() + ". Reason: " + reason
        );

        return serialize(saved);
    }

    public Map<String, Object> updateRefundStatus(Long refundId, String status, User actor) {
        OrderRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund request not found"));
        Order order = refund.getOrder();
        String nextStatus = normalizeStatus(status);

        if (STATUS_APPROVED.equals(nextStatus)) {
            if (!STATUS_REQUESTED.equalsIgnoreCase(refund.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only requested refunds can be approved");
            }
            markReviewed(refund, actor, STATUS_APPROVED);
        } else if (STATUS_REJECTED.equals(nextStatus)) {
            if (!STATUS_REQUESTED.equalsIgnoreCase(refund.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only requested refunds can be rejected");
            }
            markReviewed(refund, actor, STATUS_REJECTED);
            order.setRefundStatus(STATUS_REJECTED);
            order.setRefundReviewedAt(refund.getReviewedAt());
            releaseRefundHoldIfEligible(order);
        } else if (STATUS_PROCESSED.equals(nextStatus)) {
            if (!STATUS_APPROVED.equalsIgnoreCase(refund.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund must be approved before processing");
            }
            processRefund(refund, actor);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid refund status");
        }

        OrderRefund saved = refundRepository.save(refund);
        syncOrderRefundFields(order, saved);
        orderRepository.save(order);

        notificationService.notifySensitiveActivity(
                actor,
                "Refund " + saved.getStatus().toLowerCase(Locale.ROOT) + " for order #" + order.getId(),
                actor.getEmail() + " updated refund #" + saved.getId() + " for order #" + order.getId() + " to " + saved.getStatus() + "."
        );

        return serialize(saved);
    }

    private void processRefund(OrderRefund refund, User actor) {
        Order order = refund.getOrder();
        String paymentMethod = order.getPaymentMethod() == null ? "" : order.getPaymentMethod().trim();

        refund.setReviewedByUserId(actor.getId());
        refund.setReviewedByEmail(actor.getEmail());
        if (refund.getReviewedAt() == null) {
            refund.setReviewedAt(LocalDateTime.now());
        }

        if ("paystack".equalsIgnoreCase(paymentMethod) && order.getPaystackReference() != null && !order.getPaystackReference().isBlank()) {
            Map<String, Object> paystackRefund = paystackService.createRefund(order, refund.getAmount(), refund.getReason(), actor.getEmail());
            refund.setPaystackRefundId(String.valueOf(paystackRefund.get("id")));
            refund.setPaystackRefundStatus(String.valueOf(paystackRefund.get("status")));
            refund.setPaystackGatewayResponse(String.valueOf(paystackRefund.get("raw")));

            String paystackStatus = String.valueOf(paystackRefund.get("status"));
            if ("processed".equalsIgnoreCase(paystackStatus)) {
                refund.setStatus(STATUS_PROCESSED);
                refund.setProcessedAt(LocalDateTime.now());
            } else {
                refund.setStatus(STATUS_PROCESSING);
            }
            return;
        }

        refund.setStatus(STATUS_PROCESSED);
        refund.setProcessedAt(LocalDateTime.now());
    }

    private void markReviewed(OrderRefund refund, User actor, String status) {
        refund.setStatus(status);
        refund.setReviewedByUserId(actor.getId());
        refund.setReviewedByEmail(actor.getEmail());
        refund.setReviewedAt(LocalDateTime.now());
    }

    private void syncOrderRefundFields(Order order, OrderRefund refund) {
        order.setRefundRequested(true);
        order.setRefundAmount(refund.getAmount());
        order.setRefundReason(refund.getReason());
        order.setRefundStatus(refund.getStatus());
        order.setRefundReviewedAt(refund.getReviewedAt());
        order.setRefundProcessedAt(refund.getProcessedAt());

        if (STATUS_PROCESSED.equalsIgnoreCase(refund.getStatus())) {
            applyRefundedPaymentStatus(order, refund.getAmount());
        }
    }

    private void validateRefundableOrder(Order order, double amount, String reason) {
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund amount must be greater than zero");
        }

        double total = order.getTotal() == null ? 0.0 : order.getTotal();
        if (amount > total) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund amount cannot exceed order total");
        }

        if (reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund reason is required");
        }

        if (!isPaidOrder(order)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only paid orders can be refunded");
        }
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isPaidOrder(Order order) {
        String paymentStatus = order.getPaymentStatus() == null ? "" : order.getPaymentStatus().trim();
        String status = order.getStatus() == null ? "" : order.getStatus().trim();

        return "paid".equalsIgnoreCase(paymentStatus)
                || "ready_for_payout".equalsIgnoreCase(paymentStatus)
                || "payout_on_hold".equalsIgnoreCase(paymentStatus)
                || "payout_released".equalsIgnoreCase(paymentStatus)
                || "paid".equalsIgnoreCase(status)
                || order.getPaidAt() != null;
    }

    private boolean isPayoutReleaseEligible(Order order) {
        return Boolean.TRUE.equals(order.getConfirmedByBuyer())
                || "delivered".equalsIgnoreCase(order.getDeliveryStatus());
    }

    private void releaseRefundHoldIfEligible(Order order) {
        if (Boolean.TRUE.equals(order.getPayoutReleased())) {
            return;
        }

        if (!"payout_on_hold".equalsIgnoreCase(order.getPaymentStatus())) {
            return;
        }

        if (isPayoutReleaseEligible(order)) {
            order.setPaymentStatus("ready_for_payout");
            order.setPayoutHeldAt(null);
            order.setPayoutHoldReason(null);
        }
    }

    private void applyRefundedPaymentStatus(Order order, Double refundAmount) {
        double total = order.getTotal() == null ? 0.0 : order.getTotal();
        double amount = refundAmount == null ? 0.0 : refundAmount;

        order.setPaymentStatus(amount >= total ? "refunded" : "partially_refunded");
        order.setPayoutHeldAt(null);
        order.setPayoutHoldReason(null);
    }

    private Map<String, Object> serialize(OrderRefund refund) {
        Map<String, Object> result = new LinkedHashMap<>();
        Order order = refund.getOrder();

        result.put("id", refund.getId());
        result.put("orderId", order != null ? order.getId() : null);
        result.put("amount", refund.getAmount());
        result.put("reason", refund.getReason());
        result.put("status", refund.getStatus());
        result.put("requestedBy", refund.getRequestedByEmail());
        result.put("requestedByUserId", refund.getRequestedByUserId());
        result.put("reviewedBy", refund.getReviewedByEmail());
        result.put("reviewedByUserId", refund.getReviewedByUserId());
        result.put("reviewedAt", refund.getReviewedAt());
        result.put("processedAt", refund.getProcessedAt());
        result.put("paystackRefundId", refund.getPaystackRefundId());
        result.put("paystackRefundStatus", refund.getPaystackRefundStatus());
        result.put("createdAt", refund.getCreatedAt());
        return result;
    }

    private String money(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }
}
