package com.kmarket.authservice.controller;

import com.kmarket.authservice.service.AuthService;
import com.kmarket.authservice.dto.RoleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only read access to the RBAC roles. Restricted to role {@code admin} in SecurityConfig. */
@RestController
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
public class AdminRoleController {

    private final AuthService authService;

    @GetMapping
    public List<RoleResponse> listRoles() {
        return authService.listRoles().stream().map(RoleResponse::from).toList();
    }
}
