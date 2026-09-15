<script setup lang="ts">
/**
 * NextLoginView — /login. Visual master = the preset reference package
 * `other/miqro-gate-auth-ui` (auth-ui visual pass 2: dark enterprise-gateway
 * hero + white auth panel), ported 1:1 onto the current stack (native
 * controls, no TDesign runtime) with Simplified-Chinese copy. Product
 * behaviour is preserved: login and self-service registration share this
 * panel through the secondary card action; error envelope, redirect query
 * and every data-testid unchanged.
 */
import { computed, onMounted, ref } from 'vue';
import * as api from '@/api';
import { useRoute, useRouter } from 'vue-router';
import {
  DropdownMenuContent,
  DropdownMenuItemIndicator,
  DropdownMenuPortal,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuRoot,
  DropdownMenuTrigger,
} from 'radix-vue';
import {
  ArrowRightIcon,
  InternetIcon,
  LockOnIcon,
  SecuredIcon,
  UserIcon,
} from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import { toast } from '@/ui';
import { language } from '@/i18n';
import heroArt from '@/assets/login/hero-full.png';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

type Mode = 'login' | 'register';

/**
 * The login page ships Simplified Chinese and English. The language picker is
 * a real radio group: picking a language retranslates the page immediately and
 * the choice persists across visits (shared app-wide language store). Brand
 * tokens (MiQroGate, HTTPS / JWT, provider names, requestId) stay
 * language-neutral; backend error details are shown exactly as the API
 * returns them.
 */
interface LoginCopy {
  brandProduct: string;
  heroEyebrow: string;
  heroLine1: string;
  heroLine2Pre: string;
  heroLine2Em: string;
  heroLine2Post: string;
  heroDesc: string;
  caps: Array<{ title: string; small: string }>;
  checks: string[];
  terminal: string;
  trustNoPrompt: string;
  trustRouting: string;
  trustAudit: string;
  footerVersion: string;
  welcome: string;
  createAccount: string;
  welcomeDesc: string;
  registerDesc: string;
  usernameLabel: string;
  usernameLabelRegister: string;
  usernamePh: string;
  usernamePhRegister: string;
  displayNameLabel: string;
  displayNamePh: string;
  passwordLabel: string;
  passwordPh: string;
  passwordPhRegister: string;
  confirmLabel: string;
  confirmPh: string;
  forgot: string;
  submitLogin: string;
  submitRegister: string;
  or: string;
  requestTitle: string;
  requestDesc: string;
  backTitle: string;
  backDesc: string;
  privacyTitle: string;
  privacyDesc: string;
  footerCopy: string;
  privacyPolicy: string;
  terms: string;
  errNeedBoth: string;
  errFill: string;
  errMismatch: string;
  errLogin: string;
  errRegister: string;
  toastForgot: string;
  showPw: string;
  hidePw: string;
}

