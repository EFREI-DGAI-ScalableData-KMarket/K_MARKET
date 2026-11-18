package com.kmarket.authservice.web;

import com.kmarket.authservice.entity.User;
import com.kmarket.authservice.service.AuthService;
import com.kmarket.authservice.web.dto.LoginRequest;
import com.kmarket.authservice.web.dto.RefreshRequest;
import com.kmarket.authservice.web.dto.TokenPair;
import com.kmarket.authservice.web.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication endpoints: login, token refresh/exchange, logout and profile. */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/token")
    public TokenPair token(@RequestHeader(name = "X-API-Key", required = false) String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.unauthorized("Missing X-API-Key header");
        }
        return authService.exchangeApiKey(apiKey);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        //Assure the token is from non-tech-service account -> forbidden
        rejectServiceAccounts(authentication);
        authService.logout(Integer.valueOf(authentication.getName()));
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        //Assure the token is from non-tech-service account -> forbidden
        rejectServiceAccounts(authentication);
        User user = authService.getById(Integer.valueOf(authentication.getName()));
        return UserResponse.from(user);
    }

    private void rejectServiceAccounts(Authentication authentication) {
        boolean isServiceAccount = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_service_account".equals(a.getAuthority()));
        if (isServiceAccount) {
            throw ApiException.forbidden("Service accounts cannot use this endpoint");
        }
    }
}

