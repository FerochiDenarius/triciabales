package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.SupportMessageRequest;
import com.baleshop.baleshop.service.SupportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/support")
@CrossOrigin(origins = "*")
public class SupportController {

    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    @PostMapping("/contact")
    public ResponseEntity<Map<String, Object>> sendSupportMessage(
            @RequestBody SupportMessageRequest supportRequest,
            HttpServletRequest request
    ) {
        supportService.sendSupportMessage(supportRequest, request);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Support message sent"
        ));
    }
}