const COPY: Record<'zh-Hans' | 'en', LoginCopy> = {
  'zh-Hans': {
    brandProduct: 'AI 凭证控制平台',
    heroEyebrow: '企业级 AI 基础设施',
    heroLine1: '网关静默运转。',
    heroLine2Pre: '密钥',
    heroLine2Em: '尽在掌控',
    heroLine2Post: '。',
    heroDesc:
      'MiQroGate 是企业级 AI 凭证虚拟化与访问控制平面，为你的大模型 API 提供安全、可观测、可审计的统一网关。',
    caps: [
      { title: '虚拟密钥', small: '统一凭证管理，灵活分配与权限控制' },
      { title: '权限控制', small: '细粒度授权，最小化访问风险' },
      { title: '用量与审计', small: '实时用量统计，完整审计日志' },
      { title: '私有化部署', small: '本地化部署，数据不出环境' },
    ],
    checks: ['认证', '限流', '日志', '审计'],
    terminal: '网关 · 在线',
    trustNoPrompt: '不留存 Prompt',
    trustRouting: '确定性路由',
    trustAudit: '用量可审计',
    footerVersion: 'MiQroGate · AI 凭证控制平台',
    welcome: '欢迎回来',
    createAccount: '创建账号',
    welcomeDesc: '登录你的账号进入 MiQroGate 控制台，管理虚拟密钥、权限与用量数据。',
    registerDesc: '注册后立即可用，无需审核；若部署关闭自助注册请联系管理员。',
    usernameLabel: '账号/邮箱',
    usernameLabelRegister: '账号',
    usernamePh: '输入账号或邮箱',
    usernamePhRegister: '例如 alice',
    displayNameLabel: '昵称（可选）',
    displayNamePh: '团队里展示的名字',
    passwordLabel: '密码',
    passwordPh: '输入密码',
    passwordPhRegister: '至少 8 位，含大小写字母和数字',
    confirmLabel: '确认密码',
    confirmPh: '再次输入密码',
    forgot: '忘记密码？',
    submitLogin: '登 录',
    submitRegister: '注册并进入',
    or: '或',
    requestTitle: '申请账号',
    requestDesc: '需要访问 MiQroGate？自助注册开启时可创建账号，或联系你的管理员。',
    backTitle: '返回登录',
    backDesc: '已有门户账号？回到登录页。',
    privacyTitle: '你的数据受到保护',
    privacyDesc: 'MiQroGate 运行在你的私有环境，绝不存储你的提示词与敏感数据。',
    footerCopy: '© MiQroGate · 私有 AI 基础设施',
    privacyPolicy: '隐私政策',
    terms: '服务条款',
    errNeedBoth: '请输入账号和密码。',
    errFill: '请填写账号和密码。',
    errMismatch: '两次输入的密码不一致。',
    errLogin: '登录失败，请稍后重试。',
    errRegister: '注册失败，请稍后重试。',
    toastForgot: '请联系部署管理员重置密码。',
    showPw: '显示密码',
    hidePw: '隐藏密码',
  },
  en: {
    brandProduct: 'AI Credential Control Plane',
    heroEyebrow: 'ENTERPRISE AI INFRASTRUCTURE',
    heroLine1: 'The gateway stays quiet.',
    heroLine2Pre: 'The control stays ',
    heroLine2Em: 'yours',
    heroLine2Post: '.',
    heroDesc:
      'MiQroGate is an enterprise AI credential virtualization and access-control plane — a secure, observable and auditable gateway for your LLM APIs.',
    caps: [
      { title: 'Virtual Keys', small: 'Unified credential management with scoped distribution' },
      { title: 'Permission Control', small: 'Fine-grained authorization, minimal exposure' },
      { title: 'Usage & Audit', small: 'Real-time usage stats, complete audit trail' },
      { title: 'Private Deployment', small: 'Runs in your environment, data never leaves' },
    ],
    checks: ['Auth', 'Rate Limit', 'Logging', 'Auditing'],
    terminal: 'GATEWAY / ONLINE',
    trustNoPrompt: 'No Prompt Storage',
    trustRouting: 'Deterministic Routing',
    trustAudit: 'Auditable Usage',
    footerVersion: 'MiQroGate · Control Plane for AI Credentials',
    welcome: 'Welcome back',
    createAccount: 'Create account',
    welcomeDesc:
      'Sign in to your account to access the MiQroGate control plane. Manage your virtual keys, permissions and usage data.',
    registerDesc:
      'Ready to use right after sign-up, no approval needed; contact your administrator if self-registration is disabled.',
    usernameLabel: 'Email / Username',
    usernameLabelRegister: 'Username',
    usernamePh: 'Enter your email or username',
    usernamePhRegister: 'e.g. alice',
    displayNameLabel: 'Nickname (optional)',
    displayNamePh: 'Shown to your team',
    passwordLabel: 'Password',
    passwordPh: 'Enter your password',
    passwordPhRegister: 'At least 8 chars with upper/lower case and a digit',
    confirmLabel: 'Confirm password',
    confirmPh: 'Repeat your password',
    forgot: 'Forgot password?',
    submitLogin: 'Sign in',
    submitRegister: 'Create account',
    or: 'OR',
    requestTitle: 'Request an account',
    requestDesc:
      'Need access to MiQroGate? Create an account when self-registration is enabled, or contact your administrator.',
    backTitle: 'Back to sign in',
    backDesc: 'Already have a portal account? Return to the sign-in page.',
    privacyTitle: 'Your data is protected',
    privacyDesc:
      'MiQroGate runs in your private environment. We never store your prompts or sensitive data.',
    footerCopy: '© MiQroGate · Private AI Infrastructure',
    privacyPolicy: 'Privacy Policy',
    terms: 'Terms of Service',
    errNeedBoth: 'Enter your username and password.',
    errFill: 'Fill in username and password.',
    errMismatch: 'The two passwords do not match.',
    errLogin: 'Sign-in failed, please try again.',
    errRegister: 'Registration failed, please try again.',
    toastForgot: 'Contact your deployment administrator to reset your password.',
    showPw: 'Show password',
    hidePw: 'Hide password',
  },
};

