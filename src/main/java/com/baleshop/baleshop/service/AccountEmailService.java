package com.baleshop.baleshop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class AccountEmailService {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailService.class);

    @Autowired
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    @Value("${app.frontend-base-url:https://www.yenkasa.xyz/triciabales_frontend/landingFile}")
    private String frontendBaseUrl;

    public String sendVerificationEmail(String email, String token) {
        String actionUrl = frontendBaseUrl + "/verify-email.html?token=" + token;
        sendOrLog(
                email,
                "Verify your Yenkasa Store account",
                "Welcome to Yenkasa Store.\n\nVerify your email by opening this link:\n" + actionUrl,
                actionUrl
        );
        return actionUrl;
    }

    public String sendPasswordResetEmail(String email, String token) {
        String actionUrl = frontendBaseUrl + "/reset-password.html?token=" + token;
        sendOrLog(
                email,
                "Reset your Yenkasa Store password",
                "We received a password reset request.\n\nReset your password using this link:\n" + actionUrl,
                actionUrl
        );
        return actionUrl;
    }

    private void sendOrLog(String to, String subject, String body, String actionUrl) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null || fromAddress == null || fromAddress.isBlank()) {
            log.warn("MAIL NOT CONFIGURED. {} link for {} -> {}", subject, to, actionUrl);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("Failed to send email to {}. Fallback link -> {}", to, actionUrl, ex);
        }
    }
}
