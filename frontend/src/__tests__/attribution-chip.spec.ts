import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import AttributionChip from '@/components/AttributionChip.vue';
import { UiTooltip } from '@/ui';

/**
 * #1128: the chip that tells a reader *why* a request belongs to the project it was
 * billed to. Two things ride in it and they must not blur: the server's ruling (what
 * happened) and the client's claim (what was asserted, unverified).
 *
 * It is also a filter, not a decoration: every authenticated request walks the ladder,
 * so a chip on every row would print 「唯一绑定」 down the whole table — the chip is for
 * the rows something *else* decided.
 */
describe('AttributionChip', () => {
  function render(props: Record<string, unknown>) {
    return mount(AttributionChip, { props });
  }

  it('names the rulings it shows in the product’s words', () => {
    const labels: Record<string, string> = {
      RESOLVED_HEADER: '按请求头声明',
      POLICY_ROUTED: '未归属策略路由',
      UNATTRIBUTED: '未归属',
      AMBIGUOUS: '无从判定',
    };
    for (const [status, text] of Object.entries(labels)) {
      expect(render({ resolutionStatus: status }).text()).toContain(text);
    }
  });

  it('marks the two "we could not place this" rulings, and leaves the decided ones neutral', () => {
    // The tone is the chip's only severity affordance — losing it turns an unattributed
    // row into an ordinary one at a glance.
    const toneOf = (status: string) =>
      render({ resolutionStatus: status }).find('.ui-badge').classes();
    expect(toneOf('UNATTRIBUTED')).toContain('ui-badge--warning');
    expect(toneOf('AMBIGUOUS')).toContain('ui-badge--warning');
    expect(toneOf('POLICY_ROUTED')).toContain('ui-badge--info');
    expect(toneOf('RESOLVED_HEADER')).toContain('ui-badge--neutral');
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

  it('names every claim source the resolver accepts, including git_remote', () => {
    const sources: Record<string, string> = {
      prompt_url: '提示中的链接',
      tool_path: '工具读取的路径',
      bash_cwd: '命令的工作目录',
      system_cwd: '进程的工作目录',
      git_remote: '仓库远端',
      suffix: '密钥后缀',
      none: '无',
    };
    for (const [source, text] of Object.entries(sources)) {
      const note = render({ resolutionStatus: 'RESOLVED_HEADER', claimSource: source })
        .findComponent(UiTooltip)
        .props('text');
      expect(note).toContain(`客户端声明来源：${text}`);
    }
  });

  it('shows a claim source nobody wrote words for rather than swallowing it', () => {
    const note = render({ resolutionStatus: 'RESOLVED_HEADER', claimSource: 'future_source' })
      .findComponent(UiTooltip)
      .props('text');
    expect(note).toContain('future_source');
  });

  it('does not claim the client sent nothing — the column cannot tell that from "we dropped it"', () => {
    const note = render({ resolutionStatus: 'RESOLVED_HEADER' })
      .findComponent(UiTooltip)
      .props('text');

    expect(note).toContain('未记录客户端声明');
    expect(note).toContain('未通过校验');
    expect(note).not.toContain('本次请求没有客户端声明');
  });

  it('shows a ruling nobody wrote words for rather than swallowing it', () => {
    expect(render({ resolutionStatus: 'RESOLVED_FUTURE' }).text()).toContain('RESOLVED_FUTURE');
  });

  it('stays quiet on the ordinary routes', () => {
    // Both are ordinary: a key is minted with its project's tag as the suffix, so the
    // suffix step matches first — a single-binding key's rows read RESOLVED_SUFFIX.
    // Chipping those would print 「按密钥后缀」 down the whole table.
    for (const status of ['RESOLVED_SUFFIX', 'SOLE_BINDING']) {
      const wrapper = render({ resolutionStatus: status });
      expect(wrapper.find('[data-testid="usage-attribution-chip"]').exists()).toBe(false);
      expect(wrapper.text()).toBe('—');
    }
  });

  it('speaks up for a routing that could not place the request', () => {
    // The rows a troubleshooter is hunting: no claim, and the ladder fell through to a
    // policy route or nothing at all.
    expect(render({ resolutionStatus: 'POLICY_ROUTED' }).text()).toContain('未归属策略路由');
    expect(render({ resolutionStatus: 'UNATTRIBUTED' }).text()).toContain('未归属');
  });

  it('speaks up whenever a claim was recorded, whatever ruled', () => {
    // The claim is the signal: someone is using the context mechanism on this path.
    const wrapper = render({ resolutionStatus: 'SOLE_BINDING', claimSource: 'bash_cwd' });

    expect(wrapper.find('[data-testid="usage-attribution-chip"]').text()).toContain('唯一绑定');
    expect(wrapper.findComponent(UiTooltip).props('text')).toContain('命令的工作目录');
  });

  it('renders a dash, not a chip, for a row written before the columns existed', () => {
    const wrapper = render({ resolutionStatus: null });

    expect(wrapper.find('[data-testid="usage-attribution-chip"]').exists()).toBe(false);
    expect(wrapper.text()).toBe('—');
  });
});
