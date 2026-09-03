<script setup lang="ts">
/**
 * NextPlansView — /app/plans v2 admin page (U2 platform batch).
 * Behaviour parity with legacy plans page: subscription catalogue with
 * rolling-quota bands and a seats drawer (assign / release with gate).
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
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
import type { ProviderProductView, SeatView, SubscriptionView } from '@/types/api';

const subscriptions = ref<SubscriptionView[]>([]);
const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

const columns = [
  { key: 'productName', title: '产品', minWidth: '200px' },
  { key: 'name', title: '名称', minWidth: '160px' },
  { key: 'billingMode', title: '计费模式', width: '150px' },
  { key: 'planScope', title: 'Plan 形态', width: '110px' },
  { key: 'price', title: '价格', width: '130px', align: 'right' as const },
  { key: 'quota', title: '滚动额度', minWidth: '260px' },
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
  { value: 'FIXED_SUBSCRIPTION', label: 'FIXED_SUBSCRIPTION' },
  { value: 'PAYG', label: 'PAYG' },
  { value: 'TOKEN_PACKAGE', label: 'TOKEN_PACKAGE' },
  { value: 'CREDIT_POOL', label: 'CREDIT_POOL' },
];

const planOptions: UiSelectOption[] = [
  { value: 'PERSONAL', label: '个人 Plan' },
  { value: 'TEAM', label: '团队 Plan' },
  { value: 'ENTERPRISE', label: '企业 Plan' },
  { value: 'NONE', label: '无' },
];

const quotaUnitOptions: UiSelectOption[] = [
  { value: 'POINTS', label: 'POINTS' },
  { value: 'TOKENS', label: 'TOKENS' },
  { value: 'REQUESTS', label: 'REQUESTS' },
];

const seatDrawer = ref(false);
const seatSubscription = ref<SubscriptionView | null>(null);
const seats = ref<SeatView[]>([]);
const seatLoading = ref(false);
const seatAssignUser = ref('');
const seatDisplay = ref('');
const seatError = ref('');

const confirmState = ref<{
  title: string;
  body: string;
  confirmLabel: string;
  tone: 'danger' | 'primary';
  run: () => Promise<void>;
} | null>(null);

function planLabel(scope: string): string {
  switch (scope) {
    case 'PERSONAL':
      return '个人 Plan';
    case 'TEAM':
      return '团队 Plan';
    case 'ENTERPRISE':
      return '企业 Plan';
    default:
      return scope;
  }
}

function quotaSegments(): { label: string; ratio: number }[] {
  // Demo fill ratios: official usage API pending (WAITING_FOR_CREDENTIAL).
  const base = 34;
  return [
    { label: '5 小时', ratio: base },
    { label: '本周', ratio: Math.round(base * 0.8) },
    { label: '本月', ratio: Math.round(base * 0.6) },
  ];
}

async function load() {
  loading.value = true;
  try {
    const [subs, prods] = await Promise.all([api.listSubscriptions(), api.listProviderProducts()]);
    subscriptions.value = subs;
    products.value = prods;
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

async function openSeats(subscription: SubscriptionView) {
  seatSubscription.value = subscription;
  seatDrawer.value = true;
  seatError.value = '';
  await refreshSeats();
}

async function refreshSeats() {
  if (!seatSubscription.value) return;
  seatLoading.value = true;
  try {
    seats.value = await api.listSeats(seatSubscription.value.id);
  } finally {
    seatLoading.value = false;
  }
}

async function addSeat() {
  if (!seatSubscription.value || !seatAssignUser.value.trim()) {
    seatError.value = '请输入用户名（成员 Key 请到上游凭证页关联）。';
    return;
  }
  seatError.value = '';
  try {
    await api.createSeat(seatSubscription.value.id, {
      displayName: seatDisplay.value.trim() || undefined,
      assignedUserId: seatAssignUser.value.trim(),
    });
    seatAssignUser.value = '';
    seatDisplay.value = '';
    toast.success('席位已分配');
    await refreshSeats();
  } catch (error) {
    seatError.value = error instanceof ApiError ? error.message : '分配失败';
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
        await api.updateSeat(subscription.id, seat.id, { status: 'AVAILABLE' });
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

function formatTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

onMounted(load);
</script>

<template>
  <div class="ui-page next-plans">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-page-title">订阅</h1>
        <p class="ui-page-desc">PAYG / 个人 / 团队 / 企业订阅与席位分配。</p>
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
        <span class="ui-panel-sub">共 {{ subscriptions.length }} 个订阅</span>
      </div>
      <UiTable
        :columns="columns"
        :data="subscriptions"
        :loading="loading"
        row-key="id"
        empty-title="还没有订阅"
        data-testid="subscriptions-table"
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
          <div v-if="(row as SubscriptionView).quotaTotal" class="next-plans__band">
            <div v-for="seg in quotaSegments()" :key="seg.label" class="next-plans__band-row">
              <span class="next-plans__band-label">{{ seg.label }}</span>
              <div class="next-plans__band-track">
                <div
                  class="next-plans__band-fill"
                  :class="{
                    'next-plans__band-fill--danger': seg.ratio >= 80,
                    'next-plans__band-fill--warning': seg.ratio >= 60 && seg.ratio < 80,
                  }"
                  :style="{ width: seg.ratio + '%' }"
                />
              </div>
              <span class="next-plans__band-pct ui-num">{{ seg.ratio }}%</span>
            </div>
          </div>
          <span v-else class="ui-panel-sub">未配置配额</span>
        </template>
        <template #status="{ row }">
          <UiStatusBadge
            :tone="(row as SubscriptionView).status === 'ACTIVE' ? 'success' : 'danger'"
            :label="(row as SubscriptionView).status === 'ACTIVE' ? '正常' : '停用'"
          />
        </template>
        <template #actions="{ row }">
          <UiButton
            variant="ghost"
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
        团队/企业 Plan 按席位分配；每个成员的用量走其专属 Key（上游凭证页管理）。
      </p>
      <div class="next-plans__seat-create">
        <UiInput v-model="seatAssignUser" placeholder="用户 ID" data-testid="seat-assign-user" />
        <UiInput
          v-model="seatDisplay"
          placeholder="显示名（可选）"
          data-testid="seat-assign-display"
        />
        <UiButton variant="primary" data-testid="seat-create" @click="addSeat">分配席位</UiButton>
      </div>
      <p v-if="seatError" class="ui-form-error">{{ seatError }}</p>
      <UiTable
        :columns="seatColumns"
        :data="seats"
        :loading="seatLoading"
        row-key="id"
        empty-title="还没有席位"
        data-testid="seats-table"
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

.next-plans__band {
  display: flex;
  flex-direction: column;
  gap: var(--ui-space-1);
}

.next-plans__band-row {
  display: grid;
  grid-template-columns: 56px 1fr 40px;
  align-items: center;
  gap: var(--ui-space-2);
}

.next-plans__band-label {
  font-size: var(--ui-font-size-xs);
  color: var(--ui-foreground-secondary);
}

.next-plans__band-track {
  height: 6px;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-muted);
  overflow: hidden;
}

.next-plans__band-fill {
  height: 100%;
  border-radius: var(--ui-radius-pill);
  background: var(--ui-primary);
}

.next-plans__band-fill--warning {
  background: var(--ui-warning-fg);
}

.next-plans__band-fill--danger {
  background: var(--ui-danger-fg);
}

.next-plans__band-pct {
  font-size: var(--ui-font-size-xs);
  text-align: right;
  color: var(--ui-foreground-secondary);
}
</style>
