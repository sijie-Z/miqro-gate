import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextAdminServicesView from '@/views/next/NextAdminServicesView.vue';
import * as api from '@/api';
import type { InternalServiceView } from '@/types/generated-api';

vi.mock('@/api', () => ({
  adminListServices: vi.fn(),
  adminCreateService: vi.fn(),
  adminDisableService: vi.fn(),
}));

const mockApi = vi.mocked(api);

const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
  },
  emits: ['update:modelValue', 'change'],
  setup(props, { emit }) {
    function pick(value: unknown) {
      emit('update:modelValue', value);
      emit('change', value);
    }
    return { pick, props };
  },
  template: `
    <div class="ui-select-stub">
      <label v-if="props.label">{{ props.label }}</label>
      <button
        v-for="opt in props.options"
        :key="opt.value"
        type="button"
        class="stub-option"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>
  `,
});

const service = (overrides: Partial<InternalServiceView> = {}): InternalServiceView => ({
  id: 'sv1',
  name: 'platform-api',
  kind: 'HTTP',
  description: '平台内部 API',
  baseUrl: 'https://platform.internal.example',
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  ...overrides,
});

describe('NextAdminServicesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    document.body.innerHTML = '';
    mockApi.adminListServices.mockResolvedValue([]);
  });

  function mountView() {
    return mount(NextAdminServicesView, {
      global: { plugins: [createPinia()], stubs: { UiSelect: SelectStub } },
    });
  }

  it('renders services with Chinese statuses', async () => {
    mockApi.adminListServices.mockResolvedValue([
      service(),
      service({ id: 'sv2', name: 'erp-mcp', kind: 'MCP', status: 'DISABLED' }),
    ]);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="services-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('platform-api');
    expect(wrapper.text()).toContain('erp-mcp');
    expect(wrapper.text()).toContain('https://platform.internal.example');
    expect(wrapper.text()).toContain('正常');
    expect(wrapper.text()).toContain('已禁用');
  });

  it('registers a service with the chosen kind', async () => {
    mockApi.adminCreateService.mockResolvedValue(service());
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="service-create-open"]').trigger('click');
    await wrapper.find('[data-testid="service-create-name"]').setValue('billing-api');
    const options = wrapper.findAll('.stub-option');
    expect(options.map((o) => o.text())).toEqual(['HTTP', 'MCP', 'Other']);
    await wrapper
      .findAll('.stub-option')
      .find((o) => o.text() === 'MCP')!
      .trigger('click');
    await wrapper
      .find('[data-testid="service-create-url"]')
      .setValue('https://billing.internal.example');
    await flushPromises();
    await wrapper.find('[data-testid="service-create-submit"]').trigger('click');
    await flushPromises();

    expect(mockApi.adminCreateService).toHaveBeenCalledWith({
      name: 'billing-api',
      kind: 'MCP',
      description: undefined,
      baseUrl: 'https://billing.internal.example',
    });
  });

  it('disables a service through the confirm gate', async () => {
    mockApi.adminListServices.mockResolvedValue([service()]);
    mockApi.adminDisableService.mockResolvedValue(service({ status: 'DISABLED' }));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('[data-testid="service-disable"]').trigger('click');
    await flushPromises();
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[];
    const confirm = buttons.find(
      (b) => b.textContent?.trim() === '禁用' && b.className.includes('ui-btn--danger'),
    );
    expect(confirm, 'confirm dialog should render').toBeTruthy();
    confirm!.click();
    await flushPromises();

    expect(mockApi.adminDisableService).toHaveBeenCalledWith('sv1');
  });
});
