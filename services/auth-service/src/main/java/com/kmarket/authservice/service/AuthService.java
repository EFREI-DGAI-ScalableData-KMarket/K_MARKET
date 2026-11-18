package com.kmarket.authservice.service;

import com.kmarket.authservice.entity.ApiKey;
import com.kmarket.authservice.entity.RefreshToken;
import com.kmarket.authservice.entity.Role;
import com.kmarket.authservice.entity.User;
import com.kmarket.authservice.repository.ApiKeyRepository;
import com.kmarket.authservice.repository.RefreshTokenRepository;
import com.kmarket.authservice.repository.RoleRepository;
import com.kmarket.authservice.repository.UserRepository;
import com.kmarket.authservice.security.JwtService;
import com.kmarket.authservice.security.TokenHasher;
import com.kmarket.authservice.web.ApiException;
import com.kmarket.authservice.web.dto.CreateApiKeyRequest;
import com.kmarket.authservice.web.dto.CreateUserRequest;
import com.kmarket.authservice.web.dto.LoginRequest;
import com.kmarket.authservice.web.dto.TokenPair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Account creation and login use-cases on top of the {@code auth} schema. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenHasher tokenHasher;

    /** Creates a new account with a bcrypt-hashed password and the requested roles. */
    @Transactional
    public User createUser(CreateUserRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("Email already in use: " + email);
        }

        List<Integer> requestedRoleIds = request.role_ids().stream().distinct().toList();
        List<Role> roles = roleRepository.findAllById(requestedRoleIds);
        if (roles.size() != requestedRoleIds.size()) {
            throw ApiException.badRequest("Unknown role_ids in request");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(true);
        user.setRoles(roles);

        try {
            User savedUser = userRepository.save(user);

            log.info("User created successfully: id={}, email={}",
                    savedUser.getId(),
                    savedUser.getEmail());

            return savedUser;

        } catch (DataIntegrityViolationException e) {
            log.error("Failed to create user: email={} - Data integrity violation",
                    user.getEmail(), e);

            throw ApiException.conflict("User already exists or violates database constraints");
        }
    }

    /** Verifies credentials and returns a fresh access + refresh token pair. */
    @Transactional
    public TokenPair login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));

        if (!user.isActive() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid credentials");
        }

        return issueTokens(user);
    }

    /**
     * Rotates a refresh token: validates the presented clear token, revokes it,
     * and issues a brand-new access + refresh token pair.
     */
    @Transactional
    public TokenPair refresh(String clearRefreshToken) {
        String tokenHash = tokenHasher.sha256Hex(clearRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        OffsetDateTime now = OffsetDateTime.now();
        if (!stored.isActive(now)) {
            throw ApiException.unauthorized("Refresh token revoked or expired");
        }

        User user = stored.getUser();
        if (!user.isActive()) {
            throw ApiException.unauthorized("Account disabled");
        }

        // Rotation: the old token can never be reused.
        stored.setRevokedAt(now);
        refreshTokenRepository.save(stored);

        log.info("Refresh token rotated: userId={}", user.getId());
        return issueTokens(user);
    }

    /** Revokes every active refresh token of the given user (logout). */
    @Transactional
    public void logout(Integer userId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<RefreshToken> active =
                refreshTokenRepository.findByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now);
        active.forEach(token -> token.setRevokedAt(now));
        refreshTokenRepository.saveAll(active);

        log.info("Logout: revoked {} refresh token(s) for userId={}", active.size(), userId);
    }

    /**
     * Exchanges a technical-service API key (sent in the {@code X-API-Key} header)
     * for a short-lived JWT carrying the key's role (typically {@code service_account}).
     * No refresh token is issued for service accounts.
     */
    @Transactional(readOnly = true)
    public TokenPair exchangeApiKey(String clearApiKey) {
        String keyHash = tokenHasher.sha256Hex(clearApiKey);
        ApiKey apiKey = apiKeyRepository.findByKeyHash(keyHash)
                .orElseThrow(() -> ApiException.unauthorized("Invalid API key"));

        if (!apiKey.isActive()) {
            throw ApiException.unauthorized("API key revoked");
        }

        String roleName = apiKey.getRole().getName();
        String accessToken = jwtService.generateServiceToken(apiKey.getName(), roleName);

        log.info("Service token issued: apiKey={}, role={}", apiKey.getName(), roleName);
        return TokenPair.bearer(accessToken, null, jwtService.accessTokenTtlSeconds());
    }

    @Transactional(readOnly = true)
    public User getById(Integer id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
    }

    /** Lists every account (admin view). */
    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll();
    }

    /** Lists the RBAC reference roles (admin view). */
    @Transactional(readOnly = true)
    public List<Role> listRoles() {
        return roleRepository.findAll();
    }

    /** Lists every technical-service API key (admin view). Never exposes the clear key. */
    @Transactional(readOnly = true)
    public List<ApiKey> listApiKeys() {
        return apiKeyRepository.findAll();
    }

    /**
     * Creates a technical-service API key: a random clear key is generated, stored
     * only as a SHA-256 hash, and returned to the caller once via {@code clearKey}.
     */
    @Transactional
    public ApiKeyCreation createApiKey(CreateApiKeyRequest request) {
        String name = request.name().trim();
        if (apiKeyRepository.existsByName(name)) {
            throw ApiException.conflict("API key name already in use: " + name);
        }

        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> ApiException.badRequest("Unknown role_id: " + request.roleId()));

        String clearKey = tokenHasher.newOpaqueToken();

        ApiKey apiKey = new ApiKey();
        apiKey.setName(name);
        apiKey.setKeyHash(tokenHasher.sha256Hex(clearKey));
        apiKey.setRole(role);
        apiKey.setActive(true);

        ApiKey saved = apiKeyRepository.save(apiKey);
        log.info("API key created: id={}, name={}, role={}", saved.getId(), saved.getName(), role.getName());
        return new ApiKeyCreation(saved, clearKey);
    }

    /** Revokes (deactivates) an API key so it can no longer be exchanged for a JWT. */
    @Transactional
    public void revokeApiKey(Integer keyId) {
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("API key not found: " + keyId));
        apiKey.setActive(false);
        apiKeyRepository.save(apiKey);
        log.info("API key revoked: id={}, name={}", apiKey.getId(), apiKey.getName());
    }

    /** A freshly created API key together with its one-time clear value. */
    public record ApiKeyCreation(ApiKey apiKey, String clearKey) {
    }


    private TokenPair issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);

        String clearRefreshToken = tokenHasher.newOpaqueToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(tokenHasher.sha256Hex(clearRefreshToken));
        refreshToken.setExpiresAt(OffsetDateTime.now().plusSeconds(jwtService.refreshTokenTtlSeconds()));
        refreshTokenRepository.save(refreshToken);

        return TokenPair.bearer(accessToken, clearRefreshToken, jwtService.accessTokenTtlSeconds());
    }
}
