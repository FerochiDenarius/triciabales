package com.baleshop.baleshop.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String email;
    @JsonIgnore
    private String password;
    private String phone;
    private String address;
    private String profileImageUrl;
    private String role; // BUYER or SELLER
    @Column(unique = true)
    private String referralCode;
    private String referredByCode;
    private Double referralDiscountPercent;
    private Double referralCommissionDiscountPercent;
    private Integer referralSalesRemaining;
    private String payoutMethod;
    private String momoNetwork;
    private String momoNumber;
    private String bankName;
    private String bankAccountNumber;
    private String bankAccountName;
    private String paystackRecipientCode;
    private Boolean emailVerified = false;
    private LocalDateTime verificationSentAt;
    private LocalDateTime passwordResetRequestedAt;
    private String accountStatus = "ACTIVE";
    private LocalDateTime suspendedAt;
    private LocalDateTime blockedAt;
    private LocalDateTime deletedAt;

    public User() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public void setProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getReferralCode() {
        return referralCode;
    }

    public void setReferralCode(String referralCode) {
        this.referralCode = referralCode;
    }

    public String getReferredByCode() {
        return referredByCode;
    }

    public void setReferredByCode(String referredByCode) {
        this.referredByCode = referredByCode;
    }

    public Double getReferralDiscountPercent() {
        return referralDiscountPercent;
    }

    public void setReferralDiscountPercent(Double referralDiscountPercent) {
        this.referralDiscountPercent = referralDiscountPercent;
    }

    public Double getReferralCommissionDiscountPercent() {
        return referralCommissionDiscountPercent;
    }

    public void setReferralCommissionDiscountPercent(Double referralCommissionDiscountPercent) {
        this.referralCommissionDiscountPercent = referralCommissionDiscountPercent;
    }

    public Integer getReferralSalesRemaining() {
        return referralSalesRemaining;
    }

    public void setReferralSalesRemaining(Integer referralSalesRemaining) {
        this.referralSalesRemaining = referralSalesRemaining;
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

    public String getPaystackRecipientCode() {
        return paystackRecipientCode;
    }

    public void setPaystackRecipientCode(String paystackRecipientCode) {
        this.paystackRecipientCode = paystackRecipientCode;
    }

    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public LocalDateTime getVerificationSentAt() {
        return verificationSentAt;
    }

    public void setVerificationSentAt(LocalDateTime verificationSentAt) {
        this.verificationSentAt = verificationSentAt;
    }

    public LocalDateTime getPasswordResetRequestedAt() {
        return passwordResetRequestedAt;
    }

    public void setPasswordResetRequestedAt(LocalDateTime passwordResetRequestedAt) {
        this.passwordResetRequestedAt = passwordResetRequestedAt;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    public LocalDateTime getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(LocalDateTime suspendedAt) {
        this.suspendedAt = suspendedAt;
    }

    public LocalDateTime getBlockedAt() {
        return blockedAt;
    }

    public void setBlockedAt(LocalDateTime blockedAt) {
        this.blockedAt = blockedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
