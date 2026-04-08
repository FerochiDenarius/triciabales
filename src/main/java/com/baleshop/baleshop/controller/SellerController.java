package com.baleshop.baleshop.controller;

import com.baleshop.baleshop.dto.PayoutDetailsRequest;
import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seller")
@CrossOrigin(origins = "*")
public class SellerController {

    @Autowired
    private UserRepository userRepository;

    @PutMapping("/payout-details")
    public ResponseEntity<User> updatePayoutDetails(@RequestBody PayoutDetailsRequest request) {
        if (request.getUserId() == null) {
            return ResponseEntity.badRequest().build();
        }

        User user = userRepository.findById(request.getUserId())
                .orElse(null);

        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        user.setPayoutMethod(request.getPayoutMethod());
        user.setMomoNetwork(request.getMomoNetwork());
        user.setMomoNumber(request.getMomoNumber());
        user.setBankName(request.getBankName());
        user.setBankAccountNumber(request.getBankAccountNumber());
        user.setBankAccountName(request.getBankAccountName());

        return ResponseEntity.ok(userRepository.save(user));
    }
}
