package com.baleshop.baleshop.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer baleId;
    private String baleName;
    private Double price;
    private Integer quantity;
    private Long sellerId;
    private String sellerName;
    private String selectedSize;
    private Double lineTotal;
    private Double commissionAmount;
    private Double sellerPayoutAmount;
    private String paystackSubaccountCode;

    @JsonBackReference
    @ManyToOne
    @JoinColumn(name = "order_id")
    private Order order;

    // Constructor
    public OrderItem() {
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public Integer getBaleId() {
        return baleId;
    }

    public void setBaleId(Integer baleId) {
        this.baleId = baleId;
    }

    public String getBaleName() {
        return baleName;
    }

    public void setBaleName(String baleName) {
        this.baleName = baleName;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
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

    public String getSelectedSize() {
        return selectedSize;
    }

    public void setSelectedSize(String selectedSize) {
        this.selectedSize = selectedSize;
    }

    public Double getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(Double lineTotal) {
        this.lineTotal = lineTotal;
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

    public String getPaystackSubaccountCode() {
        return paystackSubaccountCode;
    }

    public void setPaystackSubaccountCode(String paystackSubaccountCode) {
        this.paystackSubaccountCode = paystackSubaccountCode;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }
}
