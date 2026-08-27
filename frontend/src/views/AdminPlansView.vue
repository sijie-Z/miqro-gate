<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as api from '@/api';
import { ApiError } from '@/api/http';
import PageHeader from '@/components/PageHeader.vue';
import type { ProviderProductView, SeatView, SubscriptionView } from '@/types/api';

const subscriptions = ref<SubscriptionView[]>([]);
const products = ref<ProviderProductView[]>([]);
const loading = ref(true);
const loadError = ref('');
const loadRequestId = ref('');

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

const seatDrawer = ref(false);
const seatSubscription = ref<SubscriptionView | null>(null);
const seats = ref<SeatView[]>([]);
const seatLoading = ref(false);
const seatAssignUser = ref('');
const seatDisplay = ref('');

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
    ElMessage.warning('请输入用户名（成员 Key 请到 Credentials 页关联）。');
    return;
  }
  try {
    await api.createSeat(seatSubscription.value.id, {
      displayName: seatDisplay.value.trim() || undefined,
      assignedUserId: seatAssignUser.value.trim(),
    });
    seatAssignUser.value = '';
    seatDisplay.value = '';
    await refreshSeats();
  } catch (error) {
    if (error instanceof ApiError) {
      ElMessage.error(`${error.message}（requestId: ${error.requestId ?? '-'}）`);
    }
  }
}

