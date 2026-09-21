<script setup lang="ts">
/**
 * NextPlansView — /app/plans v2 admin page (U2 platform batch).
 * Behaviour parity with legacy plans page: subscription catalogue with
 * rolling-quota bands and a seats drawer (assign / release with gate).
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { countWhenLoaded } from '@/utils/load-state';
import {
  usedInputOutputTokens,
  windowRanges,
  type QuotaWindowRange,
} from '@/lib/quota-window-usage';
import { ApiError } from '@/api/http';
import {
  UiButton,
  UiDialog,
  UiDrawer,
  UiInput,
  UiSelect,
  UiStatusBadge,
  UiTable,
  toast,
} from '@/ui';
import type { UiSelectOption } from '@/ui';
import type { ProviderProductView } from '@/types/api';
import type { SubscriptionView, SeatView } from '@/types/generated-api';

const subscriptions = ref<SubscriptionView[]>([]);
const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const billingModeLabel: Record<string, string> = {
  FIXED_SUBSCRIPTION: '固定订阅',
  PAYG: '按量付费',
};

const columns = [
  { key: 'productName', title: '产品', minWidth: '200px' },
  { key: 'name', title: '名称', minWidth: '160px' },
  {
    key: 'billingMode',
    title: '计费模式',
    width: '150px',
    format: (value: unknown) =>
      value == null ? '—' : (billingModeLabel[String(value)] ?? String(value)),
  },
  { key: 'planScope', title: '套餐形态', width: '110px' },
  { key: 'price', title: '价格', width: '130px', align: 'right' as const },
  { key: 'quota', title: '窗口用量', minWidth: '260px' },
  { key: 'status', title: '状态', width: '100px' },
  { key: 'actions', title: '操作', width: '90px', align: 'center' as const },
];

const seatColumns = [
  { key: 'user', title: '用户', minWidth: '140px' },
  { key: 'seatStatus', title: '状态', width: '110px' },
  { key: 'release', title: '', width: '90px', align: 'center' as const },
];

const creating = ref(false);
const form = ref({
  providerProductId: '',
  name: '',
  billingMode: 'FIXED_SUBSCRIPTION',
  planScope: 'PERSONAL',
  subscriptionPrice: '',
  currency: 'USD',
  quotaTotal: '',
  quotaUnit: '',
});
const formError = ref('');
const submitting = ref(false);

const productOptions = computed<UiSelectOption[]>(() =>
  products.value.map((p) => ({
    value: p.id,
    label: `${p.providerName} · ${p.displayName}`,
  })),
);

const billingOptions: UiSelectOption[] = [
  { value: 'FIXED_SUBSCRIPTION', label: '固定订阅' },
  { value: 'PAYG', label: '按量付费' },
  { value: 'TOKEN_PACKAGE', label: 'TOKEN_PACKAGE' },
  { value: 'CREDIT_POOL', label: 'CREDIT_POOL' },
];

const planOptions: UiSelectOption[] = [
  { value: 'PERSONAL', label: '个人套餐' },
  { value: 'TEAM', label: '团队套餐' },
  { value: 'ENTERPRISE', label: '企业套餐' },
  { value: 'NONE', label: '无' },
];

const quotaUnitOptions: UiSelectOption[] = [
  { value: 'POINTS', label: '积分' },
  { value: 'TOKENS', label: 'Token' },
  { value: 'REQUESTS', label: '请求次数' },
];

const seatDrawer = ref(false);
const seatSubscription = ref<SubscriptionView | null>(null);
const seats = ref<SeatView[]>([]);
const seatLoading = ref(false);
const seatAssignUser = ref('');
const seatDisplay = ref('');
const seatError = ref('');
/** #1160: 席位**读取**失败（与保存/校验槽 seatError 分开）。 */
const seatsLoadError = ref('');
const seatSubmitting = ref(false);

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

function planLabel(scope?: string): string {
  switch (scope) {
    case 'PERSONAL':
      return '个人套餐';
    case 'TEAM':
      return '团队套餐';
    case 'ENTERPRISE':
      return '企业套餐';
    default:
      return scope ?? '—';
  }
}

// ---- #1234: 配额列接真实窗口用量 ----
//
// 每订阅 × 三窗口（5 小时滚动 / 本周 / 本月，UTC 日历，口径见 @/lib/quota-window-usage）
// = 3 次 adminUsageSummary 读取，只读 totals，已用量 = 输入+输出 Token（网关侧统计）。
// 每窗口的额度在当前数据模型里不存在（订阅只有单一 quota_total），所以不画比例：
// 行尾的 quota_total 以「方案总额度」如实相称，不做任何分母。

