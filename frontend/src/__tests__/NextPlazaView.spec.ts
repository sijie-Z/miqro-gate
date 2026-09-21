import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextPlazaView from '@/views/next/NextPlazaView.vue';
import * as api from '@/api';
import type { MePlazaView } from '@/types/generated-api';

const push = vi.fn();

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ query: {} }),
}));

vi.mock('@/api', () => ({
  getPlazaModels: vi.fn(),
}));

const mockApi = vi.mocked(api);

const PLAZA: MePlazaView = {
  models: [
    {
      modelId: 'deepseek-v4-flash',
      displayName: 'DeepSeek V4 Flash',
      contextWindow: 128000,
      maxOutputTokens: 8192,
      providerProductId: 'p1',
      providerProductCode: 'deepseek-api',
      providerProductName: 'DeepSeek API',
      price: {
        inputPerMillion: 0.14,
        outputPerMillion: 0.28,
        currency: 'USD',
      },
      keys: [
        { id: 'k1', name: 'claude-code-main', display: 'mqk_live_…8f2a' },
        { id: 'k2', name: 'desktop-sync', display: 'mqk_live_…4ba9' },
        { id: 'k3', name: 'extra-key', display: 'mqk_live_…1111' },
      ],
    },
    {
      modelId: 'glm-5.1',
      displayName: 'GLM 5.1',
      contextWindow: 200000,
      providerProductId: 'p1',
      providerProductCode: 'deepseek-api',
      providerProductName: 'DeepSeek API',
      keys: [{ id: 'k1', name: 'claude-code-main', display: 'mqk_live_…8f2a' }],
    },
  ],
  requestable: [
    {
      modelId: 'deepseek-v4.1',
      displayName: 'DeepSeek V4.1',
      contextWindow: 128000,
      providerProductId: 'p1',
      providerProductCode: 'deepseek-api',
      providerProductName: 'DeepSeek API',
      keyId: 'k1',
      keyName: 'claude-code-main',
    },
  ],
};

async function mountView() {
  const wrapper = mount(NextPlazaView, { global: { stubs: { teleport: true } } });
  await flushPromises();
  return wrapper;
}

describe('NextPlazaView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
    push.mockClear();
    mockApi.getPlazaModels.mockReset();
  });

  it('renders usable models with prices, context and key chips', async () => {
    mockApi.getPlazaModels.mockResolvedValue(PLAZA);
    const wrapper = await mountView();

    const html = wrapper.text();
    expect(html).toContain('deepseek-v4-flash');
    expect(html).toContain('输入价');
    expect(html).toContain('0.14 USD');
    expect(html).toContain('0.28 USD');
    expect(html).toContain('128K');
    expect(html).toContain('claude-code-main');
    expect(html).toContain('+1'); // third key collapses
    // No snapshot at all: an em dash, never a zero (#878).
    expect(html).toContain('—');
    expect(html).not.toContain('0 USD');
  });

  it('renders the approvable section and deep-links into the approval form', async () => {
    mockApi.getPlazaModels.mockResolvedValue(PLAZA);
    const wrapper = await mountView();

    expect(wrapper.text()).toContain('可申请模型（1）');
    await wrapper.get('[data-testid="plaza-request-k1-deepseek-v4.1"]').trigger('click');
    expect(push).toHaveBeenCalledWith({
      path: '/app/model-approvals',
      query: { keyId: 'k1', model: 'deepseek-v4.1' },
    });
  });

  it('deep-links 试调 into the playground with the model preselected', async () => {
    mockApi.getPlazaModels.mockResolvedValue(PLAZA);
    const wrapper = await mountView();

    await wrapper.get('[data-testid="plaza-try-deepseek-v4-flash"]').trigger('click');
    expect(push).toHaveBeenCalledWith({
      path: '/app/playground',
      query: { model: 'deepseek-v4-flash' },
    });
  });

  it('explains the empty plaza instead of showing two blank tables', async () => {
    mockApi.getPlazaModels.mockResolvedValue({ models: [], requestable: [] });
    const wrapper = await mountView();

    expect(wrapper.find('[data-testid="plaza-empty"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="plaza-table"]').exists()).toBe(false);
  });

  it('surfaces a failed load with a retryable error, not an empty list', async () => {
    mockApi.getPlazaModels.mockRejectedValue(new Error('网络错误'));
    const wrapper = await mountView();

    expect(wrapper.find('[data-testid="plaza-load-error"]').text()).toContain('网络错误');
  });
});
