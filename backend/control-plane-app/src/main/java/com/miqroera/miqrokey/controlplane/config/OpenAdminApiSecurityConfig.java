package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.miqrokey.controlplane.security.AdminApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.domain.repository.AdminApiKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registration of the open admin surface auth filter (ADR-0015). */
@Configuration
public class OpenAdminApiSecurityConfig {

    @Bean
    public AdminApiKeyAuthFilter adminApiKeyAuthFilter(AdminApiKeyRepository repository, UserContext userContext,
            AuditService auditService) {
        return new AdminApiKeyAuthFilter(repository, userContext, auditService);
    }

    @Bean
    public FilterRegistrationBean<AdminApiKeyAuthFilter> adminApiKeyAuthFilterRegistration(
            AdminApiKeyAuthFilter filter) {
        FilterRegistrationBean<AdminApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/v1/admin-api/*");
        // After the SessionFilter (-100): a portal session short-circuits.
        registration.setOrder(-95);
        return registration;
    }
}
