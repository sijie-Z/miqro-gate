import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminConsumersView from '@/views/next/NextAdminConsumersView.vue';
import * as api from '@/api';

vi.mock('@/api', () => ({
  listApiConsumers: vi.fn(),
  createApiConsumer: vi.fn(),
  disableApiConsumer: vi.fn(),
  adminConsumerActivity: vi.fn(),
}));
const mockApi = vi.mocked(api);

const consumer = {
  id: 'k1',
  name: 'billing-sync',
  keyPrefix: 'mqk_ext_a1b2c3d4',
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
};

describe('NextAdminConsumersView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    mockApi.listApiConsumers.mockResolvedValue([consumer]);
  });
  function mountView() {
    return mount(NextAdminConsumersView, { global: { plugins: [createPinia()] } });
  }
  it('renders consumers with prefixes and statuses', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="consumers-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('billing-sync');
    expect(wrapper.text()).toContain('mqk_ext_a1b2c3d4');
    expect(wrapper.text()).toContain('正常');
  });
  it('creates a consumer and reveals the one-shot key with ack gate', async () => {
    mockApi.createApiConsumer.mockResolvedValue({
      consumer: { ...consumer, id: 'k9', name: 'nightly' },
      apiKey: 'mqk_ext_secretkey',
      shownOnce: true,
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="consumer-create-open"]').trigger('click');
    await wrapper.find('[data-testid="consumer-create-name"]').setValue('nightly');
    await wrapper.find('[data-testid="consumer-create-submit"]').trigger('click');
    await flushPromises();
    expect(mockApi.createApiConsumer).toHaveBeenCalledWith('nightly', undefined);
    expect(document.body.textContent).toContain('mqk_ext_secretkey');
    const close = document.querySelector('[data-testid="consumer-key-close"]') as HTMLButtonElement;
    expect(close.disabled).toBe(true);
    (document.querySelector('[data-testid="consumer-key-ack"]') as HTMLInputElement).click();
    await flushPromises();
    expect(close.disabled).toBe(false);
  });
  it('opens the call overview dialog with aggregates', async () => {
    mockApi.adminConsumerActivity.mockResolvedValue({
      consumerId: 'k1',
      windowHours: 24,
      totalCalls: 5,
      forwarded: 3,
      denied: 1,
      failed: 1,
      lastCallAt: '2026-09-10T08:00:00Z',
      topTools: [{ name: 'echo-tool', calls: 3 }],
      topServices: [{ name: 'svc-1', calls: 3 }],
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="consumer-activity"]').trigger('click');
    await flushPromises();
    expect(mockApi.adminConsumerActivity).toHaveBeenCalledWith('k1', 24);
    const body = document.querySelector('[data-testid="consumer-activity-body"]');
    expect(body?.textContent).toContain('echo-tool');
    expect(body?.textContent).toContain('总调用');
  });
  it('revokes a consumer through the gate', async () => {
    mockApi.disableApiConsumer.mockResolvedValue({ ...consumer, status: 'DISABLED' });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="consumer-disable"]').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '吊销' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm).toBeTruthy();
    confirm!.click();
    await flushPromises();
    expect(mockApi.disableApiConsumer).toHaveBeenCalledWith('k1');
  });
});
