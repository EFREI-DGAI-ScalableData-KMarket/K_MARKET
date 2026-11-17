package com.kmarket.authservice.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kmarket.authservice.entity.Role;
import com.kmarket.authservice.entity.User;

import java.time.OffsetDateTime;
import java.util.List;

/** Response matching the {@code User} schema of the contract. */
public record UserResponse(
        Integer id,
        String email,
        @JsonProperty("is_active") boolean active,
        List<String> roles,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static UserResponse from(User user) {
        List<String> roleNames = user.getRoles().stream().map(Role::getName).sorted().toList();
        return new UserResponse(user.getId(), user.getEmail(), user.isActive(),
                roleNames, user.getCreatedAt());
    }
}
