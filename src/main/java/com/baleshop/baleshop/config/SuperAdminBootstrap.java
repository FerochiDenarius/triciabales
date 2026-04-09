package com.baleshop.baleshop.config;

import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.UserRepository;
import com.baleshop.baleshop.service.PasswordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SuperAdminBootstrap implements CommandLineRunner {

    @Value("${app.super-admin.email:}")
    private String superAdminEmail;

    @Value("${app.super-admin.password:}")
    private String superAdminPassword;

    @Value("${app.super-admin.name:Platform Owner}")
    private String superAdminName;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordService passwordService;

    @Override
    public void run(String... args) {
        if (superAdminEmail == null || superAdminEmail.isBlank() || superAdminPassword == null || superAdminPassword.isBlank()) {
            return;
        }

        User user = userRepository.findByEmailIgnoreCase(superAdminEmail).orElseGet(User::new);
        user.setName(superAdminName);
        user.setEmail(superAdminEmail);
        user.setRole("SUPER_ADMIN");
        user.setEmailVerified(true);

        if (user.getPassword() == null || !passwordService.matches(superAdminPassword, user.getPassword())) {
            user.setPassword(passwordService.encode(superAdminPassword));
        }

        userRepository.save(user);
    }
}
