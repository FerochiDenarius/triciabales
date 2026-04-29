package com.baleshop.baleshop.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PaymentApiException extends ResponseStatusException {

    private final String code;
    private final String details;

    public PaymentApiException(HttpStatus status, String code, String message, String details) {
        super(status, message);
        this.code = code;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public String getDetails() {
        return details;
    }
}
