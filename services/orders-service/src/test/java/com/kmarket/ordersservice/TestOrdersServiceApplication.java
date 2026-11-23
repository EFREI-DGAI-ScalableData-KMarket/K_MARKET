package com.kmarket.ordersservice;

import org.springframework.boot.SpringApplication;

public class TestOrdersServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(OrdersServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