const languages = [
  { code: 'zh-Hans', label: '简体中文' },
  { code: 'en', label: 'English' },
] as const;

const t = computed(() => COPY[language.value]);

const mode = ref<Mode>('login');
const username = ref('');
const displayName = ref('');
const password = ref('');
const confirmPassword = ref('');
const showPassword = ref(false);
const loading = ref(false);
const oauthProviders = ref<Array<{ code: string; name: string }>>([]);
const errorMessage = ref('');
const errorRequestId = ref('');

function switchMode(next: Mode) {
  mode.value = next;
  errorMessage.value = '';
  errorRequestId.value = '';
  password.value = '';
  confirmPassword.value = '';
}

function onForgot() {
  toast.info(t.value.toastForgot);
}

async function submit() {
  if (loading.value) return;
  errorMessage.value = '';
  errorRequestId.value = '';
  if (mode.value === 'login') {
    if (!username.value.trim() || !password.value) {
      errorMessage.value = t.value.errNeedBoth;
      return;
    }
    loading.value = true;
    try {
      await auth.login(username.value.trim(), password.value);
      await afterAuthenticated();
    } catch (error) {
      renderError(error, t.value.errLogin);
    } finally {
      loading.value = false;
    }
    return;
  }

  // register (self-service)
  if (!username.value || !password.value || !confirmPassword.value) {
    errorMessage.value = t.value.errFill;
    return;
  }
  if (password.value !== confirmPassword.value) {
    errorMessage.value = t.value.errMismatch;
    return;
  }
  loading.value = true;
  try {
    await auth.register(
      username.value.trim(),
      displayName.value.trim() || undefined,
      password.value,
    );
    await afterAuthenticated();
  } catch (error) {
    renderError(error, t.value.errRegister);
  } finally {
    loading.value = false;
  }
}

async function afterAuthenticated() {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : undefined;
  await router.push(redirect ?? '/app-new/keys');
}

function renderError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    errorMessage.value = error.message;
    errorRequestId.value = error.requestId ?? '';
  } else {
    errorMessage.value = fallback;
  }
}

function startOauth() {
  window.location.assign('/api/v1/auth/oauth/start');
}

onMounted(async () => {
  try {
    oauthProviders.value = await api.publicOauthProviders();
  } catch {
    // provider discovery is best-effort on the login page
    oauthProviders.value = [];
  }
});
</script>

