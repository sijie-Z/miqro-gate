package com.miqroera.miqrokey.adapters.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.adapters.aliyun.AliyunBailianAdapter;
import com.miqroera.miqrokey.adapters.baidu.BaiduQianfanAdapter;
import com.miqroera.miqrokey.adapters.deepseek.DeepSeekPaygAdapter;
import com.miqroera.miqrokey.adapters.minimax.MiniMaxAdapter;
import com.miqroera.miqrokey.adapters.moonshot.MoonshotKimiAdapter;
import com.miqroera.miqrokey.adapters.tencent.TencentTokenHubAdapter;
import com.miqroera.miqrokey.adapters.volcengine.VolcengineArkAdapter;
import com.miqroera.miqrokey.adapters.zhipu.ZhipuGlmAdapter;

/**
 * Single compile-time registration point for every built-in adapter
 * (G3.1–G3.8). Both the control plane and the gateway data plane build their
 * registry from here so the relay path (gateway) and the provider-call path
 * (control plane) always see the same adapter set.
 */
public final class AdapterRegistryFactory {

    private AdapterRegistryFactory() {
    }

    public static BuiltInAdapterRegistry create(ObjectMapper objectMapper) {
        BuiltInAdapterRegistry registry = new BuiltInAdapterRegistry();
        registry.register(new DeepSeekPaygAdapter(objectMapper));
        registry.register(TencentTokenHubAdapter.codingPlan(objectMapper));
        registry.register(TencentTokenHubAdapter.tokenPlanPersonal(objectMapper));
        registry.register(TencentTokenHubAdapter.enterprisePro(objectMapper));
        registry.register(TencentTokenHubAdapter.enterpriseLite(objectMapper));
        registry.register(TencentTokenHubAdapter.paygApi(objectMapper));
        registry.register(ZhipuGlmAdapter.codingPlanPersonal(objectMapper));
        registry.register(ZhipuGlmAdapter.codingPlanTeam(objectMapper));
        registry.register(ZhipuGlmAdapter.paygApi(objectMapper));
        registry.register(MiniMaxAdapter.tokenPlanPersonal(objectMapper));
        registry.register(MiniMaxAdapter.tokenPlanTeam(objectMapper));
        registry.register(MiniMaxAdapter.paygApi(objectMapper));
        registry.register(MoonshotKimiAdapter.kimiCodeMember(objectMapper));
        registry.register(MoonshotKimiAdapter.paygApi(objectMapper));
        registry.register(BaiduQianfanAdapter.codingPlan(objectMapper));
        registry.register(BaiduQianfanAdapter.tokenPlanPersonal(objectMapper));
        registry.register(BaiduQianfanAdapter.paygApi(objectMapper));
        registry.register(VolcengineArkAdapter.codingPlan(objectMapper));
        registry.register(VolcengineArkAdapter.agentPlan(objectMapper));
        registry.register(VolcengineArkAdapter.paygApi(objectMapper));
        registry.register(AliyunBailianAdapter.codingPlan(objectMapper));
        registry.register(AliyunBailianAdapter.tokenPlanTeam(objectMapper));
        registry.register(AliyunBailianAdapter.paygApi(objectMapper));
        return registry;
    }
}
