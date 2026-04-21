package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.PayoutDetailsRequest;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.UserRepository;
import com.baleshop.baleshop.service.PaystackService;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/seller")
@CrossOrigin(origins = "*")
public class SellerController {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SessionAuthService sessionAuthService;
    @Autowired
    private PaystackService paystackService;

    @PutMapping("/payout-details")
    public ResponseEntity<User> updatePayoutDetails(@RequestBody PayoutDetailsRequest request, HttpServletRequest httpRequest) {
        User actor = sessionAuthService.requireRole(httpRequest, "SELLER", "ADMIN", "SUPER_ADMIN");

        if (request.getUserId() == null) {
            return ResponseEntity.badRequest().build();
        }

        User user = userRepository.findById(request.getUserId())
                .orElse(null);

        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        if ("SELLER".equalsIgnoreCase(actor.getRole()) && !actor.getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update your own payout details");
        }

        user.setPayoutMethod(request.getPayoutMethod());
        user.setMomoNetwork(request.getMomoNetwork());
        user.setMomoNumber(request.getMomoNumber());
        user.setBankName(request.getBankName());
        user.setBankCode(request.getBankCode());
        user.setBankAccountNumber(request.getBankAccountNumber());
        user.setBankAccountName(request.getBankAccountName());
        if (!"SELLER".equalsIgnoreCase(actor.getRole())
                && request.getPaystackSubaccountCode() != null
                && !request.getPaystackSubaccountCode().isBlank()) {
            user.setPaystackSubaccountCode(request.getPaystackSubaccountCode().trim());
            user.setPaystackSubaccountStatus("linked_manually");
        }

        User saved = userRepository.save(user);
        if (canCreatePaystackSubaccount(saved)) {
            saved = paystackService.createOrUpdateSellerSubaccount(saved);
        }

        return ResponseEntity.ok(saved);
    }

    private boolean canCreatePaystackSubaccount(User user) {
        return user.getBankCode() != null && !user.getBankCode().isBlank()
                && user.getBankAccountNumber() != null && !user.getBankAccountNumber().isBlank()
                && (user.getShopName() != null && !user.getShopName().isBlank()
                    || user.getName() != null && !user.getName().isBlank());
    }
}