<template>
  <main class="gate-auth" data-testid="login-panel" data-i18n-ignore>
    <!-- Dark hero: gateway portal scene -->
    <section class="gate-hero">
      <!-- The hero is the reference artwork alone (943x1024) with the text
           areas blanked out — no live copy is layered on the image. -->
      <img class="hero-art" :src="heroArt" alt="" aria-hidden="true" draggable="false" />

    </section>

    <!-- White auth panel -->
    <section class="auth-panel">
      <div class="auth-panel-top">
        <div class="mobile-brand">
          <span class="brand-symbol" aria-hidden="true">
            <span class="brand-wing brand-wing-left" />
            <span class="brand-wing brand-wing-right" />
            <span class="brand-core" />
          </span>
          <span>MiQroGate</span>
        </div>
        <DropdownMenuRoot>
          <DropdownMenuTrigger class="panel-language" data-testid="login-language">
            <InternetIcon size="14px" />
            <span>{{ languages.find((l) => l.code === language)?.label }}</span>
            <svg
              class="panel-language__chevron"
              width="10"
              height="10"
              viewBox="0 0 16 16"
              fill="none"
              aria-hidden="true"
            >
              <path
                d="M4 6.5 8 10.5 12 6.5"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </DropdownMenuTrigger>
          <DropdownMenuPortal>
            <DropdownMenuContent class="ui-menu login-language__menu" :side-offset="6" align="end">
              <DropdownMenuRadioGroup v-model="language">
                <DropdownMenuRadioItem
                  v-for="item in languages"
                  :key="item.code"
                  :value="item.code"
                  class="login-language__item"
                  :data-testid="`login-language-${item.code}`"
                >
                  <span>{{ item.label }}</span>
                  <DropdownMenuItemIndicator class="login-language__check">
                    <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                      <path
                        d="M3.5 8.5 6.5 11.5 12.5 4.5"
                        stroke="currentColor"
                        stroke-width="1.8"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                  </DropdownMenuItemIndicator>
                </DropdownMenuRadioItem>
              </DropdownMenuRadioGroup>
            </DropdownMenuContent>
          </DropdownMenuPortal>
        </DropdownMenuRoot>
      </div>

      <div class="auth-content">
        <div class="auth-brand-inline">
          <span class="brand-symbol" aria-hidden="true">
            <span class="brand-wing brand-wing-left" />
            <span class="brand-wing brand-wing-right" />
            <span class="brand-core" />
          </span>
          <span>MiQroGate</span>
        </div>

        <div class="auth-heading">
          <h2 v-if="mode === 'login'">{{ t.welcome }} <span>👋</span></h2>
          <h2 v-else>{{ t.createAccount }}</h2>
          <p v-if="mode === 'login'">{{ t.welcomeDesc }}</p>
          <p v-else>{{ t.registerDesc }}</p>
        </div>

        <div v-if="errorMessage" class="login-error" role="alert" data-testid="login-error">
          <svg
            class="login-error__icon"
            width="15"
            height="15"
            viewBox="0 0 16 16"
            fill="none"
            aria-hidden="true"
          >
            <circle cx="8" cy="8" r="6.4" stroke="currentColor" stroke-width="1.4" />
            <path
              d="M8 5v3.4M8 10.6v.2"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linecap="round"
            />
          </svg>
          <span class="login-error__body">
            {{ errorMessage
            }}<span v-if="errorRequestId" class="error-request-id">
              requestId: {{ errorRequestId }}</span
            >
          </span>
        </div>

        <form class="auth-form" novalidate @submit.prevent="submit">
          <div class="auth-field">
            <span class="auth-label">{{
              mode === 'login' ? t.usernameLabel : t.usernameLabelRegister
            }}</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><UserIcon size="18px" /></span>
              <input
                v-model="username"
                class="auth-input__inner"
                type="text"
                :placeholder="mode === 'login' ? t.usernamePh : t.usernamePhRegister"
                autocomplete="username"
                data-testid="login-username"
              />
            </div>
          </div>

          <div v-if="mode === 'register'" class="auth-field">
            <span class="auth-label">{{ t.displayNameLabel }}</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><UserIcon size="18px" /></span>
              <input
                v-model="displayName"
                class="auth-input__inner"
                type="text"
                :placeholder="t.displayNamePh"
                autocomplete="name"
                data-testid="register-display-name"
              />
            </div>
          </div>

          <div class="auth-field">
            <span class="auth-label">{{ t.passwordLabel }}</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><LockOnIcon size="18px" /></span>
              <input
                v-model="password"
                class="auth-input__inner auth-input__inner--eye"
                :type="showPassword ? 'text' : 'password'"
                :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
                :placeholder="mode === 'login' ? t.passwordPh : t.passwordPhRegister"
                data-testid="login-password"
                @keydown.enter="submit"
              />
              <button
                type="button"
                class="input-eye"
                :aria-label="showPassword ? t.hidePw : t.showPw"
                :aria-pressed="showPassword"
                data-testid="password-toggle"
                @click="showPassword = !showPassword"
              >
                <svg
                  v-if="showPassword"
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <path
                    d="M9.8 12a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 0 0-4.4 0Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <path
                    d="m4.5 4 15 16"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linecap="round"
                  />
                </svg>
                <svg
                  v-else
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M4 12s3.5-5.5 8-5.5S20 12 20 12s-3.5 5.5-8 5.5S4 12 4 12Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <path
                    d="M9.8 12a2.2 2.2 0 1 0 4.4 0 2.2 2.2 0 0 0-4.4 0Z"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                </svg>
              </button>
            </div>
            <div v-if="mode === 'login'" class="password-help">
              <button type="button" class="text-link" @click="onForgot">{{ t.forgot }}</button>
            </div>
          </div>

          <div v-if="mode === 'register'" class="auth-field">
            <span class="auth-label">{{ t.confirmLabel }}</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><LockOnIcon size="18px" /></span>
              <input
                v-model="confirmPassword"
                class="auth-input__inner"
                :type="showPassword ? 'text' : 'password'"
                :placeholder="t.confirmPh"
                autocomplete="new-password"
                data-testid="register-confirm"
              />
            </div>
          </div>

          <button
            class="auth-submit"
            type="submit"
            :disabled="loading"
            :aria-busy="loading || undefined"
            data-testid="login-submit"
          >
            <span v-if="loading" class="auth-submit__spinner" aria-hidden="true" />
            <span>{{ mode === 'login' ? t.submitLogin : t.submitRegister }}</span>
            <ArrowRightIcon size="18px" />
          </button>
        </form>

        <div class="or-divider">
          <span /> <em>{{ t.or }}</em> <span />
        </div>

        <button
          v-if="oauthProviders.length"
          type="button"
          class="auth-oauth"
          data-testid="oauth-login"
          @click="startOauth()"
        >
          {{ oauthProviders[0]?.name }}
        </button>

        <button
          type="button"
          class="request-access"
          :data-testid="mode === 'login' ? 'tab-register' : 'tab-login'"
          @click="switchMode(mode === 'login' ? 'register' : 'login')"
        >
          <span class="request-access-icon"><UserIcon size="20px" /></span>
          <span class="request-access-copy">
            <template v-if="mode === 'login'">
              <strong>{{ t.requestTitle }}</strong>
              <small>{{ t.requestDesc }}</small>
            </template>
            <template v-else>
              <strong>{{ t.backTitle }}</strong>
              <small>{{ t.backDesc }}</small>
            </template>
          </span>
          <ArrowRightIcon size="18px" />
        </button>

        <div class="privacy-card">
          <span class="privacy-icon"><SecuredIcon size="19px" /></span>
          <span>
            <strong>{{ t.privacyTitle }}</strong>
            <small>{{ t.privacyDesc }}</small>
          </span>
        </div>
      </div>

      <footer class="auth-footer">
        <span>{{ t.footerCopy }}</span>
        <span class="auth-footer-links"
          ><span>{{ t.privacyPolicy }}</span
          ><i /> <span>{{ t.terms }}</span></span
        >
      </footer>
    </section>
  </main>
