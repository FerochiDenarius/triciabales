package com.baleshop.baleshop.service;

import com.baleshop.baleshop.model.User;
import com.baleshop.baleshop.model.UserToken;
import com.baleshop.baleshop.repository.UserTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserTokenService {

    @Autowired
    private UserTokenRepository userTokenRepository;

    public UserToken issueSingleUseToken(User user, String type, Duration ttl) {
        userTokenRepository.deleteByUserIdAndType(user.getId(), type);

        UserToken token = new UserToken();
        token.setUser(user);
        token.setType(type);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(LocalDateTime.now().plus(ttl));

        return userTokenRepository.save(token);
    }

    public UserToken issueSessionToken(User user, Duration ttl) {
        userTokenRepository.deleteByUserIdAndType(user.getId(), UserToken.TYPE_AUTH_SESSION);

        UserToken token = new UserToken();
        token.setUser(user);
        token.setType(UserToken.TYPE_AUTH_SESSION);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(LocalDateTime.now().plus(ttl));

        return userTokenRepository.save(token);
    }

    public Optional<UserToken> findValidToken(String token, String type) {
        return userTokenRepository.findByTokenAndType(token, type)
                .filter(savedToken -> savedToken.getUsedAt() == null)
                .filter(savedToken -> savedToken.getExpiresAt() != null && savedToken.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    public void markUsed(UserToken token) {
        token.setUsedAt(LocalDateTime.now());
        userTokenRepository.save(token);
    }
}