interface QuotaWindowUsage {
  key: QuotaWindowRange['key'];
  label: string;
  used: number;
}

interface QuotaUsageState {
  windows: QuotaWindowUsage[];
  /** Row-level read failure (#943/#1160 家族)：读不到就不画数字。 */
  error: string;
  loading: boolean;
}

/** Per-subscription usage state, keyed by subscription id. */
const quotaUsage = ref<Record<string, QuotaUsageState>>({});

/** One subscription's three window reads; a failure lands on this row only. */
async function loadQuotaUsageRow(subscriptionId: string, ranges: QuotaWindowRange[]) {
  const state = quotaUsage.value[subscriptionId];
  if (!state) return;
  state.loading = true;
  state.error = '';
  try {
    const summaries = await Promise.all(
      ranges.map((range) =>
        api.adminUsageSummary({ subscriptionId, from: range.from, to: range.to }),
      ),
    );
    state.windows = ranges.map((range, i) => {
      const used = usedInputOutputTokens(summaries[i]?.totals?.tokens);
      if (used === null) {
        // 2xx 但形状读不出来（totals / tokens 缺字段）同样是读取失败——
        // 画 0 会把「没读到」说成「没用量」。
        throw new Error('窗口用量响应缺少 totals.tokens');
      }
      return { key: range.key, label: range.label, used };
    });
  } catch (error) {
    state.windows = [];
    state.error = error instanceof ApiError ? error.message : '读取窗口用量失败，请稍后重试。';
  } finally {
    state.loading = false;
  }
}

/**
 * All subscriptions' three-window reads. Called by `load()`, not awaited there:
 * each row draws its own loading placeholder while its reads are in flight.
 */
async function loadQuotaUsage() {
  const ranges = windowRanges(new Date());
  const states: Record<string, QuotaUsageState> = {};
  for (const s of subscriptions.value) {
    if (s.id) states[s.id] = { windows: [], error: '', loading: true };
  }
  quotaUsage.value = states;
  await Promise.all(Object.keys(states).map((id) => loadQuotaUsageRow(id, ranges)));
}

/** #1234: 重试只重发该订阅的 3 次窗口读取——其它行不动、表格不重跑。 */
function retryQuotaUsage(subscriptionId: string) {
  return loadQuotaUsageRow(subscriptionId, windowRanges(new Date()));
}

/** The row's usage state; an unknown row reads as "still loading", never as 0. */
function quotaUsageOf(row: unknown): QuotaUsageState {
  const id = (row as SubscriptionView).id ?? '';
  return quotaUsage.value[id] ?? { windows: [], error: '', loading: true };
}

function formatCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`;
  return String(n);
}

/** 与总览账本同一套单位称呼（quotaUnit 是自由字符串，未知值原样显示）。 */
const QUOTA_UNIT_LABELS: Record<string, string> = {
  POINTS: '积分',
  TOKENS: 'Token',
  REQUESTS: '请求次数',
  CURRENCY: '金额',
};

function quotaUnitLabel(unit?: string): string {
  return unit ? (QUOTA_UNIT_LABELS[unit] ?? unit) : '—';
}

async function load() {
  loading.value = true;
  try {
    const [subs, prods] = await Promise.all([api.listSubscriptions(), api.listProviderProducts()]);
    subscriptions.value = subs;
    products.value = prods;
    // #1234: 窗口用量读取与表格渲染并行；每行自画加载占位，失败只坏自己的行。
    void loadQuotaUsage();
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message;
      loadRequestId.value = error.requestId ?? '';
    }
  } finally {
    loading.value = false;
  }
}

async function createSubscription() {
  if (!form.value.providerProductId || !form.value.name.trim()) {
    formError.value = '请选择产品并填写名称。';
    return;
  }
  submitting.value = true;
  try {
    await api.createSubscription({
      providerProductId: form.value.providerProductId,
      name: form.value.name.trim(),
      billingMode: form.value.billingMode,
      planScope: form.value.planScope,
      subscriptionPrice: form.value.subscriptionPrice
        ? Number(form.value.subscriptionPrice)
        : undefined,
      currency: form.value.currency || undefined,
      quotaTotal: form.value.quotaTotal ? Number(form.value.quotaTotal) : undefined,
      quotaUnit: form.value.quotaUnit || undefined,
    });
    creating.value = false;
    form.value = {
      providerProductId: '',
      name: '',
      billingMode: 'FIXED_SUBSCRIPTION',
      planScope: 'PERSONAL',
      subscriptionPrice: '',
      currency: 'USD',
      quotaTotal: '',
      quotaUnit: '',
    };
    toast.success('订阅已创建');
    await load();
  } catch (error) {
    formError.value = error instanceof ApiError ? error.message : '创建失败，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

// #440: request-sequence guard — a slow seat list for subscription A must not
// land after the drawer re-targets subscription B (a release would then act on
// the wrong row set).
let seatsRequestSeq = 0;

async function openSeats(subscription: SubscriptionView) {
  seatSubscription.value = subscription;
  seatDrawer.value = true;
  seatError.value = '';
  await refreshSeats();
}

async function refreshSeats() {
  const target = seatSubscription.value;
  if (!target) return;
  const seq = ++seatsRequestSeq;
  // Hub View schemas mark every field optional (springdoc omits `required`);
  // subscription rows and the drawer target always carry their ids — the `!`
  // restore the pre-hub required-field contract.
  // #1160 加载次序不变量：加载中 → 失败 → 空 → 有数据。「还没有席位」只有在这次读取
  // 成功且确实为空时才允许出现（UiTable 的 :error 契约会用它替换空态）；读取失败在此
  // 记录到 seatsLoadError。与保存错误 seatError 分开：那是分配/校验失败槽。
  seatsLoadError.value = '';
  seatLoading.value = true;
  try {
    const rows = await api.listSeats(target.id!);
    if (seq !== seatsRequestSeq) {
      return; // a newer drawer target won — this response is stale
    }
    seats.value = rows;
  } catch (error) {
    if (seq !== seatsRequestSeq) {
      return;
    }
    seats.value = [];
    seatsLoadError.value = error instanceof ApiError ? error.message : '加载席位失败。';
  } finally {
    if (seq === seatsRequestSeq) {
      seatLoading.value = false;
    }
  }
}

async function addSeat() {
  // #PH35: POST /seats is not idempotent server-side (fresh id per call, and the
  // only unique index on plan_seats is partial on external_seat_ref, which this
  // form never sends), so a double click used to leave two seat rows behind.
  if (seatSubmitting.value) {
    return;
  }
  if (!seatSubscription.value || !seatAssignUser.value.trim()) {
    seatError.value = '请输入用户名（成员 Key 请到上游凭证页关联）。';
    return;
  }
  seatError.value = '';
  seatSubmitting.value = true;
  try {
    await api.createSeat(seatSubscription.value.id!, {
      displayName: seatDisplay.value.trim() || undefined,
      assignedUserId: seatAssignUser.value.trim(),
    });
    seatAssignUser.value = '';
    seatDisplay.value = '';
    toast.success('席位已分配');
    await refreshSeats();
  } catch (error) {
    seatError.value = error instanceof ApiError ? error.message : '分配失败';
  } finally {
    seatSubmitting.value = false;
  }
}

function requestRelease(seat: SeatView) {
  if (!seatSubscription.value) return;
  const subscription = seatSubscription.value;
  confirmState.value = {
    title: '释放席位',
    body: '释放后该席位不再关联用户，成员 Key 保持有效但不再消耗席位额度。',
    confirmLabel: '释放',
    tone: 'danger',
    run: async () => {
      try {
        await api.updateSeat(subscription.id!, seat.id!, {
          status: 'AVAILABLE',
          // The read path hands us the row's version; the server refuses a stale one.
          // Same non-null style as the ids above — every SeatView field is optional in
          // the generated types, though this one is NOT NULL in the schema.
          version: seat.version!,
        });
        toast.success('席位已释放');
        await refreshSeats();
      } catch (error) {
        if (error instanceof ApiError) {
          toast.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
        }
      }
    },
  };
}

async function confirmAndRun() {
  const state = confirmState.value;
  if (!state) return;
  confirmState.value = null;
  await state.run();
}

function seatLabel(seat: SeatView): string {
  return seat.username ?? seat.displayName ?? seat.assignedUserId?.slice(0, 8) ?? '—';
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-plans">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">订阅</h1>
        <p class="ui-page-desc">按量付费 / 个人 / 团队 / 企业套餐订阅与席位分配。</p>
      </div>
      <div class="ui-page-actions">
        <UiButton
          variant="primary"
          data-testid="subscription-create-open"
          @click="creating = !creating"
        >
          {{ creating ? '收起表单' : '创建订阅' }}
        </UiButton>
      </div>
    </header>

    <div v-if="loadError" class="ui-alert ui-alert--error">
      {{ loadError
      }}<span v-if="loadRequestId" class="ui-request-id"> requestId: {{ loadRequestId }}</span>
    </div>

    <section
      v-if="creating"
      class="ui-panel next-plans__create"
      data-testid="subscription-create-form"
    >
      <div class="ui-panel-head">
        <h2 class="ui-panel-title">创建订阅</h2>
      </div>
      <div class="ui-panel-body">
        <div class="next-plans__form">
          <UiSelect
            v-model="form.providerProductId"
            label="供应商产品"
            required
            placeholder="选择产品"
            :options="productOptions"
            width="100%"
            data-testid="subscription-create-product"
          />
          <UiInput
            v-model="form.name"
            label="名称"
            required
            data-testid="subscription-create-name"
          />
          <div class="next-plans__form-row">
            <UiSelect
              v-model="form.billingMode"
              label="计费模式"
              :options="billingOptions"
              width="100%"
              data-testid="subscription-create-billing"
            />
            <UiSelect
              v-model="form.planScope"
              label="Plan 形态"
              :options="planOptions"
              width="100%"
              data-testid="subscription-create-scope"
            />
          </div>
          <div class="next-plans__form-row">
            <UiInput
              v-model="form.subscriptionPrice"
              label="订阅价格"
              type="number"
              data-testid="subscription-create-price"
            />
            <UiInput
              v-model="form.currency"
              label="币种"
              maxlength="3"
              data-testid="subscription-create-currency"
            />
          </div>
          <div class="next-plans__form-row">
            <UiInput
              v-model="form.quotaTotal"
              label="配额总量"
              type="number"
              data-testid="subscription-create-quota"
            />
            <UiSelect
              v-model="form.quotaUnit"
              label="配额单位"
              :options="quotaUnitOptions"
              width="100%"
              data-testid="subscription-create-quota-unit"
            />
          </div>
          <p v-if="formError" class="ui-form-error">{{ formError }}</p>
          <div class="next-plans__actions">
            <UiButton
              variant="primary"
              :loading="submitting"
              data-testid="subscription-create-submit"
              @click="createSubscription"
            >
              创建订阅
            </UiButton>
            <UiButton variant="ghost" @click="creating = false">取消</UiButton>
          </div>
        </div>
      </div>
    </section>

    <section class="ui-panel">
      <div class="ui-panel-toolbar">
        <span class="ui-panel-sub"
          >共 {{ countWhenLoaded(loadError, subscriptions.length) }} 个订阅</span
        >
      </div>
      <UiTable
        :columns="columns"
        :data="subscriptions"
        :loading="loading"
        row-key="id"
        empty-title="还没有订阅"
        data-testid="subscriptions-table"
        :error="loadError"
        @retry="load"
      >
        <template #planScope="{ row }">{{
          planLabel((row as SubscriptionView).planScope)
        }}</template>
        <template #price="{ row }">
          <span class="ui-num">{{
            (row as SubscriptionView).subscriptionPrice != null
              ? `${(row as SubscriptionView).subscriptionPrice} ${(row as SubscriptionView).currency ?? ''}`
              : '—'
          }}</span>
        </template>
        <template #quota="{ row }">
          <div class="next-plans__usage">
            <template v-if="quotaUsageOf(row).error">
              <p class="ui-form-error" data-testid="plans-quota-row-error">
                {{ quotaUsageOf(row).error }}
              </p>
              <UiButton
                variant="ghost"
                size="sm"
                data-testid="plans-quota-retry"
                @click="retryQuotaUsage((row as SubscriptionView).id!)"
                >重试</UiButton
              >
            </template>
            <p
              v-else-if="quotaUsageOf(row).loading"
              class="next-plans__usage-empty"
              data-testid="plans-quota-loading"
            >
              加载中…
            </p>
            <template v-else>
              <div
                v-for="seg in quotaUsageOf(row).windows"
                :key="seg.key"
                class="next-plans__usage-row"
                data-testid="plans-quota-seg"
              >
                <span class="next-plans__usage-label">{{ seg.label }}</span>
                <span class="next-plans__usage-value ui-num">{{ formatCount(seg.used) }}</span>
              </div>
            </template>
            <span class="next-plans__usage-total"
              ><span class="next-plans__usage-total-label">方案总额度：</span
              ><span class="ui-num">{{
                (row as SubscriptionView).quotaTotal
                  ? `${formatCount((row as SubscriptionView).quotaTotal!)} ${quotaUnitLabel((row as SubscriptionView).quotaUnit)}`
                  : '未配置'
              }}</span></span
            >
          </div>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as SubscriptionView).status === 'ACTIVE' ? 'success' : 'danger'"
            :label="(row as SubscriptionView).status === 'ACTIVE' ? '正常' : '停用'"
          />
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="link"
            size="sm"
            data-testid="subscription-seats-open"
            @click="openSeats(row as SubscriptionView)"
          >
            席位
          </UiButton>
        </template>
      </UiTable>
    </section>

    <!-- Seats drawer -->
    <UiDrawer
      :open="seatDrawer"
      :title="`席位：${seatSubscription?.name ?? ''}`"
      width="500px"
      data-testid="seats-drawer"
      @close="seatDrawer = false"
    >
      <p class="next-plans__hint">
        团队/企业套餐按席位分配；每个成员的用量走其专属密钥（上游凭证页管理）。
      </p>
      <div class="next-plans__seat-create">
        <UiInput v-model="seatAssignUser" placeholder="用户 ID" data-testid="seat-assign-user" />
        <UiInput
          v-model="seatDisplay"
          placeholder="显示名（可选）"
          data-testid="seat-assign-display"
        />
        <UiButton
          variant="primary"
          :loading="seatSubmitting"
          data-testid="seat-create"
          @click="addSeat"
        >
          分配席位
        </UiButton>
      </div>
      <p v-if="seatError" class="ui-form-error">{{ seatError }}</p>
      <UiTable
        :columns="seatColumns"
        :data="seats"
        :loading="seatLoading"
        :error="seatsLoadError"
        row-key="id"
        empty-title="还没有席位"
        data-testid="seats-table"
        @retry="refreshSeats"
      >
        <template #user="{ row }">{{ seatLabel(row as SeatView) }}</template>
        <template #seatStatus="{ row }">
          <UiStatusBadge
            :tone="(row as SeatView).seatStatus === 'ASSIGNED' ? 'success' : 'neutral'"
            :label="(row as SeatView).seatStatus === 'ASSIGNED' ? '已分配' : '可分配'"
          />
        </template>
        <template #release="{ row }">
          <UiButton
            v-if="(row as SeatView).seatStatus === 'ASSIGNED'"
            variant="ghost"
            size="sm"
            class="next-plans__danger"
            data-testid="seat-release"
            @click="requestRelease(row as SeatView)"
          >
            释放
          </UiButton>
        </template>
      </UiTable>
    </UiDrawer>

    <UiDialog
      v-if="confirmState"
      :open="true"
      :title="confirmState.title"
      :description="confirmState.body"
      width="440px"
      @update:open="confirmState = null"
    >
      <template #footer>
        <UiButton variant="ghost" @click="confirmState = null">取消</UiButton>
        <UiButton
          :variant="confirmState.tone === 'danger' ? 'danger' : 'primary'"
          @click="confirmAndRun"
        >
          {{ confirmState.confirmLabel }}
        </UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.ui-alert {
  padding: var(--ui-space-3) var(--ui-space-4);
  margin-bottom: var(--ui-space-4);
  border-radius: var(--ui-radius-control);
  font-size: var(--ui-font-size-sm);
}

.ui-alert--error {
  background: var(--ui-danger-bg);
  color: var(--ui-danger-fg);
}

.next-plans__create {
  margin-bottom: var(--ui-space-5);
  max-width: 820px;
}

.next-plans__form {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-4);
  max-width: 720px;
}

.next-plans__form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--ui-space-5);
}

.next-plans__actions {
  display: flex;
  gap: var(--ui-space-2);
}

.next-plans__hint {
  margin: 0 0 var(--ui-space-4);
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
  line-height: var(--ui-line-height-base);
}

.next-plans__seat-create {
  display: flex;
  gap: var(--ui-space-2);
  align-items: flex-end;
  margin-bottom: var(--ui-space-4);
}

.next-plans__seat-create > :deep(.ui-field) {
  flex: 1;
}

.next-plans__danger {
  color: var(--ui-danger-fg);
}

.next-plans__usage {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.next-plans__usage-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ui-space-2);
}

.next-plans__usage-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-plans__usage-value {
  font-size: var(--ui-font-size-sm);
  color: var(--ui-foreground);
}

.next-plans__usage-empty {
  margin: 0;
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-plans__usage-total {
  margin-top: var(--ui-space-1);
  font-size: var(--ui-font-size-xs);
}

.next-plans__usage-total-label {
  color: var(--ui-foreground-secondary);
}
</style>
