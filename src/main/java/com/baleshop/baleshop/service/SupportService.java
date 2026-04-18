package com.baleshop.baleshop.service;

import com.baleshop.baleshop.dto.SupportMessageRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class SupportService {

    private static final int MAX_MESSAGES_PER_WINDOW = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final AccountEmailService accountEmailService;
    private final Map<String, Deque<Instant>> attemptsByIp = new ConcurrentHashMap<>();

    @Value("${app.support.email:support@store.yenkas.xyz}")
    private String supportEmail;

    public SupportService(AccountEmailService accountEmailService) {
        this.accountEmailService = accountEmailService;
    }

    public void sendSupportMessage(SupportMessageRequest request, HttpServletRequest httpRequest) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Support message is required");
        }

        if (!clean(request.getWebsite(), 0, 200).isBlank()) {
            return;
        }

        String ipAddress = clientIp(httpRequest);
        enforceRateLimit(ipAddress);

        String name = clean(request.getName(), 2, 80);
        String email = clean(request.getEmail(), 5, 160).toLowerCase(Locale.ROOT);
        String phone = clean(request.getPhone(), 0, 40);
        String subject = clean(request.getSubject(), 0, 120);
        String message = cleanMultiline(request.getMessage(), 10, 2000);
        String pageUrl = clean(request.getPageUrl(), 0, 500);
        String userAgent = clean(httpRequest.getHeader("User-Agent"), 0, 300);

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
        }

        if (subject.isBlank()) {
            subject = "Store support request";
        }

        String body = """
                New Yenkasa Store support message

                Name: %s
                Email: %s
                Phone: %s
                Page: %s
                IP: %s
                User Agent: %s

                Message:
                %s
                """.formatted(name, email, phone.isBlank() ? "-" : phone, pageUrl.isBlank() ? "-" : pageUrl, ipAddress, userAgent, message);

        accountEmailService.sendNotificationEmail(
                supportEmail,
                "Yenkasa Store Support: " + subject,
                body
        );

        accountEmailService.sendNotificationEmail(
                email,
                "We received your Yenkasa Store message",
                "Hello " + name + ",\n\nWe received your message and will reply from " + supportEmail + ".\n\nYour message:\n" + message
        );
    }

    private void enforceRateLimit(String ipAddress) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(RATE_LIMIT_WINDOW);
        Deque<Instant> attempts = attemptsByIp.computeIfAbsent(ipAddress, key -> new ArrayDeque<>());

        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.removeFirst();
            }

            if (attempts.size() >= MAX_MESSAGES_PER_WINDOW) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many support messages. Please try again later.");
            }

            attempts.addLast(now);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private String clean(String value, int minLength, int maxLength) {
        String cleaned = value == null ? "" : value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();

        if (cleaned.length() < minLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required field is too short");
        }

        if (cleaned.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field is too long");
        }

        return cleaned;
    }

    private String cleanMultiline(String value, int minLength, int maxLength) {
        String cleaned = value == null ? "" : value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();

        if (cleaned.length() < minLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is too short");
        }

        if (cleaned.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is too long");
        }

        return cleaned;
    }
}
