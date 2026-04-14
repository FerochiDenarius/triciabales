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
import com.baleshop.baleshop.service.CloudinaryService;
import com.baleshop.baleshop.service.NotificationService;
import com.baleshop.baleshop.service.PasswordService;
import com.baleshop.baleshop.service.SessionAuthService;
import com.baleshop.baleshop.service.UserTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

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

    @Autowired
    private CloudinaryService cloudinaryService;

    @Autowired
    private NotificationService notificationService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {

        String requestedEmail = request.getEmail() == null ? "" : request.getEmail().trim();
        log.info("Registration requested for {}", requestedEmail);

        Optional<User> existingUser = userRepository.findByEmailIgnoreCase(request.getEmail());
        User user = existingUser.orElseGet(User::new);
        boolean reactivatingDeletedAccount = existingUser
                .map(existing -> "DELETED".equals(normalizeValue(existing.getAccountStatus(), "ACTIVE")))
                .orElse(false);

        if (existingUser.isPresent() && !reactivatingDeletedAccount) {
            log.info("Registration rejected for {}: email already exists with status {}",
                    requestedEmail,
                    normalizeValue(existingUser.get().getAccountStatus(), "ACTIVE"));
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

        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordService.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setAddress(request.getAddress());
        if (user.getReferralCode() == null || user.getReferralCode().isBlank()) {
            user.setReferralCode(generateReferralCode());
        }
        user.setRole(requestedRole);
        user.setEmailVerified(false);
        user.setVerificationSentAt(LocalDateTime.now());
        user.setAccountStatus("ACTIVE");
        user.setDeletedAt(null);
        user.setSuspendedAt(null);
        user.setBlockedAt(null);
        user.setPasswordResetRequestedAt(null);

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
        if (reactivatingDeletedAccount) {
            log.info("Reactivated deleted account during registration for {}", savedUser.getEmail());
        } else {
            log.info("Created new {} account for {}", savedUser.getRole(), savedUser.getEmail());
        }

        UserToken verificationToken = userTokenService.issueSingleUseToken(
                savedUser,
                UserToken.TYPE_EMAIL_VERIFICATION,
                Duration.ofHours(24)
        );
        String actionUrl = accountEmailService.sendVerificationEmail(savedUser.getEmail(), verificationToken.getToken());
        log.info("Verification email queued after registration for {}", savedUser.getEmail());

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
                .orElseThrow(() -> {
                    log.info("Login denied for {}: account not found", request.getEmail());
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
                });

        if (!passwordService.matches(request.getPassword(), user.getPassword())) {
            log.info("Login denied for {}: password mismatch", user.getEmail());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        String accountStatus = user.getAccountStatus() == null ? "ACTIVE" : user.getAccountStatus().trim().toUpperCase(Locale.ROOT);

        if ("DELETED".equals(accountStatus)) {
            log.info("Login denied for {}: account deleted", user.getEmail());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been deleted");
        }

        if ("BLOCKED".equals(accountStatus)) {
            log.info("Login denied for {}: account blocked", user.getEmail());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been blocked. Please contact support.");
        }

        if ("SUSPENDED".equals(accountStatus)) {
            log.info("Login denied for {}: account suspended", user.getEmail());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been suspended. Please contact support.");
        }

        if (passwordService.needsUpgrade(user.getPassword())) {
            user.setPassword(passwordService.encode(request.getPassword()));
        }

        if (!Boolean.TRUE.equals(user.getEmailVerified()) && !"SUPER_ADMIN".equalsIgnoreCase(user.getRole())) {
            log.info("Login denied for {}: email not verified, queueing verification email", user.getEmail());

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
        log.info("Login successful for {} role={}", user.getEmail(), user.getRole());

        if ("SUPER_ADMIN".equalsIgnoreCase(user.getRole())) {
            notificationService.notifySensitiveActivity(
                    user,
                    "Super admin login",
                    "Super admin " + user.getEmail() + " logged in."
            );
        }

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

    @GetMapping("/me")
    public ResponseEntity<User> getMyProfile(HttpServletRequest request) {
        return ResponseEntity.ok(sessionAuthService.requireAuthenticatedUser(request));
    }

    @PutMapping("/me")
    public ResponseEntity<AuthResponse> updateMyProfile(
            HttpServletRequest request,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "profileImage", required = false) MultipartFile profileImage
    ) throws IOException {
        User user = sessionAuthService.requireAuthenticatedUser(request);
        String verificationActionUrl = null;

        if (name != null) {
            user.setName(name.trim());
        }

        if (phone != null) {
            user.setPhone(phone.trim());
        }

        if (address != null) {
            user.setAddress(address.trim());
        }

        if (password != null && !password.isBlank()) {
            user.setPassword(passwordService.encode(password));
            notificationService.notifySensitiveActivity(
                    user,
                    "Password changed",
                    "Password was changed for account " + user.getEmail() + "."
            );
        }

        if (profileImage != null && !profileImage.isEmpty()) {
            user.setProfileImageUrl(cloudinaryService.uploadProfileImage(profileImage));
        }

        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(user.getEmail())) {
            String previousEmail = user.getEmail();
            String nextEmail = email.trim();
            Optional<User> existingUser = userRepository.findByEmailIgnoreCase(nextEmail);

            if (existingUser.isPresent() && !existingUser.get().getId().equals(user.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
            }

            user.setEmail(nextEmail);
            user.setEmailVerified(false);
            user.setVerificationSentAt(LocalDateTime.now());
            notificationService.notifySensitiveActivity(
                    user,
                    "Email address changed",
                    "Account email changed from " + previousEmail + " to " + nextEmail + "."
            );
        }

        User savedUser = userRepository.save(user);
        log.info("Profile updated for {} role={}", savedUser.getEmail(), savedUser.getRole());

        if (!Boolean.TRUE.equals(savedUser.getEmailVerified()) && !"SUPER_ADMIN".equalsIgnoreCase(savedUser.getRole())) {
            UserToken verificationToken = userTokenService.issueSingleUseToken(
                    savedUser,
                    UserToken.TYPE_EMAIL_VERIFICATION,
                    Duration.ofHours(24)
            );
            verificationActionUrl = accountEmailService.sendVerificationEmail(savedUser.getEmail(), verificationToken.getToken());
            log.info("Verification email queued after profile update for {}", savedUser.getEmail());
        }

        return ResponseEntity.ok(
                AuthResponse.of(
                        true,
                        verificationActionUrl == null
                                ? "Profile updated successfully."
                                : "Profile updated. Please verify your email address.",
                        null,
                        verificationActionUrl,
                        savedUser
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
            String requestedEmail = request.getEmail().trim();
            User user = userRepository.findByEmailIgnoreCase(requestedEmail).orElse(null);

            if (user == null) {
                log.info("Verification resend skipped for {}: account not found", requestedEmail);
            } else if (Boolean.TRUE.equals(user.getEmailVerified())) {
                log.info("Verification resend skipped for {}: account already verified", user.getEmail());
            } else {
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
                    log.info("Verification resend queued for {}", user.getEmail());
                } else {
                    log.info("Verification resend skipped for {}: account deleted", user.getEmail());
                }
            }
        } else {
            log.info("Verification resend skipped: email was blank");
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
    public ResponseEntity<?> getAllUsers(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request
    ) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(filterUsers(role, status, includeDeleted));
    }

    @GetMapping("/buyers")
    public ResponseEntity<?> getBuyerUsers(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request
    ) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(filterUsers("BUYER", status, includeDeleted));
    }

    @GetMapping("/sellers")
    public ResponseEntity<?> getSellerUsers(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request
    ) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(filterUsers("SELLER", status, includeDeleted));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getUserSummary(HttpServletRequest request) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");

        List<User> users = userRepository.findAll();
        Map<String, Long> roleCounts = users.stream()
                .collect(Collectors.groupingBy(
                        user -> normalizeValue(user.getRole(), "UNKNOWN"),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        Map<String, Long> statusCounts = users.stream()
                .collect(Collectors.groupingBy(
                        user -> normalizeValue(user.getAccountStatus(), "ACTIVE"),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalUsers", (long) users.size());
        summary.put("buyers", roleCounts.getOrDefault("BUYER", 0L));
        summary.put("sellers", roleCounts.getOrDefault("SELLER", 0L));
        summary.put("admins", roleCounts.getOrDefault("ADMIN", 0L));
        summary.put("superAdmins", roleCounts.getOrDefault("SUPER_ADMIN", 0L));
        summary.put("activeUsers", statusCounts.getOrDefault("ACTIVE", 0L));
        summary.put("suspendedUsers", statusCounts.getOrDefault("SUSPENDED", 0L));
        summary.put("blockedUsers", statusCounts.getOrDefault("BLOCKED", 0L));
        summary.put("deletedUsers", statusCounts.getOrDefault("DELETED", 0L));
        summary.put("roleCounts", roleCounts);
        summary.put("statusCounts", statusCounts);

        return ResponseEntity.ok(summary);
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

        User savedUser = userRepository.save(user);
        notificationService.notifySensitiveActivity(
                actor,
                "User status changed",
                actor.getEmail() + " changed " + savedUser.getEmail() + " status to " + savedUser.getAccountStatus() + "."
        );

        return ResponseEntity.ok(savedUser);
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
        notificationService.notifySensitiveActivity(
                actor,
                "User deleted",
                actor.getEmail() + " deleted user account " + user.getEmail() + "."
        );

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

    private List<User> filterUsers(String role, String status, boolean includeDeleted) {
        return userRepository.findAll().stream()
                .filter(user -> includeDeleted || !"DELETED".equals(normalizeValue(user.getAccountStatus(), "ACTIVE")))
                .filter(user -> role == null || role.isBlank() || normalizeValue(user.getRole(), "").equals(normalizeValue(role, "")))
                .filter(user -> status == null || status.isBlank() || normalizeValue(user.getAccountStatus(), "ACTIVE").equals(normalizeValue(status, "ACTIVE")))
                .sorted(Comparator.comparing(User::getId, Comparator.nullsLast(Long::compareTo)).reversed())
                .toList();
    }

    private String normalizeValue(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value.trim().toUpperCase(Locale.ROOT);
    }
}
