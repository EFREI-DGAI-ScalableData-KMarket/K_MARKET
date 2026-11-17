package com.kmarket.authservice.repository;

import com.kmarket.authservice.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Active (not revoked, not expired) tokens of a user, used by logout. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfter(Integer userId, OffsetDateTime now);
}

