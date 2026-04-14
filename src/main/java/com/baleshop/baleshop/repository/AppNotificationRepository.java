package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.AppNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppNotificationRepository extends JpaRepository<AppNotification, Long> {

    List<AppNotification> findTop80ByUserIdOrRecipientRoleOrderByCreatedAtDesc(Long userId, String recipientRole);
}
