package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.DeliveryEstimateRequest;
import com.baleshop.baleshop.service.DeliveryEstimateService;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
