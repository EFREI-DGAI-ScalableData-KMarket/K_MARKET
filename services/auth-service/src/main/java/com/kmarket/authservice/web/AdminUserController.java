package com.kmarket.authservice.web;

import com.kmarket.authservice.entity.User;
import com.kmarket.authservice.service.AuthService;
import com.kmarket.authservice.web.dto.CreateUserRequest;
import com.kmarket.authservice.web.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only account management. Access is restricted to role {@code admin} in SecurityConfig. */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AuthService authService;

    @GetMapping
    public List<UserResponse> listUsers() {
        return authService.listUsers().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        User user = authService.createUser(request);
        return UserResponse.from(user);
    }
}
