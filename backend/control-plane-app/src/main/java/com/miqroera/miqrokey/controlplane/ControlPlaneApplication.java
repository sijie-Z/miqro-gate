package com.miqroera.miqrokey.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

import com.miqroera.miqrokey.controlplane.config.ApprovalProperties;
import com.miqroera.miqrokey.controlplane.config.AdminAccessProperties;
import com.miqroera.miqrokey.controlplane.config.AuthProperties;

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
@ComponentScan("com.miqroera.miqrokey")
@EnableConfigurationProperties({AuthProperties.class, ApprovalProperties.class, AdminAccessProperties.class})
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