</template>

<style scoped>
:global(html, body, #app) {
  min-height: 100%;
  margin: 0;
}
:global(body) {
  background: #ffffff;
  font-family:
    Inter,
    ui-sans-serif,
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    sans-serif;
}

.gate-auth {
  --navy: #071224;
  --navy-2: #0d1a32;
  --blue: #6674ff;
  --blue-2: #9f8dff;
  --sky: #3ba7ff;
  --ink: #0f1730;
  --muted: #72809a;
  --line: #e6eaf2;
  min-height: 100vh;
  display: grid;
  /* 61.4 / 38.6 — the reference image's hero:panel split (943 / 1536 px). */
  grid-template-columns: minmax(580px, 1.23fr) minmax(480px, 0.77fr);
  overflow: hidden;
  background: #fff;
  color: var(--ink);
}

.gate-hero {
  position: relative;
  min-width: 0;
  min-height: 100vh;
  padding: 34px clamp(48px, 6vw, 96px) 30px;
  display: flex;
  flex-direction: column;
  /* Sampled from the reference artwork so the composite block's edges are
     invisible: #0a111f top -> #040a14 mid -> #08101d bottom, plus the faint
     right-side aura the reference carries on the copy side. */
  background:
    radial-gradient(circle at 82% 58%, rgba(84, 94, 255, 0.09), transparent 46%),
    linear-gradient(180deg, #0a111f 0%, #040a14 44%, #08101d 88%, #08101d 100%);
  color: #f5f7ff;
  isolation: isolate;
}

.gate-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.16;
  background-image:
    linear-gradient(rgba(157, 175, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(157, 175, 255, 0.06) 1px, transparent 1px);
  background-size: 52px 52px;
  mask-image: linear-gradient(to right, #000, transparent 95%);
  z-index: -2;
}

.gate-hero::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 42%;
  pointer-events: none;
  background: linear-gradient(to top, rgba(6, 13, 26, 0.82), transparent);
  z-index: -1;
}


.brand-symbol {
  position: relative;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
}
.brand-wing {
  position: absolute;
  top: 8px;
  width: 19px;
  height: 14px;
  border: 3px solid #7380ff;
  border-radius: 4px 10px 4px 10px;
  transform: skewY(-13deg);
}
.brand-wing-left {
  left: 2px;
  transform-origin: right center;
}
.brand-wing-right {
  right: 2px;
  transform: scaleX(-1) skewY(-13deg);
  opacity: 0.8;
}
.brand-core {
  position: absolute;
  width: 8px;
  height: 8px;
  left: 13px;
  top: 13px;
  border-radius: 2px;
  background: linear-gradient(135deg, #a38eff, #5c7fff);
  box-shadow: 0 0 17px rgba(117, 123, 255, 0.75);
}


/* Single-column stack in its own left-hand column (reference measured
   geometry: x ≈ 8–33% of the hero, starting at ~39% viewport height). */

.hero-art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 62% 50%;
  pointer-events: none;
  user-select: none;
}



/* ---------------- white auth panel ---------------- */
.auth-panel {
  position: relative;
  min-width: 0;
  min-height: 100vh;
  background: #fff;
  border-left: 1px solid #e8ebf2;
  display: flex;
  flex-direction: column;
}
.auth-panel-top {
  min-height: 56px;
  padding: 22px 26px 0;
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
}
.mobile-brand {
  display: none;
}
.panel-language {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: none;
  color: #7f8ba4;
  font-family: inherit;
  font-size: 10px;
  cursor: pointer;
  transition: color 0.18s ease;
}
.panel-language:hover {
  color: #41506e;
}
.panel-language__chevron {
  margin-top: 1px;
}
.login-language__menu {
  min-width: 150px;
}
.login-language__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  color: #16213a;
  cursor: pointer;
  outline: none;
}
.login-language__item[data-highlighted] {
  background: #f2f4f8;
}
.login-language__check {
  margin-left: auto;
  display: grid;
  place-items: center;
  color: #6a72ff;
}
.auth-content {
  width: min(480px, calc(100% - 104px));
  margin: auto;
  padding: 16px 0 32px;
}
.auth-brand-inline {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 44px;
  color: #17213a;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: -0.03em;
}
.auth-brand-inline .brand-symbol {
  width: 30px;
  height: 30px;
}
.auth-brand-inline .brand-wing {
  top: 7px;
  width: 17px;
  height: 12px;
  border-width: 2.5px;
}
.auth-brand-inline .brand-core {
  left: 11px;
  top: 11px;
  width: 7px;
  height: 7px;
}
.auth-heading h2 {
  margin: 0;
  color: #0c1730;
  font-size: 43px;
  line-height: 1.05;
  letter-spacing: -0.055em;
  font-weight: 770;
}
.auth-heading h2 span {
  font-size: 26px;
  vertical-align: top;
}
.auth-heading p {
  max-width: 470px;
  margin: 14px 0 50px;
  color: #70809d;
  font-size: 12px;
  line-height: 1.8;
}

