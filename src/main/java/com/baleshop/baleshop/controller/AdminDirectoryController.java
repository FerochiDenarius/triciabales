package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.UserRepository;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@CrossOrigin(origins = "*")
public class AdminDirectoryController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionAuthService sessionAuthService;

    @GetMapping("/api/admin/users")
    public ResponseEntity<List<User>> getAdminUsers(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request
    ) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(filterUsers(role, status, includeDeleted));
    }

    @GetMapping({"/api/sellers", "/api/admin/sellers"})
    public ResponseEntity<List<User>> getAdminSellers(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeDeleted", defaultValue = "false") boolean includeDeleted,
            HttpServletRequest request
    ) {
        sessionAuthService.requireRole(request, "SUPER_ADMIN");
        return ResponseEntity.ok(filterUsers("SELLER", status, includeDeleted));
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

        return value.trim().replace('-', '_').replaceAll("\\s+", "_").toUpperCase(Locale.ROOT);
    }
}
