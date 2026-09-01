import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import TDesign from 'tdesign-vue-next';
import AdminConsumersView from '@/views/AdminConsumersView.vue';
import * as api from '@/api';
import type { ApiConsumerView } from '@/types/api';

vi.mock('@/api', () => ({
  listApiConsumers: vi.fn(),
  createApiConsumer: vi.fn(),
  disableApiConsumer: vi.fn(),
}));

const mockApi = vi.mocked(api);

const consumer = (overrides: Partial<ApiConsumerView> = {}): ApiConsumerView => ({
  id: '01900000-0000-0000-0000-0000000000a1',
  name: 'platform',
  keyPrefix: '1d740c88',
  status: 'ACTIVE',
  createdAt: '2026-08-31T00:00:00Z',
  ...overrides,
});

function mountView() {
  return mount(AdminConsumersView, {
    global: { plugins: [TDesign, createPinia()] },
  });
}

describe('AdminConsumersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.listApiConsumers.mockResolvedValue([consumer()]);
  });

  it('renders the consumer list with masked key prefixes', async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="consumers-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('platform');
    expect(wrapper.text()).toContain('1d740c88…');
    expect(wrapper.text()).toContain('Active');
  });

  it('creates a consumer and reveals the one-time key', async () => {
    mockApi.listApiConsumers.mockResolvedValue([]);
    mockApi.createApiConsumer.mockResolvedValue({
      consumer: consumer(),
      apiKey: 'mqk_api_1d740c88_1d740c88650e837a01c58cb1bde3c7f1',
      shownOnce: true,
    });

    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="consumer-create-open"]').trigger('click');
    await wrapper.find('[data-testid="consumer-create-name"] input').setValue('platform');
    await wrapper.find('[data-testid="consumer-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createApiConsumer).toHaveBeenCalledWith('platform');
    expect(wrapper.find('[data-testid="consumer-key-value"]').text()).toContain('mqk_api_1d740c88');
    expect(mockApi.listApiConsumers).toHaveBeenCalledTimes(2);
  });

  it('shows an empty state when no consumers exist', async () => {
    mockApi.listApiConsumers.mockResolvedValue([]);

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain('还没有 API 消费者');
  });
});
