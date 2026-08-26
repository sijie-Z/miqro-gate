package com.miqroera.miqrokey.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.aliyun.AliyunBailianAdapter;
import com.miqroera.miqrokey.adapters.baidu.BaiduQianfanAdapter;
import com.miqroera.miqrokey.adapters.deepseek.DeepSeekPaygAdapter;
import com.miqroera.miqrokey.adapters.minimax.MiniMaxAdapter;
import com.miqroera.miqrokey.adapters.moonshot.MoonshotKimiAdapter;
import com.miqroera.miqrokey.adapters.registry.BuiltInAdapterRegistry;
import com.miqroera.miqrokey.adapters.tencent.TencentTokenHubAdapter;
import com.miqroera.miqrokey.adapters.volcengine.VolcengineArkAdapter;
import com.miqroera.miqrokey.adapters.zhipu.ZhipuGlmAdapter;
import com.miqroera.miqrokey.controlplane.client.HttpProviderClient;
import com.miqroera.miqrokey.controlplane.client.ProviderClientFactory;
import com.miqroera.miqrokey.domain.security.UpstreamTargetValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProviderClientConfig (G3.1-G3.8)")
class ProviderClientConfigTest {

    private final ProviderClientConfig config = new ProviderClientConfig();

    @Test
    @DisplayName("registers every implemented adapter family at compile time")
    void registryContainsAllImplementedAdapters() {
        BuiltInAdapterRegistry registry = config.adapterRegistry(new ObjectMapper());

        assertThat(registry.adapterIds()).containsExactly("aliyun-coding-plan", "aliyun-payg-api",
                "aliyun-token-plan-team", "baidu-coding-plan", "baidu-payg-api", "baidu-token-plan-personal",
                "deepseek-payg-api", "minimax-payg-api", "minimax-token-plan-personal", "minimax-token-plan-team",
                "moonshot-kimi-code-member", "moonshot-payg-api", "tencent-coding-plan", "tencent-payg-api",
                "tencent-token-plan-enterprise-lite", "tencent-token-plan-enterprise-pro",
                "tencent-token-plan-personal", "volcengine-agent-plan", "volcengine-coding-plan", "volcengine-payg-api",
                "zhipu-coding-plan-personal", "zhipu-coding-plan-team", "zhipu-payg-api");
        assertThat(registry.findById(DeepSeekPaygAdapter.ADAPTER_ID)).isPresent().get()
                .isInstanceOf(DeepSeekPaygAdapter.class);
        assertThat(registry.findById("tencent-coding-plan")).isPresent().get()
                .isInstanceOf(TencentTokenHubAdapter.class);
        assertThat(registry.findById("zhipu-coding-plan-team")).isPresent().get().isInstanceOf(ZhipuGlmAdapter.class);
        assertThat(registry.findById("minimax-token-plan-team")).isPresent().get().isInstanceOf(MiniMaxAdapter.class);
        assertThat(registry.findById("moonshot-payg-api")).isPresent().get().isInstanceOf(MoonshotKimiAdapter.class);
        assertThat(registry.findById("baidu-coding-plan")).isPresent().get().isInstanceOf(BaiduQianfanAdapter.class);
        assertThat(registry.findById("volcengine-agent-plan")).isPresent().get()
                .isInstanceOf(VolcengineArkAdapter.class);
        assertThat(registry.findById("aliyun-token-plan-team")).isPresent().get()
                .isInstanceOf(AliyunBailianAdapter.class);
    }

    @Test
    @DisplayName("production default validator allows no private targets")
    void productionValidatorAllowsNothingPrivate() {
        UpstreamTargetValidator validator = config.controlPlaneTargetValidator(new ProviderClientProperties());

        assertThat(validator.allowsPrivateTargets()).isFalse();
    }

    @Test
    @DisplayName("the configured allowlist extends the validator for local provider gateways")
    void configuredAllowlistExtendsValidator() {
        ProviderClientProperties properties = new ProviderClientProperties();
        properties.setAllowedCidrs(List.of("127.0.0.0/8"));
        UpstreamTargetValidator validator = config.controlPlaneTargetValidator(properties);

        assertThat(validator.allowsPrivateTargets()).isTrue();
    }

    @Test
    @DisplayName("the factory builds credential-scoped HttpProviderClient instances")
    void factoryBuildsCredentialScopedClients() {
        ProviderClientFactory factory = config.providerClientFactory(
                config.controlPlaneTargetValidator(new ProviderClientProperties()), new ProviderClientProperties());

        assertThat(factory.create(URI.create("https://api.deepseek.com"), "Authorization", "Bearer sk-a"))
                .isInstanceOf(HttpProviderClient.class);
    }
}
