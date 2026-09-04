import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import NextAdminAuditView from '@/views/next/NextAdminAuditView.vue';
import * as api from '@/api';

vi.mock('@/api', () => ({ auditEvents: vi.fn() }));
const mockApi = vi.mocked(api);

const event = {
  id: 'a1',
  chainPosition: 12,
  actorId: 'root',
  action: 'LOGIN_SUCCESS',
  targetType: 'USER',
  changeSummary: 'root 登录成功',
  createdAt: '2026-09-03T08:00:00Z',
};

describe('NextAdminAuditView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    mockApi.auditEvents.mockResolvedValue([event]);
  });
  function mountView() {
    return mount(NextAdminAuditView, { global: { plugins: [createPinia()] } });
  }
  it('renders audit events with chain positions', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="audit-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('LOGIN_SUCCESS');
    expect(wrapper.text()).toContain('root 登录成功');
  });
  it('filters by action', async () => {
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="audit-action-filter"]').setValue('LOGIN_SUCCESS');
    await wrapper.find('[data-testid="audit-refresh"]').trigger('click');
    await flushPromises();
    expect(mockApi.auditEvents).toHaveBeenLastCalledWith({ action: 'LOGIN_SUCCESS' });
  });
});
