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
    private String deliveryMethod;
    private String deliveryStatus;
    private String paymentMethod;
    private String paymentStatus;
    private String momoNetwork;
    private String momoNumber;
    private String cardEmail;
    private Double commissionAmount;
    private Double sellerPayoutAmount;
    private Boolean payoutReleased = false;
    private Boolean confirmedByBuyer = false;
    private LocalDateTime buyerConfirmedAt;
    private LocalDateTime payoutReleasedAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @JsonManagedReference
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items;

    // Constructors
    public Order() {
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
}