async function releaseSeat(seat: SeatView) {
  if (!seatSubscription.value) return;
  try {
    await ElMessageBox.confirm(
      '释放后该席位不再关联用户，成员 Key 保持有效但不再消耗席位额度。',
      '释放席位',
      {
        confirmButtonText: '释放',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  await api.updateSeat(seatSubscription.value.id, seat.id, { status: 'AVAILABLE' });
  await refreshSeats();
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

onMounted(load);
</script>

<template>
  <div class="plans-page">
    <PageHeader title="Plans" description="PAYG / 个人 / 团队 / 企业订阅与席位分配。">
      <template #actions>
        <el-button
          type="primary"
          data-testid="subscription-create-open"
          @click="creating = !creating"
        >
          {{ creating ? '收起表单' : '创建订阅' }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="loadError" type="error" :closable="false" class="block-alert">
      {{ loadError
      }}<span v-if="loadRequestId" class="mk-mono">requestId: {{ loadRequestId }}</span>
    </el-alert>

    <section v-if="creating" class="create-panel" data-testid="subscription-create-form">
      <h3 class="panel-title">创建订阅</h3>
      <el-form label-position="top" class="create-form">
        <el-form-item label="供应商产品" required>
          <el-select v-model="form.providerProductId" data-testid="subscription-create-product">
            <el-option
              v-for="p in products"
              :key="p.id"
              :label="`${p.providerName} · ${p.displayName}`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" data-testid="subscription-create-name" />
        </el-form-item>
        <el-form-item label="计费模式">
          <el-select v-model="form.billingMode">
            <el-option label="FIXED_SUBSCRIPTION" value="FIXED_SUBSCRIPTION" />
            <el-option label="PAYG" value="PAYG" />
            <el-option label="TOKEN_PACKAGE" value="TOKEN_PACKAGE" />
            <el-option label="CREDIT_POOL" value="CREDIT_POOL" />
          </el-select>
        </el-form-item>
        <el-form-item label="Plan 形态">
          <el-select v-model="form.planScope">
            <el-option label="个人 Plan" value="PERSONAL" />
            <el-option label="团队 Plan" value="TEAM" />
            <el-option label="企业 Plan" value="ENTERPRISE" />
            <el-option label="无" value="NONE" />
          </el-select>
        </el-form-item>
        <div class="form-row">
          <el-form-item label="订阅价格">
            <el-input v-model="form.subscriptionPrice" type="number" />
          </el-form-item>
          <el-form-item label="币种">
            <el-input v-model="form.currency" maxlength="3" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="配额总量">
            <el-input v-model="form.quotaTotal" type="number" />
          </el-form-item>
          <el-form-item label="配额单位">
            <el-select v-model="form.quotaUnit">
              <el-option label="POINTS" value="POINTS" />
              <el-option label="TOKENS" value="TOKENS" />
              <el-option label="REQUESTS" value="REQUESTS" />
            </el-select>
          </el-form-item>
        </div>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="subscription-create-submit"
          @click="createSubscription"
        >
          创建订阅
        </el-button>
      </el-form>
    </section>

    <el-table v-loading="loading" :data="subscriptions" data-testid="subscriptions-table">
      <el-table-column prop="productName" label="产品" min-width="180" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="billingMode" label="计费模式" width="150" />
      <el-table-column label="Plan 形态" width="110">
        <template #default="{ row }">{{ planLabel(row.planScope) }}</template>
      </el-table-column>
      <el-table-column label="价格" width="110" align="right">
        <template #default="{ row }">
          <span class="mk-num">{{
            row.subscriptionPrice != null ? `${row.subscriptionPrice} ${row.currency ?? ''}` : '—'
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="滚动额度" min-width="240" data-testid="plans-quota-band">
        <template #default="{ row }">
          <div v-if="row.quotaTotal" class="mk-quota-band">
            <div v-for="seg in quotaSegments()" :key="seg.label" class="mk-quota-segment">
              <div class="mk-quota-segment-label">
                <span>{{ seg.label }}</span
                ><span class="mk-num">{{ seg.ratio }}%</span>
              </div>
              <div class="mk-quota-track">
                <div
                  class="mk-quota-fill"
                  :class="
                    seg.ratio >= 80
                      ? 'mk-quota-fill--danger'
                      : seg.ratio >= 60
                        ? 'mk-quota-fill--warning'
                        : ''
                  "
                  :style="{ width: seg.ratio + '%' }"
                />
              </div>
            </div>
          </div>
          <span v-else class="mk-stat-hint">未配置配额</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <span
            class="mk-status"
            :class="row.status === 'ACTIVE' ? 'mk-status--success' : 'mk-status--danger'"
          >
            {{ row.status }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            data-testid="subscription-seats-open"
            @click="openSeats(row)"
          >
            席位
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="seatDrawer" :title="`席位：${seatSubscription?.name ?? ''}`" size="480px">
      <p class="hint">
        团队/企业 Plan 按席位分配；每个成员的用量走其专属 Key（Credentials 页管理）。
      </p>
      <div class="seat-create">
        <el-input v-model="seatAssignUser" placeholder="用户 ID" data-testid="seat-assign-user" />
        <el-input v-model="seatDisplay" placeholder="显示名（可选）" />
        <el-button type="primary" data-testid="seat-create" @click="addSeat">分配席位</el-button>
      </div>
      <el-table v-loading="seatLoading" :data="seats" data-testid="seats-table">
        <el-table-column label="用户" min-width="120">
          <template #default="{ row }">
            {{ row.username ?? row.displayName ?? row.assignedUserId?.slice(0, 8) ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span
              class="mk-status"
              :class="row.seatStatus === 'ASSIGNED' ? 'mk-status--success' : 'mk-status--neutral'"
            >
              {{ row.seatStatus }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="" width="80">
          <template #default="{ row }">
            <el-button
              v-if="row.seatStatus === 'ASSIGNED'"
              link
              type="danger"
              data-testid="seat-release"
              @click="releaseSeat(row)"
            >
              释放
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<style scoped>
.block-alert {
  margin-bottom: 16px;
}

.create-panel {
  border: 1px solid var(--miqrokey-border-default);
  border-radius: var(--miqrokey-radius-panel);
  background: var(--miqrokey-bg-surface);
  padding: 16px 20px 20px;
  margin-bottom: 20px;
  max-width: var(--miqrokey-content-form-max);
}

.panel-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 600;
}

.create-form {
  max-width: 560px;
}

.form-row {
  display: flex;
  gap: 12px;
}

.form-row .el-form-item {
  flex: 1;
}

.form-error {
  margin-bottom: 12px;
  color: var(--miqrokey-danger);
}

.hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--miqrokey-text-secondary);
}

.seat-create {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
</style>
