package com.kmarket.authservice.web;

import com.kmarket.authservice.service.AuthService;
import com.kmarket.authservice.service.AuthService.ApiKeyCreation;
import com.kmarket.authservice.web.dto.ApiKeyCreatedResponse;
import com.kmarket.authservice.web.dto.ApiKeyResponse;
import com.kmarket.authservice.web.dto.CreateApiKeyRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only management of technical-service API keys. Restricted to role {@code admin} in SecurityConfig. */
@RestController
@RequestMapping("/admin/api-keys")
@RequiredArgsConstructor
public class AdminApiKeyController {

    private final AuthService authService;

    @GetMapping
    public List<ApiKeyResponse> listApiKeys() {
        return authService.listApiKeys().stream().map(ApiKeyResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreatedResponse createApiKey(@Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyCreation created = authService.createApiKey(request);
        return ApiKeyCreatedResponse.from(created.apiKey(), created.clearKey());
    }

    @DeleteMapping("/{key_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable("key_id") Integer keyId) {
        authService.revokeApiKey(keyId);
    }
}