.auth-field {
  margin-bottom: 24px;
}
.auth-label {
  display: block;
  margin-bottom: 8px;
  color: #17213a;
  font-size: 10px;
  font-weight: 700;
}
.auth-input {
  position: relative;
}
.auth-input__prefix {
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%);
  display: grid;
  place-items: center;
  color: #8592a9;
  pointer-events: none;
}
.auth-input__prefix :deep(svg) {
  display: block;
}
.auth-input__inner {
  width: 100%;
  height: 50px;
  padding: 0 14px 0 42px;
  border: 1px solid #d8dfeb;
  border-radius: 8px;
  background: #fff;
  color: #152039;
  font-family: inherit;
  font-size: 12px;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}
.auth-input__inner--eye {
  padding-right: 44px;
}
.auth-input__inner:hover:not(:focus) {
  border-color: #bbc6d9;
}
.auth-input__inner:focus {
  outline: none;
  border-color: #7f8aff;
  box-shadow: 0 0 0 3px rgba(107, 118, 255, 0.1);
}
.auth-input__inner::placeholder {
  color: #9aa7bb;
}
.input-eye {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  display: inline-grid;
  place-items: center;
  padding: 3px;
  border: 0;
  background: none;
  color: #7f8ca2;
  cursor: pointer;
}
.input-eye:hover {
  color: #4d5a72;
}
.password-help {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
.text-link {
  padding: 0;
  border: 0;
  background: none;
  color: #6a72ff;
  font-size: 10px;
  cursor: pointer;
}
.text-link:hover {
  color: #4f57e8;
}

.auth-submit {
  width: 100%;
  height: 47px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  margin-top: 4px;
  border: 0;
  border-radius: 8px;
  background: linear-gradient(90deg, #7055ff 0%, #328df1 100%);
  color: #fff;
  font-family: inherit;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
  box-shadow: 0 11px 24px rgba(81, 91, 245, 0.19);
  transition:
    filter 0.18s ease,
    opacity 0.18s ease;
}
.auth-submit:hover:not(:disabled) {
  filter: brightness(1.03);
}
.auth-submit:disabled {
  opacity: 0.72;
  cursor: default;
}
.auth-submit__spinner {
  width: 13px;
  height: 13px;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  border-radius: 50%;
  animation: auth-spin 0.7s linear infinite;
}
@keyframes auth-spin {
  to {
    transform: rotate(360deg);
  }
}

.login-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 18px;
  padding: 9px 12px;
  border-radius: 6px;
  background: #fdecee;
  color: #b5322b;
  font-size: 12px;
  line-height: 1.6;
}
.login-error__icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: #d54941;
}
.login-error__body {
  min-width: 0;
}
.error-request-id {
  display: block;
  margin-top: 2px;
  color: #8b3340;
  font:
    9px ui-monospace,
    SFMono-Regular,
    Menlo,
    monospace;
}

