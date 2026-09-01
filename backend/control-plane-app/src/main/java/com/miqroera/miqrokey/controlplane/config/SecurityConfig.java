package com.miqroera.miqrokey.controlplane.config;

import com.miqroera.miqrokey.controlplane.security.AuthenticationService;
import com.miqroera.miqrokey.controlplane.security.CsrfInterceptor;
import com.miqroera.miqrokey.controlplane.security.OriginInterceptor;
import com.miqroera.miqrokey.controlplane.security.RoleInterceptor;
import com.miqroera.miqrokey.controlplane.security.ApiKeyAuthFilter;
import com.miqroera.miqrokey.controlplane.security.SessionFilter;
import com.miqroera.miqrokey.controlplane.security.SessionService;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.domain.repository.ApiConsumerRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private final CsrfInterceptor csrfInterceptor;
    private final RoleInterceptor roleInterceptor;
    private final OriginInterceptor originInterceptor;

    // Self-injection wiring
    private final AuthenticationService authenticationService;
    private final AuthProperties authProperties;

    public SecurityConfig(CsrfInterceptor csrfInterceptor, RoleInterceptor roleInterceptor,
            OriginInterceptor originInterceptor, AuthenticationService authenticationService,
            AuthProperties authProperties) {
        this.csrfInterceptor = csrfInterceptor;
        this.roleInterceptor = roleInterceptor;
        this.originInterceptor = originInterceptor;
        this.authenticationService = authenticationService;
        this.authProperties = authProperties;
    }

    @PostConstruct
    void wireSelfInjection() {
        authenticationService.setSelf(authenticationService);
    }

    @Bean
    public SessionFilter sessionFilter(SessionService sessionService, UserRepository userRepository,
            UserContext userContext) {
        return new SessionFilter(sessionService, userRepository, userContext, authProperties);
    }

    @Bean
    public FilterRegistrationBean<SessionFilter> sessionFilterRegistration(SessionFilter sessionFilter) {
        FilterRegistrationBean<SessionFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(sessionFilter);
        registration.addUrlPatterns("/api/*");
        // Run AFTER Spring Boot's RequestContextFilter (order -105), which
        // binds the request to the current thread: the SessionFilter writes
        // the request-scoped UserContext, and with HIGHEST_PRECEDENCE it ran
        // before the request scope existed — every authenticated request
        // 500'd with ScopeNotActiveException on a real servlet container
        // (MockMvc never exposed this).
        registration.setOrder(-100);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/v1/billing/*");
        // After the SessionFilter: a portal admin session short-circuits.
        registration.setOrder(-90);
        return registration;
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(ApiConsumerRepository consumerRepository, UserContext userContext) {
        return new ApiKeyAuthFilter(consumerRepository, userContext);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(originInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(csrfInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(roleInterceptor).addPathPatterns("/api/**");
    }
}
