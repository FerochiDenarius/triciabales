package com.baleshop.baleshop.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_refunds")
public class OrderRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    private Double amount;

    @Column(length = 1000)
    private String reason;

    private String status;
    private Long requestedByUserId;
    private String requestedByEmail;
    private Long reviewedByUserId;
    private String reviewedByEmail;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime processedAt;
    private String paystackRefundId;
    private String paystackRefundStatus;

    @Column(length = 4000)
    private String paystackGatewayResponse;

    @PrePersist
    public void beforeCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRequestedByUserId() {
        return requestedByUserId;
    }

    public void setRequestedByUserId(Long requestedByUserId) {
        this.requestedByUserId = requestedByUserId;
    }

    public String getRequestedByEmail() {
        return requestedByEmail;
    }

    public void setRequestedByEmail(String requestedByEmail) {
        this.requestedByEmail = requestedByEmail;
    }

    public Long getReviewedByUserId() {
        return reviewedByUserId;
    }

    public void setReviewedByUserId(Long reviewedByUserId) {
        this.reviewedByUserId = reviewedByUserId;
    }

    public String getReviewedByEmail() {
        return reviewedByEmail;
    }

    public void setReviewedByEmail(String reviewedByEmail) {
        this.reviewedByEmail = reviewedByEmail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public String getPaystackRefundId() {
        return paystackRefundId;
    }

    public void setPaystackRefundId(String paystackRefundId) {
        this.paystackRefundId = paystackRefundId;
    }

    public String getPaystackRefundStatus() {
        return paystackRefundStatus;
    }

    public void setPaystackRefundStatus(String paystackRefundStatus) {
        this.paystackRefundStatus = paystackRefundStatus;
    }

    public String getPaystackGatewayResponse() {
        return paystackGatewayResponse;
    }

    public void setPaystackGatewayResponse(String paystackGatewayResponse) {
        this.paystackGatewayResponse = paystackGatewayResponse;
    }
}
