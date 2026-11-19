package com.kmarket.authservice.dto;

import com.kmarket.authservice.entity.Role;

/** Response matching the {@code Role} schema of the contract. */
public record RoleResponse(
        Integer id,
        String name,
        String description) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(role.getId(), role.getName(), role.getDescription());
    }
}