.or-divider {
  display: flex;
  align-items: center;
  gap: 13px;
  margin: 24px 0;
}
.or-divider span {
  flex: 1;
  height: 1px;
  background: #e5e9f0;
}
.or-divider em {
  color: #a0aabe;
  font:
    8px ui-monospace,
    SFMono-Regular,
    Menlo,
    monospace;
  font-style: normal;
}

.auth-oauth {
  width: 100%;
  height: 47px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-bottom: 14px;
  border: 1px solid #d8dfeb;
  border-radius: 8px;
  background: #fff;
  color: #16213a;
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    background 0.18s ease;
}
.auth-oauth:hover {
  border-color: #bbc6d9;
  background: #f9fbff;
}

.request-access {
  width: 100%;
  min-height: 78px;
  display: grid;
  grid-template-columns: 50px 1fr 18px;
  align-items: center;
  gap: 13px;
  padding: 12px 13px;
  text-align: left;
  border: 1px solid #e4e9f2;
  border-radius: 10px;
  background: #f7f9fd;
  color: #16213a;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    transform 0.18s ease,
    background 0.18s ease;
}
.request-access:hover {
  transform: translateY(-1px);
  border-color: #cbd5e6;
  background: #f9fbff;
}
.request-access-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  color: #5f70ff;
  background: linear-gradient(145deg, #edf0ff, #eaf5ff);
  border: 1px solid #dbe1ff;
}
.request-access-copy strong {
  display: block;
  margin-bottom: 4px;
  font-size: 11px;
  font-weight: 750;
}
.request-access-copy small {
  display: block;
  color: #77849a;
  font-size: 9px;
  line-height: 1.5;
}
.privacy-card {
  display: grid;
  grid-template-columns: 38px 1fr;
  gap: 11px;
  margin-top: 14px;
  padding: 14px 15px;
  border: 1px solid #ebeff5;
  border-radius: 10px;
  background: #fbfcfe;
}
.privacy-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  color: #4d61cb;
  background: #eef1ff;
}
.privacy-card strong {
  display: block;
  color: #26324a;
  font-size: 10px;
}
.privacy-card small {
  display: block;
  max-width: 380px;
  margin-top: 4px;
  color: #8a95a9;
  font-size: 9px;
  line-height: 1.5;
}

