package com.miqroera.miqrokey.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MiQroKey Control Plane — Spring MVC management API.
 *
 * <p>
 * This application uses synchronous Spring MVC with JDBC (NOT WebFlux, NOT
 * R2DBC). It handles tenant/user management, credential administration, usage
 * reporting, and all administrative operations.
 * </p>
 */
@SpringBootApplication
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
