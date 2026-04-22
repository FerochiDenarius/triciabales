package com.baleshop.baleshop.dto;

public class CartItemDto {

    private Integer baleId;
    private String baleName;
    private Double price;
    private Integer quantity;
    private String selectedSize;

    public CartItemDto() {
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

    public String getSelectedSize() {
        return selectedSize;
    }

    public void setSelectedSize(String selectedSize) {
        this.selectedSize = selectedSize;
    }
}
