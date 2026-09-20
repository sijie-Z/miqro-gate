import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AttributionChip from '@/components/AttributionChip.vue';
import { UiTooltip } from '@/ui';

/**
 * #1128: the chip that tells a reader *why* a request belongs to the project it was
 * billed to. Two claims ride in it and they must not blur: the server's ruling (what
 * happened) and the client's claim (what was asserted, unverified).
 */
describe('AttributionChip', () => {
  function render(props: Record<string, unknown>) {
    return mount(AttributionChip, { props });
  }

  it('names each ruling in the product’s words', () => {
    const labels: Record<string, string> = {
      RESOLVED_HEADER: '按请求头声明',
      RESOLVED_SUFFIX: '按密钥后缀',
      SOLE_BINDING: '唯一绑定',
      POLICY_ROUTED: '未归属策略路由',
      UNATTRIBUTED: '未归属',
      AMBIGUOUS: '无从判定',
    };
    for (const [status, text] of Object.entries(labels)) {
      expect(render({ resolutionStatus: status }).text()).toContain(text);
    }
  });

  it('separates the ruling from the claim, and says the claim is unverified', () => {
    const wrapper = render({
      resolutionStatus: 'RESOLVED_HEADER',
      claimSource: 'prompt_url',
      claimConfidence: 'HIGH',
    });

    const note = wrapper.findComponent(UiTooltip).props('text');
    expect(note).toContain('归属由服务端裁定：按请求头声明');
    expect(note).toContain('客户端声明来源：提示中的链接，置信度 HIGH');
    expect(note).toContain('声明未经验证');
  });

  it('says so when there was no client claim at all', () => {
    const wrapper = render({ resolutionStatus: 'SOLE_BINDING' });

    expect(wrapper.findComponent(UiTooltip).props('text')).toContain('本次请求没有客户端声明');
  });

  it('shows a ruling nobody wrote words for rather than swallowing it', () => {
    // A new ruling value reaching the console should be visible, not blank.
    expect(render({ resolutionStatus: 'RESOLVED_FUTURE' }).text()).toContain('RESOLVED_FUTURE');
  });

  it('renders a dash, not a chip, for a row the ladder never saw', () => {
    const wrapper = render({ resolutionStatus: null });

    expect(wrapper.find('[data-testid="usage-attribution-chip"]').exists()).toBe(false);
    expect(wrapper.text()).toBe('—');
  });
});
