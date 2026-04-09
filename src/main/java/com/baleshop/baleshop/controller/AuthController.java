package com.baleshop.baleshop.controller;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "This legacy login route is disabled. Use /api/users/login instead.");
        return response;
    }
}
