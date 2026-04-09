package com.baleshop.baleshop.dto;

import com.baleshop.baleshop.model.User;

public class AuthResponse {

    private boolean success;
    private String message;
    private String token;
    private String actionUrl;
    private User user;

    public static AuthResponse of(boolean success, String message, String token, String actionUrl, User user) {
        AuthResponse response = new AuthResponse();
        response.setSuccess(success);
        response.setMessage(message);
        response.setToken(token);
        response.setActionUrl(actionUrl);
        response.setUser(user);
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getActionUrl() {
        return actionUrl;
    }

    public void setActionUrl(String actionUrl) {
        this.actionUrl = actionUrl;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }
}
