package com.baleshop.baleshop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AccountEmailService {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailService.class);
    private static final Duration MAILER_TIMEOUT = Duration.ofSeconds(30);

    @Value("${spring.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    @Value("${app.frontend-base-url:https://www.yenkasa.xyz/triciabales_frontend/landingFile}")
    private String frontendBaseUrl;

    @Value("${app.mailer.node-binary:node}")
    private String nodeBinary;

    @Value("${app.mailer.script-path:scripts/send-email.js}")
    private String mailerScriptPath;

    public String sendVerificationEmail(String email, String token) {
        String actionUrl = frontendBaseUrl + "/verify-email.html?token=" + token;
        log.info("Preparing verification email for {} using {}", email, actionUrl);
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
        log.info("Preparing password reset email for {} using {}", email, actionUrl);
        sendOrLog(
                email,
                "Reset your Yenkasa Store password",
                "We received a password reset request.\n\nReset your password using this link:\n" + actionUrl,
                actionUrl
        );
        return actionUrl;
    }

    private void sendOrLog(String to, String subject, String body, String actionUrl) {
        if (fromAddress == null || fromAddress.isBlank()) {
            log.warn("MAIL NOT CONFIGURED. {} link for {} -> {}", subject, to, actionUrl);
            return;
        }

        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("from", fromAddress);
            payload.put("to", to);
            payload.put("subject", subject);
            payload.put("text", body);
            payload.put("actionUrl", actionUrl);

            ProcessBuilder processBuilder = new ProcessBuilder(nodeBinary, mailerScriptPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(toJson(payload));
            }

            String output = readOutput(process);
            boolean finished = process.waitFor(MAILER_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Nodemailer process timed out after " + MAILER_TIMEOUT.toSeconds() + "s");
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException("Nodemailer exited with code " + process.exitValue() + ". Output: " + output);
            }

            log.info("Email sent successfully to {} with subject {}. Mailer output: {}", to, subject, output.trim());
        } catch (Exception ex) {
            log.warn("Failed to send email to {}. Fallback link -> {}", to, actionUrl, ex);
        }
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        return output.toString();
    }

    private String toJson(Map<String, String> payload) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;

        for (Map.Entry<String, String> entry : payload.entrySet()) {
            if (!first) {
                json.append(',');
            }

            json.append('"')
                    .append(escapeJson(entry.getKey()))
                    .append("\":")
                    .append('"')
                    .append(escapeJson(entry.getValue()))
                    .append('"');
            first = false;
        }

        json.append('}');
        return json.toString();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder();

        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }

        return escaped.toString();
    }
}
