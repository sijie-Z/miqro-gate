package com.miqroera.miqrokey.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #PH48 配置生效性：{@code MIQROKEY_LOG_LEVEL} 必须能调整网关自身包的日志级别。
 *
 * <p>
 * {@code docs/configuration-reference.md} 把 {@code MIQROKEY_LOG_LEVEL} 记为全局
 * 日志级别开关，本模块的 {@code logback-spring.xml} 也确实用它设置 {@code <root>}。但 Spring Boot 在
 * logback 初始化**之后**才应用 {@code logging.level.*} 映射：只要 {@code application.yml} 把
 * {@code logging.level.com.miqroera.miqrokey} 写成字面量，root 级别就被静默压住。
 * </p>
 *
 * <p>
 * 本测试按 Spring Boot 的方式绑定 {@code logging.level} 映射，断言
 * {@code MIQROKEY_LOG_LEVEL=DEBUG} 时该包的解析结果为 {@code DEBUG}。
 * </p>
 */
class GatewayLogLevelConfigTest {

    @Test
    void packageLogLevelFollowsMiqrokeyLogLevel() throws Exception {
        assertThat(resolvedPackageLogLevel("DEBUG")).isEqualTo("DEBUG");
    }

    @Test
    void packageLogLevelDefaultsToInfo() throws Exception {
        assertThat(resolvedPackageLogLevel(null)).isEqualTo("INFO");
    }

    /** 按 Spring Boot 的方式绑定 application.yml 的 {@code logging.level} 映射。 */
    private static String resolvedPackageLogLevel(String miqrokeyLogLevel) throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        if (miqrokeyLogLevel != null) {
            sources.addFirst(new SystemEnvironmentPropertySource("test-environment",
                    Map.of("MIQROKEY_LOG_LEVEL", miqrokeyLogLevel)));
        }
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        Map<String, String> levels = binder.bind("logging.level", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        return levels.get("com.miqroera.miqrokey");
    }
}
