import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminWebhooksView from '@/views/next/NextAdminWebhooksView.vue';
import * as api from '@/api';
import { toastState } from '@/ui/toast';
import type { WebhookDelivery, WebhookEndpointView } from '@/types/api';

vi.mock('@/api', () => ({
  listWebhooks: vi.fn(),
  createWebhook: vi.fn(),
  updateWebhook: vi.fn(),
  deleteWebhook: vi.fn(),
  testWebhook: vi.fn(),
  webhookDeliveries: vi.fn(),
}));

const mockApi = vi.mocked(api);

const endpoint = (overrides: Partial<WebhookEndpointView> = {}): WebhookEndpointView => ({
  id: 'w1',
  name: 'ops-alerts',
  url: 'https://alerts.internal/hook',
  enabled: true,
  timeoutMs: 5000,
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

const delivery = (overrides: Partial<WebhookDelivery> = {}): WebhookDelivery => ({
  id: 'd1',
  eventId: 'ev1',
  endpointId: 'w1',
  attempt: 1,
  httpStatus: 200,
  nextRetryAt: undefined,
  errorMessage: undefined,
  createdAt: '2026-09-02T00:00:00Z',
  ...overrides,
});

describe('NextAdminWebhooksView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    toastState.items.splice(0);
    document.body.innerHTML = '';
    mockApi.listWebhooks.mockResolvedValue([]);
  });

  function mountView() {
    return mount(NextAdminWebhooksView, { global: { plugins: [createPinia()] } });
  }

  it('renders endpoints with Chinese statuses', async () => {
    mockApi.listWebhooks.mockResolvedValue([
      endpoint(),
      endpoint({ id: 'w2', name: 'sre-hook', enabled: false }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="webhooks-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('ops-alerts');
    expect(wrapper.text()).toContain('https://alerts.internal/hook');
    expect(wrapper.text()).toContain('已启用');
    expect(wrapper.text()).toContain('已停用');
  });

  it('creates a webhook with secret and numeric timeout', async () => {
    mockApi.createWebhook.mockResolvedValue(endpoint());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="webhook-create-open"]').trigger('click');
    await wrapper.find('[data-testid="webhook-create-name"]').setValue('ci-alerts');
    await wrapper.find('[data-testid="webhook-create-url"]').setValue('https://ci.internal/hook');
    await wrapper.find('[data-testid="webhook-create-secret"]').setValue('s3cret-value');
    await wrapper.find('[data-testid="webhook-create-timeout"]').setValue('3000');
    await wrapper.find('[data-testid="webhook-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.createWebhook).toHaveBeenCalledWith({
      name: 'ci-alerts',
      url: 'https://ci.internal/hook',
      secret: 's3cret-value',
      timeoutMs: 3000,
    });
  });

  it('runs a signature test and reports the HTTP status', async () => {
    mockApi.listWebhooks.mockResolvedValue([endpoint()]);
    mockApi.testWebhook.mockResolvedValue({ httpStatus: 200 });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="webhook-test"]').trigger('click');
    await flushPromises();

    expect(mockApi.testWebhook).toHaveBeenCalledWith('w1');
    expect(toastState.items.some((t) => t.message.includes('测试投递成功（HTTP 200）'))).toBe(true);
  });

  it('shows the delivery history drawer', async () => {
    mockApi.listWebhooks.mockResolvedValue([endpoint()]);
    mockApi.webhookDeliveries.mockResolvedValue([
      delivery(),
      delivery({ id: 'd2', attempt: 2, httpStatus: 502, errorMessage: 'upstream refused' }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="webhook-deliveries"]').trigger('click');
    await flushPromises();

    expect(mockApi.webhookDeliveries).toHaveBeenCalledWith('w1');
    const table = document.querySelector('[data-testid="deliveries-table"]');
    expect(table, 'deliveries drawer table should render').toBeTruthy();
    expect(table!.textContent).toContain('502');
    expect(table!.textContent).toContain('upstream refused');
  });

  it('deletes a webhook through the confirm gate', async () => {
    mockApi.listWebhooks.mockResolvedValue([endpoint()]);
    mockApi.deleteWebhook.mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="webhook-delete"]').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '删除' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm, 'confirm dialog should render').toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.deleteWebhook).toHaveBeenCalledWith('w1');
  });
});
