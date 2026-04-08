package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.AuthRequest;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Value("${app.owner-code:}")
    private String ownerCode;

    @PostMapping("/register")
    public User register(@RequestBody AuthRequest request) {

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new RuntimeException("Email already exists");
        }

        String requestedRole = request.getRole() != null && !request.getRole().isBlank()
                ? request.getRole().trim().toUpperCase(Locale.ROOT)
                : "BUYER";

        User referrer = null;

        if (request.getReferralCode() != null && !request.getReferralCode().isBlank()) {
            referrer = userRepository.findByReferralCode(request.getReferralCode().trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new RuntimeException("Invalid referral code"));
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());
        user.setPhone(request.getPhone());
        user.setAddress(request.getAddress());
        user.setReferralCode(generateReferralCode());

        if (ownerCode != null && !ownerCode.isBlank() && ownerCode.equals(request.getOwnerCode())) {
            user.setRole("SUPER_ADMIN");
        } else {
            user.setRole(requestedRole);
        }

        if (referrer != null) {
            user.setReferredByCode(referrer.getReferralCode());

            if ("BUYER".equalsIgnoreCase(user.getRole())) {
                user.setReferralDiscountPercent(5.0);
            }

            if ("SELLER".equalsIgnoreCase(user.getRole())) {
                user.setReferralCommissionDiscountPercent(4.0);
                user.setReferralSalesRemaining(10);
            }
        }

        return userRepository.save(user);
    }

    @PostMapping("/login")
    public User login(@RequestBody AuthRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.getPassword().equals(request.getPassword())) {
            throw new RuntimeException("Invalid password");
        }

        return user;
    }

    private String generateReferralCode() {
        String code;

        do {
            code = "YEN" + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 8)
                    .toUpperCase(Locale.ROOT);
        } while (userRepository.findByReferralCode(code).isPresent());

        return code;
    }
}
