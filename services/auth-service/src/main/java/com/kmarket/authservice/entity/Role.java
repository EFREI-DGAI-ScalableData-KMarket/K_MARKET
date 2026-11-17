package com.kmarket.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A RBAC role from {@code auth.roles}. Reference data seeded by
 * {@code db/postgres/05_auth.sql}: admin, catalog_manager, analyst,
 * logistics, service_account.
 */
@Entity
@Table(name = "roles", schema = "auth")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column
    private String description;
}
