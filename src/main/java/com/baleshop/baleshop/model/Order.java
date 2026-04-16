package com.baleshop.baleshop.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String customerName;
    private String phone;
    private String address;
    private String region;
    private String area;
    private String landmark;
    private String notes;
    private Long sellerId;
    private String sellerName;

    private Double total;

    private String status; // pending, paid, shipped
    private String deliveryProvider;
    private String deliveryMethod;
    private String deliveryStatus;
    private String externalDeliveryId;
    private String trackingUrl;
    private Double deliveryFee;
    private String pickupAddress;
    private String dropoffAddress;
    private String recipientName;
    private String recipientPhone;
    private String uberQuoteId;
    @Column(length = 2000)
    private String deliveryGatewayResponse;
    private String paymentMethod;
    private String paymentStatus;
    private String momoNetwork;
    private String momoNumber;
    private String cardEmail;
    private String paystackReference;
    private String paystackAccessCode;
    private String paystackAuthorizationUrl;
    private String paystackGatewayResponse;
    private LocalDateTime paidAt;
    private Double commissionAmount;
    private Double sellerPayoutAmount;
    private Boolean payoutReleased = false;
    private Boolean confirmedByBuyer = false;
    private LocalDateTime createdAt;
    private LocalDateTime buyerConfirmedAt;
    private LocalDateTime payoutReleasedAt;
    private LocalDateTime payoutHeldAt;
    private String payoutHoldReason;
    private Boolean refundRequested = false;
    private Double refundAmount;
    private String refundStatus;
    @Column(length = 1000)
    private String refundReason;
    private LocalDateTime refundRequestedAt;
    private LocalDateTime refundReviewedAt;
    private LocalDateTime refundProcessedAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @JsonManagedReference
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items;

    // Constructors
    public Order() {
    }

    @PrePersist
    public void beforeCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getLandmark() {
        return landmark;
    }

    public void setLandmark(String landmark) {
        this.landmark = landmark;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public String getSellerName() {
        return sellerName;
    }

    public void setSellerName(String sellerName) {
        this.sellerName = sellerName;
    }

    public Double getTotal() {
        return total;
    }

    public void setTotal(Double total) {
        this.total = total;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDeliveryProvider() {
        return deliveryProvider;
    }

    public void setDeliveryProvider(String deliveryProvider) {
        this.deliveryProvider = deliveryProvider;
    }

    public String getDeliveryMethod() {
        return deliveryMethod;
    }

    public void setDeliveryMethod(String deliveryMethod) {
        this.deliveryMethod = deliveryMethod;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public String getExternalDeliveryId() {
        return externalDeliveryId;
    }

    public void setExternalDeliveryId(String externalDeliveryId) {
        this.externalDeliveryId = externalDeliveryId;
    }

    public String getTrackingUrl() {
        return trackingUrl;
    }

    public void setTrackingUrl(String trackingUrl) {
        this.trackingUrl = trackingUrl;
    }

    public Double getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(Double deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public String getPickupAddress() {
        return pickupAddress;
    }

    public void setPickupAddress(String pickupAddress) {
        this.pickupAddress = pickupAddress;
    }

    public String getDropoffAddress() {
        return dropoffAddress;
    }

    public void setDropoffAddress(String dropoffAddress) {
        this.dropoffAddress = dropoffAddress;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getRecipientPhone() {
        return recipientPhone;
    }

    public void setRecipientPhone(String recipientPhone) {
        this.recipientPhone = recipientPhone;
    }

    public String getUberQuoteId() {
        return uberQuoteId;
    }

    public void setUberQuoteId(String uberQuoteId) {
        this.uberQuoteId = uberQuoteId;
    }

    public String getDeliveryGatewayResponse() {
        return deliveryGatewayResponse;
    }

    public void setDeliveryGatewayResponse(String deliveryGatewayResponse) {
        this.deliveryGatewayResponse = deliveryGatewayResponse;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getMomoNetwork() {
        return momoNetwork;
    }

    public void setMomoNetwork(String momoNetwork) {
        this.momoNetwork = momoNetwork;
    }

    public String getMomoNumber() {
        return momoNumber;
    }

    public void setMomoNumber(String momoNumber) {
        this.momoNumber = momoNumber;
    }

    public String getCardEmail() {
        return cardEmail;
    }

    public void setCardEmail(String cardEmail) {
        this.cardEmail = cardEmail;
    }

    public String getPaystackReference() {
        return paystackReference;
    }

    public void setPaystackReference(String paystackReference) {
        this.paystackReference = paystackReference;
    }

    public String getPaystackAccessCode() {
        return paystackAccessCode;
    }

    public void setPaystackAccessCode(String paystackAccessCode) {
        this.paystackAccessCode = paystackAccessCode;
    }

    public String getPaystackAuthorizationUrl() {
        return paystackAuthorizationUrl;
    }

    public void setPaystackAuthorizationUrl(String paystackAuthorizationUrl) {
        this.paystackAuthorizationUrl = paystackAuthorizationUrl;
    }

    public String getPaystackGatewayResponse() {
        return paystackGatewayResponse;
    }

    public void setPaystackGatewayResponse(String paystackGatewayResponse) {
        this.paystackGatewayResponse = paystackGatewayResponse;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public Double getCommissionAmount() {
        return commissionAmount;
    }

    public void setCommissionAmount(Double commissionAmount) {
        this.commissionAmount = commissionAmount;
    }

    public Double getSellerPayoutAmount() {
        return sellerPayoutAmount;
    }

    public void setSellerPayoutAmount(Double sellerPayoutAmount) {
        this.sellerPayoutAmount = sellerPayoutAmount;
    }

    public Boolean getPayoutReleased() {
        return payoutReleased;
    }

    public void setPayoutReleased(Boolean payoutReleased) {
        this.payoutReleased = payoutReleased;
    }

    public Boolean getConfirmedByBuyer() {
        return confirmedByBuyer;
    }

    public void setConfirmedByBuyer(Boolean confirmedByBuyer) {
        this.confirmedByBuyer = confirmedByBuyer;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getBuyerConfirmedAt() {
        return buyerConfirmedAt;
    }

    public void setBuyerConfirmedAt(LocalDateTime buyerConfirmedAt) {
        this.buyerConfirmedAt = buyerConfirmedAt;
    }

    public LocalDateTime getPayoutReleasedAt() {
        return payoutReleasedAt;
    }

    public void setPayoutReleasedAt(LocalDateTime payoutReleasedAt) {
        this.payoutReleasedAt = payoutReleasedAt;
    }

    public LocalDateTime getPayoutHeldAt() {
        return payoutHeldAt;
    }

    public void setPayoutHeldAt(LocalDateTime payoutHeldAt) {
        this.payoutHeldAt = payoutHeldAt;
    }

    public String getPayoutHoldReason() {
        return payoutHoldReason;
    }

    public void setPayoutHoldReason(String payoutHoldReason) {
        this.payoutHoldReason = payoutHoldReason;
    }

    public Boolean getRefundRequested() {
        return refundRequested;
    }

    public void setRefundRequested(Boolean refundRequested) {
        this.refundRequested = refundRequested;
    }

    public Double getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(Double refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getRefundStatus() {
        return refundStatus;
    }

    public void setRefundStatus(String refundStatus) {
        this.refundStatus = refundStatus;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public void setRefundReason(String refundReason) {
        this.refundReason = refundReason;
    }

    public LocalDateTime getRefundRequestedAt() {
        return refundRequestedAt;
    }

    public void setRefundRequestedAt(LocalDateTime refundRequestedAt) {
        this.refundRequestedAt = refundRequestedAt;
    }

    public LocalDateTime getRefundReviewedAt() {
        return refundReviewedAt;
    }

    public void setRefundReviewedAt(LocalDateTime refundReviewedAt) {
        this.refundReviewedAt = refundReviewedAt;
    }

    public LocalDateTime getRefundProcessedAt() {
        return refundProcessedAt;
    }

    public void setRefundProcessedAt(LocalDateTime refundProcessedAt) {
        this.refundProcessedAt = refundProcessedAt;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    @Transient
    public Long getBuyerId() {
        return user != null ? user.getId() : null;
    }

    @Transient
    public String getBuyerName() {
        return user != null ? user.getName() : customerName;
    }

    @Transient
    public String getBuyerEmail() {
        return user != null ? user.getEmail() : null;
    }

    @Transient
    public String getBuyerRole() {
        return user != null ? user.getRole() : null;
    }

    @Transient
    public String getBuyerAccountStatus() {
        return user != null ? user.getAccountStatus() : null;
    }
}
