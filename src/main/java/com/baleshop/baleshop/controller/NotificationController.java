package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.model.AppNotification;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.AppNotificationRepository;
import com.baleshop.baleshop.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "*")
public class NotificationController {

    private final AppNotificationRepository notificationRepository;
    private final SessionAuthService sessionAuthService;

    public NotificationController(
            AppNotificationRepository notificationRepository,
            SessionAuthService sessionAuthService
    ) {
        this.notificationRepository = notificationRepository;
        this.sessionAuthService = sessionAuthService;
        System.out.println("✅ NotificationController loaded");
    }

    @GetMapping("/me")
    public List<AppNotification> myNotifications(HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        return notificationRepository.findTop80ByUserIdOrRecipientRoleOrderByCreatedAtDesc(
                actor.getId(),
                actor.getRole()
        );
    }

    @PutMapping("/{id}/read")
    public AppNotification markRead(@PathVariable Long id, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        AppNotification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));

        if (!canAccessNotification(actor, notification)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot update this notification");
        }

        notification.setReadAt(LocalDateTime.now());
        return notificationRepository.save(notification);
    }

    @DeleteMapping("/{id}")
    public java.util.Map<String, Object> deleteNotification(@PathVariable Long id, HttpServletRequest request) {
        User actor = sessionAuthService.requireAuthenticatedUser(request);
        AppNotification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));

        if (!canAccessNotification(actor, notification)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot delete this notification");
        }

        notificationRepository.delete(notification);
        return java.util.Map.of(
                "success", true,
                "message", "Notification deleted",
                "id", id
        );
    }

    private boolean canAccessNotification(User actor, AppNotification notification) {
        boolean ownsNotification = actor.getId().equals(notification.getUserId());
        boolean roleMatches = notification.getRecipientRole() != null
                && notification.getRecipientRole().equalsIgnoreCase(actor.getRole());

        return ownsNotification || roleMatches;
    }
}
