package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenAndType(String token, String type);
    List<UserToken> findByUserIdAndType(Long userId, String type);
    void deleteByUserIdAndType(Long userId, String type);
}
