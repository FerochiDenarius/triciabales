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

        String email = data.get("email");
        String password = data.get("password");

        Map<String, Object> response = new HashMap<>();

        if (email.equals("admin@triciabales.com") && password.equals("1234")) {
            response.put("success", true);
        } else {
            response.put("success", false);
        }

        return response;
    }
}