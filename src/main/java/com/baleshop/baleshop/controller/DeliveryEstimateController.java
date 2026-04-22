package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.DeliveryEstimateRequest;
import com.baleshop.baleshop.service.DeliveryEstimateService;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/delivery")
@CrossOrigin(origins = "*")
public class DeliveryEstimateController {

    private final DeliveryEstimateService deliveryEstimateService;
    private final SessionAuthService sessionAuthService;

    public DeliveryEstimateController(
            DeliveryEstimateService deliveryEstimateService,
            SessionAuthService sessionAuthService
    ) {
        this.deliveryEstimateService = deliveryEstimateService;
        this.sessionAuthService = sessionAuthService;
    }

    @PostMapping("/estimate")
    public ResponseEntity<Map<String, Object>> estimate(
            @RequestBody DeliveryEstimateRequest request,
            HttpServletRequest httpRequest
    ) {
        sessionAuthService.requireAuthenticatedUser(httpRequest);
        return ResponseEntity.ok(deliveryEstimateService.estimate(request));
    }

    @GetMapping("/places")
    public ResponseEntity<Map<String, Object>> places(
            @RequestParam String input,
            HttpServletRequest httpRequest
    ) {
        sessionAuthService.requireAuthenticatedUser(httpRequest);
        return ResponseEntity.ok(deliveryEstimateService.placePredictions(input));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleEstimateError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "success", false,
                "status", exception.getStatusCode().value(),
                "error", exception.getReason() == null ? "Could not estimate delivery" : exception.getReason()
        ));
    }
}