.auth-footer {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 28px 24px;
  border-top: 1px solid #eef1f5;
  color: #a0aabd;
  font-size: 8px;
}
.auth-footer-links {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.auth-footer i {
  width: 2px;
  height: 2px;
  border-radius: 50%;
  background: #b7c0cf;
}

@media (max-width: 1180px) {
  .gate-auth {
    grid-template-columns: minmax(540px, 1fr) minmax(460px, 0.82fr);
  }
  .gate-hero {
    padding-left: 36px;
    padding-right: 22px;
  }
          .auth-content {
    width: min(430px, calc(100% - 88px));
  }
}

@media (max-width: 920px) {
  .gate-auth {
    display: block;
  }
  .gate-hero {
    min-height: 640px;
    height: 640px;
  }
  .hero-art {
    object-position: 70% 50%;
  }
    .auth-panel {
    min-height: 680px;
    border-left: 0;
  }
  .auth-content {
    margin: 0 auto;
    padding-top: 34px;
    padding-bottom: 55px;
  }
  .auth-brand-inline {
    display: none;
  }
  .mobile-brand {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    color: #17213a;
    font-size: 14px;
    font-weight: 800;
  }
  .mobile-brand .brand-symbol {
    width: 30px;
    height: 30px;
  }
  .mobile-brand .brand-wing {
    top: 7px;
    width: 17px;
    height: 12px;
    border-width: 2.5px;
  }
  .mobile-brand .brand-core {
    left: 11px;
    top: 11px;
    width: 7px;
    height: 7px;
  }
  .auth-panel-top {
    justify-content: space-between;
    align-items: center;
    padding-top: 18px;
  }
}

@media (max-width: 600px) {
  .gate-hero {
    min-height: 620px;
    height: auto;
    padding: 24px 20px;
  }
      .auth-panel-top {
    padding-left: 18px;
    padding-right: 18px;
  }
  .auth-content {
    width: calc(100% - 36px);
    padding-top: 28px;
  }
  .auth-heading h2 {
    font-size: 34px;
  }
  .auth-heading p {
    margin-bottom: 30px;
  }
  .auth-footer {
    padding-left: 18px;
    padding-right: 18px;
    flex-direction: column;
  }
}
</style>
