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

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }
}
