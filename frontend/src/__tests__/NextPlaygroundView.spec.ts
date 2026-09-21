import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent } from 'vue';
import NextPlaygroundView from '@/views/next/NextPlaygroundView.vue';
import * as api from '@/api';
import type { MePlazaView } from '@/types/generated-api';

const push = vi.fn();
const routeQuery: Record<string, string> = {};

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ query: routeQuery }),
}));

vi.mock('@/api', () => ({
  getPlazaModels: vi.fn(),
}));

const mockApi = vi.mocked(api);

/** radix-vue popups are not deterministic in jsdom — same stub as the keys spec. */
const SelectStub = defineComponent({
  name: 'UiSelect',
  props: {
    modelValue: { type: String, default: '' },
    options: { type: Array, default: () => [] },
    label: { type: String, default: '' },
    placeholder: { type: String, default: '' },
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

const PLAZA: MePlazaView = {
  models: [
    {
      modelId: 'deepseek-v4-flash',
      displayName: 'DeepSeek V4 Flash',
      providerProductId: 'p1',
      providerProductCode: 'deepseek-api',
      providerProductName: 'DeepSeek API',
      price: {
        inputPerMillion: 0.14,
        outputPerMillion: 0.28,
        currency: 'USD',
      },
      keys: [],
    },
  ],
  requestable: [],
};

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

async function mountView() {
  const wrapper = mount(NextPlaygroundView, { global: { stubs: { UiSelect: SelectStub } } });
  await flushPromises();
  return wrapper;
}

async function fillKeyAndLoad(wrapper: Awaited<ReturnType<typeof mountView>>) {
  const keyInput = wrapper.get('[data-testid="playground-key"]');
  await keyInput.setValue('mqk_live_test_secret');
  await wrapper.get('[data-testid="playground-load-models"]').trigger('click');
  await flushPromises();
}

describe('NextPlaygroundView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
    Object.keys(routeQuery).forEach((k) => delete routeQuery[k]);
    push.mockClear();
    mockApi.getPlazaModels.mockReset();
    mockApi.getPlazaModels.mockResolvedValue(PLAZA);
  });

  it("reads the key's models from /v1/models and sends one real chat call", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    const fetchMock = vi.fn(async (url: string | URL, init?: RequestInit) => {
      calls.push({ url: String(url), init });
      if (String(url) === '/v1/models') {
        return jsonResponse({
          object: 'list',
          data: [{ id: 'deepseek-v4-flash' }, { id: 'glm-5.1' }],
        });
      }
      if (String(url) === '/v1/chat/completions') {
        return jsonResponse({
          choices: [{ message: { content: '你好，这是一次真实调用。' } }],
          usage: { prompt_tokens: 12, completion_tokens: 8, total_tokens: 20 },
        });
      }
      throw new Error(`unexpected fetch: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    routeQuery.model = 'glm-5.1';
    const wrapper = await mountView();
    await fillKeyAndLoad(wrapper);

    // /v1/models carried the pasted key as a bearer token.
    expect(calls[0]?.url).toBe('/v1/models');
    expect((calls[0]?.init?.headers as Record<string, string>)?.Authorization).toBe(
      'Bearer mqk_live_test_secret',
    );
    expect(wrapper.find('[data-testid="playground-models-count"]').text()).toContain('2');

    // The deep-linked model was preselected; switch to the flash model anyway
    // to prove the select drives the request body.
    await wrapper.get('.stub-option').trigger('click');
    await wrapper.get('[data-testid="playground-prompt"]').setValue('用一句话解释 API 网关');
    await wrapper.get('[data-testid="playground-send"]').trigger('click');
    await flushPromises();

    const chat = calls.find((c) => c.url === '/v1/chat/completions');
    expect(chat).toBeDefined();
    const body = JSON.parse(String(chat?.init?.body));
    expect(body.model).toBe('deepseek-v4-flash');
    expect(body.messages).toEqual([{ role: 'user', content: '用一句话解释 API 网关' }]);
    expect(body.max_tokens).toBe(512);

    expect(wrapper.find('[data-testid="playground-reply"]').text()).toContain('一次真实调用');
    const meta = wrapper.find('[data-testid="playground-meta"]').text();
    expect(meta).toContain('输入 12 tokens');
    expect(meta).toContain('输出 8 tokens');
    // 12*0.14 + 8*0.28 = 3.92 / 1e6 ≈ 3.9e-6 USD
    expect(meta).toContain('USD');

    vi.unstubAllGlobals();
  });

  it('renders the gateway 429 envelope with the retry hint', async () => {
    const fetchMock = vi.fn(async (url: string | URL) => {
      if (String(url) === '/v1/models') {
        return jsonResponse({ object: 'list', data: [{ id: 'deepseek-v4-flash' }] });
      }
      return jsonResponse(
        { error: { type: 'quota_exceeded', message: 'Monthly quota exceeded' } },
        429,
        { 'Retry-After': '3600' },
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const wrapper = await mountView();
    await fillKeyAndLoad(wrapper);
    await wrapper.get('.stub-option').trigger('click');
    await wrapper.get('[data-testid="playground-prompt"]').setValue('hello');
    await wrapper.get('[data-testid="playground-send"]').trigger('click');
    await flushPromises();

    const error = wrapper.find('[data-testid="playground-error"]').text();
    expect(error).toContain('Monthly quota exceeded');
    expect(error).toContain('quota_exceeded');
    expect(error).toContain('3600 秒后重试');

    vi.unstubAllGlobals();
  });

  it('reports a bad key instead of silently showing zero models', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ error: { type: 'invalid_api_key', message: 'Unknown virtual key' } }, 401),
    );
    vi.stubGlobal('fetch', fetchMock);

    const wrapper = await mountView();
    await fillKeyAndLoad(wrapper);

    const error = wrapper.find('[data-testid="playground-models-error"]').text();
    expect(error).toContain('Unknown virtual key');
    expect(error).toContain('invalid_api_key');

    vi.unstubAllGlobals();
  });
});
