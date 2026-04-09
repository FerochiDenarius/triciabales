package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.model.UserToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class SessionAuthService {

    @Autowired
    private UserTokenService userTokenService;

    public String issueSession(User user) {
        return userTokenService.issueSessionToken(user, Duration.ofDays(7)).getToken();
    }

    public User requireAuthenticatedUser(HttpServletRequest request) {
        String token = extractBearerToken(request);

        UserToken session = userTokenService.findValidToken(token, UserToken.TYPE_AUTH_SESSION)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        return session.getUser();
    }

    public User requireRole(HttpServletRequest request, String... allowedRoles) {
        User user = requireAuthenticatedUser(request);
        Set<String> roles = new HashSet<>(Arrays.asList(allowedRoles));

        if (!roles.contains(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
        }

        return user;
    }

    public void invalidateSession(HttpServletRequest request) {
        String token = extractBearerToken(request);
        UserToken session = userTokenService.findValidToken(token, UserToken.TYPE_AUTH_SESSION)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        userTokenService.markUsed(session);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication token is missing");
        }

        return authHeader.substring(7).trim();
    }
}
