package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.AuthRequest;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Value("${app.owner-code:}")
    private String ownerCode;

    @PostMapping("/register")
    public User register(@RequestBody AuthRequest request) {

        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new RuntimeException("Email already exists");
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());
        user.setPhone(request.getPhone());
        user.setAddress(request.getAddress());

        if (ownerCode != null && !ownerCode.isBlank() && ownerCode.equals(request.getOwnerCode())) {
            user.setRole("SUPER_ADMIN");
        } else if (request.getRole() != null && !request.getRole().isBlank()) {
            user.setRole(request.getRole());
        } else {
            user.setRole("BUYER");
        }

        return userRepository.save(user);
    }

    @PostMapping("/login")
    public User login(@RequestBody AuthRequest request) {

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.getPassword().equals(request.getPassword())) {
            throw new RuntimeException("Invalid password");
        }

        return user;
    }
}
