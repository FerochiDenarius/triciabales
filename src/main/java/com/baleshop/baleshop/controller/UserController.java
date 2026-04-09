package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.AuthRequest;
import com.baleshop.baleshop.dto.AuthResponse;
import com.baleshop.baleshop.dto.PasswordResetConfirmRequest;
import com.baleshop.baleshop.dto.PasswordResetRequest;
import com.baleshop.baleshop.dto.UserStatusUpdateRequest;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.model.UserToken;
import com.baleshop.baleshop.repository.UserRepository;
import com.baleshop.baleshop.service.AccountEmailService;
import com.baleshop.baleshop.service.PasswordService;
import com.baleshop.baleshop.service.SessionAuthService;
import com.baleshop.baleshop.service.UserTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private AccountEmailService accountEmailService;

    @Autowired
    private SessionAuthService sessionAuthService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {

        if (userRepository.findByEmailIgnoreCase(request.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        String requestedRole = request.getRole() != null && !request.getRole().isBlank()
                ? request.getRole().trim().toUpperCase(Locale.ROOT)
                : "BUYER";

        if (!"BUYER".equals(requestedRole) && !"SELLER".equals(requestedRole)) {
            requestedRole = "BUYER";
        }

        User referrer = null;

        if (request.getReferralCode() != null && !request.getReferralCode().isBlank()) {
            referrer = userRepository.findByReferralCode(request.getReferralCode().trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid referral code"));
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordService.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setAddress(request.getAddress());
        user.setReferralCode(generateReferralCode());
        user.setRole(requestedRole);
        user.setEmailVerified(false);
        user.setVerificationSentAt(LocalDateTime.now());
        user.setAccountStatus("ACTIVE");

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

        User savedUser = userRepository.save(user);
        UserToken verificationToken = userTokenService.issueSingleUseToken(
                savedUser,
                UserToken.TYPE_EMAIL_VERIFICATION,
                Duration.ofHours(24)
        );
        String actionUrl = accountEmailService.sendVerificationEmail(savedUser.getEmail(), verificationToken.getToken());

        return ResponseEntity.status(HttpStatus.CREATED).body(
                AuthResponse.of(
                        true,
                        "Account created. Please verify your email before logging in.",
                        null,
                        actionUrl,
                        savedUser
                )
        );
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {

        User user = userRepository.findByEmailIgnoreCase(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!passwordService.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        String accountStatus = user.getAccountStatus() == null ? "ACTIVE" : user.getAccountStatus().trim().toUpperCase(Locale.ROOT);

        if ("DELETED".equals(accountStatus)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been deleted");
        }

        if ("BLOCKED".equals(accountStatus)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been blocked. Please contact support.");
        }

        if ("SUSPENDED".equals(accountStatus)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been suspended. Please contact support.");
        }

        if (passwordService.needsUpgrade(user.getPassword())) {
            user.setPassword(passwordService.encode(request.getPassword()));
        }

        if (!Boolean.TRUE.equals(user.getEmailVerified()) && !"SUPER_ADMIN".equalsIgnoreCase(user.getRole())) {

            UserToken verificationToken = userTokenService.issueSingleUseToken(
                    user,
                    UserToken.TYPE_EMAIL_VERIFICATION,
                    Duration.ofHours(24)
            );

            String actionUrl = accountEmailService.sendVerificationEmail(
                    user.getEmail(),
                    verificationToken.getToken()
            );

            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    AuthResponse.of(
                            false,
                            "Your email is not verified. We have sent you a new verification email.",
                            null,
                            actionUrl,
                            null
                    )
            );
        }

        userRepository.save(user);

        String sessionToken = sessionAuthService.issueSession(user);

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        "Login successful",
                        sessionToken,
                        null,
                        user
                )
        );
    }

    @GetMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@RequestParam("token") String token) {
        UserToken verificationToken = userTokenService.findValidToken(token, UserToken.TYPE_EMAIL_VERIFICATION)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification link is invalid or expired"));

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);
        userTokenService.markUsed(verificationToken);

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        "Email verified successfully. You can now log in.",
                        null,
                        null,
                        user
                )
        );
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<AuthResponse> requestPasswordReset(@RequestBody PasswordResetRequest request) {
        String actionUrl = null;

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            User user = userRepository.findByEmailIgnoreCase(request.getEmail()).orElse(null);

            if (user != null) {
                user.setPasswordResetRequestedAt(LocalDateTime.now());
                userRepository.save(user);

                UserToken resetToken = userTokenService.issueSingleUseToken(
                        user,
                        UserToken.TYPE_PASSWORD_RESET,
                        Duration.ofHours(1)
                );
                actionUrl = accountEmailService.sendPasswordResetEmail(user.getEmail(), resetToken.getToken());
            }
        }

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        "If that email exists, a password reset link has been sent.",
                        null,
                        actionUrl,
                        null
                )
        );
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<AuthResponse> resendVerification(@RequestBody PasswordResetRequest request) {
        String actionUrl = null;

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            User user = userRepository.findByEmailIgnoreCase(request.getEmail()).orElse(null);

            if (user != null && !Boolean.TRUE.equals(user.getEmailVerified())) {
                String accountStatus = user.getAccountStatus() == null
                        ? "ACTIVE"
                        : user.getAccountStatus().trim().toUpperCase(Locale.ROOT);

                if (!"DELETED".equals(accountStatus)) {
                    user.setVerificationSentAt(LocalDateTime.now());
                    userRepository.save(user);

                    UserToken verificationToken = userTokenService.issueSingleUseToken(
                            user,
                            UserToken.TYPE_EMAIL_VERIFICATION,
                            Duration.ofHours(24)
                    );
                    actionUrl = accountEmailService.sendVerificationEmail(user.getEmail(), verificationToken.getToken());
                }
            }
        }

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        "If that account exists and is not yet verified, a new verification email has been sent.",
                        null,
                        actionUrl,
                        null
                )
        );
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<AuthResponse> confirmPasswordReset(@RequestBody PasswordResetConfirmRequest request) {
        UserToken resetToken = userTokenService.findValidToken(request.getToken(), UserToken.TYPE_PASSWORD_RESET)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset link is invalid or expired"));

        User user = resetToken.getUser();
        user.setPassword(passwordService.encode(request.getPassword()));
        userRepository.save(user);
        userTokenService.markUsed(resetToken);

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        "Password reset successful. You can now log in.",
                        null,
                        null,
                        null
                )
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logout(HttpServletRequest request) {
        sessionAuthService.invalidateSession(request);

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        "Logged out successfully",
                        null,
                        null,
                        null
                )
        );
    }

    @GetMapping
    public ResponseEntity<?> getAllUsers(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(userRepository.findAll());
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<User> updateUserStatus(
            @PathVariable Long id,
            @RequestBody UserStatusUpdateRequest request,
            HttpServletRequest httpRequest
    ) {
        User actor = sessionAuthService.requireRole(httpRequest, "SUPER_ADMIN");
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (actor.getId().equals(user.getId()) && request.getStatus() != null) {
            String attemptedStatus = request.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!"ACTIVE".equals(attemptedStatus)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot suspend or block your own super admin account");
            }
        }

        String status = request.getStatus() != null
                ? request.getStatus().trim().toUpperCase(Locale.ROOT)
                : "ACTIVE";

        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status) && !"BLOCKED".equals(status) && !"DELETED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account status");
        }

        user.setAccountStatus(status);

        if ("SUSPENDED".equals(status)) {
            user.setSuspendedAt(LocalDateTime.now());
            user.setBlockedAt(null);
            user.setDeletedAt(null);
        } else if ("BLOCKED".equals(status)) {
            user.setBlockedAt(LocalDateTime.now());
            user.setSuspendedAt(null);
            user.setDeletedAt(null);
        } else if ("DELETED".equals(status)) {
            user.setDeletedAt(LocalDateTime.now());
            user.setSuspendedAt(null);
            user.setBlockedAt(null);
        } else {
            user.setSuspendedAt(null);
            user.setBlockedAt(null);
            user.setDeletedAt(null);
        }

        return ResponseEntity.ok(userRepository.save(user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, HttpServletRequest httpRequest) {
        User actor = sessionAuthService.requireRole(httpRequest, "SUPER_ADMIN");
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (actor.getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot delete your own super admin account");
        }

        user.setAccountStatus("DELETED");
        user.setDeletedAt(LocalDateTime.now());
        user.setSuspendedAt(null);
        user.setBlockedAt(null);
        user.setEmailVerified(false);
        user.setPasswordResetRequestedAt(null);
        userRepository.save(user);

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        "User deleted successfully",
                        null,
                        null,
                        user
                )
        );
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
