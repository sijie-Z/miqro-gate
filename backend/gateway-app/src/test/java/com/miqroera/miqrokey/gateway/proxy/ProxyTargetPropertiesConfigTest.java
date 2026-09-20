package com.miqroera.miqrokey.gateway.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #PH48 配置生效性：{@code ProxyTargetProperties} 声明的每个配置项，都必须在随包发布的
 * {@code application.yml} 里有一个键。
 *
 * <p>
 * 一个绑定了前缀、却既没有 yml 键、也没有任何读取点的记录分量，是「幽灵配置项」：
 * 运维按文档设了环境变量以为生效了，实际连绑定都到不了（relaxed 绑定要求环境变量名
 * 展开后正好等于「前缀 + 分量」）。
 * </p>
 */
class ProxyTargetPropertiesConfigTest {

    @Test
    void everyDeclaredUpstreamPropertyHasAKeyInApplicationYml() throws Exception {
        Set<String> declared = declaredUpstreamKeys();
        Set<String> missing = new LinkedHashSet<>();
        for (RecordComponent component : ProxyTargetProperties.class.getRecordComponents()) {
            String key = toKebabCase(component.getName());
            if (!declared.contains(key)) {
                missing.add(key);
            }
        }
        assertThat(missing)
                .as("ProxyTargetProperties 声明了、但 application.yml 的 miqrokey.gateway.upstream 下没有对应键的配置项")
                .isEmpty();
    }

    /** {@code miqrokey.gateway.upstream} 下 {@code application.yml} 实际声明的键。 */
    private static Set<String> declaredUpstreamKeys() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        Map<String, Object> declared = binder
                .bind("miqrokey.gateway.upstream", Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        return declared.keySet().stream()
                .map(key -> key.contains(".") ? key.substring(0, key.indexOf('.')) : key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String toKebabCase(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }
}
