package com.baleshop.baleshop.dto;

public class PayoutDetailsRequest {

    private Long userId;
    private String payoutMethod;
    private String momoNetwork;
    private String momoNumber;
    private String bankName;
    private String bankCode;
    private String bankAccountNumber;
    private String bankAccountName;
    private String paystackSubaccountCode;

    public PayoutDetailsRequest() {
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPayoutMethod() {
        return payoutMethod;
    }

    public void setPayoutMethod(String payoutMethod) {
        this.payoutMethod = payoutMethod;
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

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getBankCode() {
        return bankCode;
    }

    public void setBankCode(String bankCode) {
        this.bankCode = bankCode;
    }

    public String getBankAccountNumber() {
        return bankAccountNumber;
    }

    public void setBankAccountNumber(String bankAccountNumber) {
        this.bankAccountNumber = bankAccountNumber;
    }

    public String getBankAccountName() {
        return bankAccountName;
    }

    public void setBankAccountName(String bankAccountName) {
        this.bankAccountName = bankAccountName;
    }

    public String getPaystackSubaccountCode() {
        return paystackSubaccountCode;
    }

    public void setPaystackSubaccountCode(String paystackSubaccountCode) {
        this.paystackSubaccountCode = paystackSubaccountCode;
    }
}
