package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.RefundStatusUpdateRequest;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.service.RefundService;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/refunds")
@CrossOrigin(origins = "*")
public class RefundController {

    private final RefundService refundService;
    private final SessionAuthService sessionAuthService;

    public RefundController(RefundService refundService, SessionAuthService sessionAuthService) {
        this.refundService = refundService;
        this.sessionAuthService = sessionAuthService;
    }

    @GetMapping
    public List<Map<String, Object>> listRefunds(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return refundService.listRefunds();
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateRefundStatus(
            @PathVariable Long id,
            @RequestBody RefundStatusUpdateRequest statusRequest,
            HttpServletRequest request
    ) {
        User actor = sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(refundService.updateRefundStatus(id, statusRequest.getStatus(), actor));
    }
}
