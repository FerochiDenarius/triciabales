package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.service.PaymentApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PaymentApiException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentApiException(PaymentApiException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "success", false,
                "code", exception.getCode(),
                "message", exception.getReason() == null ? "Payment request failed" : exception.getReason(),
                "details", exception.getDetails() == null ? "" : exception.getDetails()
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "success", false,
                "code", "REQUEST_FAILED",
                "message", exception.getReason() == null ? "Request failed" : exception.getReason(),
                "details", ""
        ));
    }
}
