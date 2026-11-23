package com.kmarket.ordersservice.controller;

import com.kmarket.ordersservice.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness probe. Public endpoint (declared permitAll in SecurityConfig). */
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public HealthResponse health() {
        return HealthResponse.ok();
    }
}
