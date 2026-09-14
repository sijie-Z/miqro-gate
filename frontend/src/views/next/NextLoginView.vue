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
import { onMounted, ref } from 'vue';
import * as api from '@/api';
import { useRoute, useRouter } from 'vue-router';
import {
  ArrowRightIcon,
  ChartBarIcon,
  CheckCircleIcon,
  InternetIcon,
  LockOnIcon,
  SecuredIcon,
  ServerIcon,
  UserIcon,
} from 'tdesign-icons-vue-next';
import { ApiError } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import { toast } from '@/ui';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

type Mode = 'login' | 'register';

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
  toast.info('请联系部署管理员重置密码。');
}

async function submit() {
  if (loading.value) return;
  errorMessage.value = '';
  errorRequestId.value = '';
  if (mode.value === 'login') {
    if (!username.value.trim() || !password.value) {
      errorMessage.value = '请输入账号和密码。';
      return;
    }
    loading.value = true;
    try {
      await auth.login(username.value.trim(), password.value);
      await afterAuthenticated();
    } catch (error) {
      renderError(error, '登录失败，请稍后重试。');
    } finally {
      loading.value = false;
    }
    return;
  }

  // register (self-service)
  if (!username.value || !password.value || !confirmPassword.value) {
    errorMessage.value = '请填写账号和密码。';
    return;
  }
  if (password.value !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致。';
    return;
  }
  loading.value = true;
  try {
    await auth.register(username.value.trim(), displayName.value.trim() || undefined, password.value);
    await afterAuthenticated();
  } catch (error) {
    renderError(error, '注册失败，请稍后重试。');
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
  <main class="gate-auth" data-testid="login-panel">
    <!-- Dark hero: gateway portal scene -->
    <section class="gate-hero">
      <header class="hero-header">
        <button class="brand" type="button" aria-label="MiQroGate" @click="router.push('/login')">
          <span class="brand-symbol" aria-hidden="true">
            <span class="brand-wing brand-wing-left" />
            <span class="brand-wing brand-wing-right" />
            <span class="brand-core" />
          </span>
          <span class="brand-name">MiQroGate</span>
          <span class="brand-divider" />
          <span class="brand-product">AI 凭证控制平台</span>
        </button>

        <div class="hero-locale">
          <InternetIcon size="15px" />
          <span>简体中文</span>
          <span class="locale-chevron">⌄</span>
        </div>
      </header>

      <div class="hero-content">
        <div class="hero-copy">
          <p class="hero-eyebrow">企业级 AI 基础设施</p>
          <h1>
            网关悄然运行。<br />
            <span>密钥由你<em>掌控</em>。</span>
          </h1>
          <p class="hero-description">
            MiQroGate 是企业级 AI 凭证虚拟化与访问控制平面，为你的大模型 API
            提供安全、可观测、可审计的统一网关。
          </p>

          <div class="hero-capabilities">
            <article class="capability">
              <span class="capability-icon"><LockOnIcon size="18px" /></span>
              <span>
                <strong>虚拟密钥</strong>
                <small>统一凭证管理，灵活分配与权限控制</small>
              </span>
            </article>
            <article class="capability">
              <span class="capability-icon"><SecuredIcon size="18px" /></span>
              <span>
                <strong>权限控制</strong>
                <small>细粒度授权，最小化访问风险</small>
              </span>
            </article>
            <article class="capability">
              <span class="capability-icon"><ChartBarIcon size="18px" /></span>
              <span>
                <strong>用量与审计</strong>
                <small>实时用量统计，完整审计日志</small>
              </span>
            </article>
            <article class="capability">
              <span class="capability-icon"><ServerIcon size="18px" /></span>
              <span>
                <strong>私有化部署</strong>
                <small>本地化部署，数据不出环境</small>
              </span>
            </article>
          </div>
        </div>

        <div class="gate-scene" aria-hidden="true">
          <div class="scene-aura" />
          <div class="scene-floor" />
          <div class="scene-grid" />

          <svg class="scene-lines" viewBox="0 0 760 540" preserveAspectRatio="none">
            <defs>
              <linearGradient id="flow" x1="0" x2="1">
                <stop offset="0" stop-color="#5d6bff" stop-opacity="0" />
                <stop offset="0.48" stop-color="#8590ff" stop-opacity="0.85" />
                <stop offset="1" stop-color="#b2a8ff" stop-opacity="0" />
              </linearGradient>
              <filter id="blur">
                <feGaussianBlur stdDeviation="5" />
              </filter>
            </defs>
            <ellipse
              cx="372"
              cy="258"
              rx="180"
              ry="132"
              fill="none"
              stroke="#7c87ff"
              stroke-opacity=".24"
              stroke-dasharray="3 8"
            />
            <ellipse
              cx="372"
              cy="258"
              rx="250"
              ry="183"
              fill="none"
              stroke="#6772e9"
              stroke-opacity=".12"
              stroke-dasharray="2 12"
            />
            <path d="M138 177 C254 177 296 214 346 238" stroke="url(#flow)" stroke-width="2" fill="none" />
            <path d="M136 270 C257 270 288 261 343 252" stroke="url(#flow)" stroke-width="2" fill="none" />
            <path d="M160 358 C262 348 294 293 344 269" stroke="url(#flow)" stroke-width="2" fill="none" />
            <path d="M414 244 C489 217 544 201 632 180" stroke="url(#flow)" stroke-width="2" fill="none" />
            <path d="M414 258 C498 258 552 258 642 258" stroke="url(#flow)" stroke-width="2" fill="none" />
            <path d="M414 272 C491 300 549 323 640 340" stroke="url(#flow)" stroke-width="2" fill="none" />
            <path
              d="M145 176 C250 176 295 212 346 238 M138 270 C257 270 289 262 343 252 M163 357 C261 348 294 294 344 269"
              stroke="#8790ff"
              stroke-opacity=".32"
              stroke-width="9"
              filter="url(#blur)"
              fill="none"
            />
          </svg>

          <div class="provider-card provider-openai">
            <span class="provider-logo">✳</span><span>OpenAI</span><i />
          </div>
          <div class="provider-card provider-anthropic">
            <span class="provider-logo">AI</span><span>Anthropic</span><i />
          </div>
          <div class="provider-card provider-deepseek">
            <span class="provider-logo">◈</span><span>DeepSeek</span><i />
          </div>
          <div class="provider-card provider-custom">
            <span class="provider-logo">⌁</span><span>自定义端点</span><i />
          </div>

          <div class="gate-arch">
            <div class="gate-column gate-column-left" />
            <div class="gate-column gate-column-right" />
            <div class="gate-top" />
            <div class="gate-inner-glow" />
            <div class="gate-light-edge gate-light-left" />
            <div class="gate-light-edge gate-light-right" />
            <div class="gate-floor-reflection" />
          </div>

          <div class="gate-status-card">
            <div class="gate-status-brand">
              <span class="mini-symbol"><i /></span>
              <strong>MiQroGate</strong>
            </div>
            <div class="status-check"><CheckCircleIcon size="12px" /> 认证</div>
            <div class="status-check"><CheckCircleIcon size="12px" /> 限流</div>
            <div class="status-check"><CheckCircleIcon size="12px" /> 日志</div>
            <div class="status-check"><CheckCircleIcon size="12px" /> 审计</div>
          </div>

          <div class="scene-terminal">
            <span class="terminal-dot" />
            <span>网关 · 在线</span>
            <b>99.99%</b>
          </div>
        </div>
      </div>

      <footer class="hero-footer">
        <div class="footer-trust">
          <span><LockOnIcon size="13px" /> HTTPS / JWT</span>
          <span><span class="footer-slash" />不留存 Prompt</span>
          <span><span class="footer-slash" />确定性路由</span>
          <span><SecuredIcon size="13px" />用量可审计</span>
        </div>
        <span class="hero-footer-version">MiQroGate · AI 凭证控制平台</span>
      </footer>
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
        <span class="panel-language">
          <InternetIcon size="14px" />
          简体中文
          <span>⌄</span>
        </span>
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
          <h2 v-if="mode === 'login'">欢迎回来 <span>👋</span></h2>
          <h2 v-else>创建账号</h2>
          <p v-if="mode === 'login'">
            登录你的账号进入 MiQroGate 控制台，管理虚拟密钥、权限与用量数据。
          </p>
          <p v-else>注册后立即可用，无需审核；若部署关闭自助注册请联系管理员。</p>
        </div>

        <div v-if="errorMessage" class="login-error" role="alert" data-testid="login-error">
          <svg class="login-error__icon" width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="6.4" stroke="currentColor" stroke-width="1.4" />
            <path d="M8 5v3.4M8 10.6v.2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
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
            <span class="auth-label">{{ mode === 'login' ? '账号/邮箱' : '账号' }}</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><UserIcon size="18px" /></span>
              <input
                v-model="username"
                class="auth-input__inner"
                type="text"
                :placeholder="mode === 'login' ? '输入账号或邮箱' : '例如 alice'"
                autocomplete="username"
                data-testid="login-username"
              />
            </div>
          </div>

          <div v-if="mode === 'register'" class="auth-field">
            <span class="auth-label">昵称（可选）</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><UserIcon size="18px" /></span>
              <input
                v-model="displayName"
                class="auth-input__inner"
                type="text"
                placeholder="团队里展示的名字"
                autocomplete="name"
                data-testid="register-display-name"
              />
            </div>
          </div>

          <div class="auth-field">
            <span class="auth-label">密码</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><LockOnIcon size="18px" /></span>
              <input
                v-model="password"
                class="auth-input__inner auth-input__inner--eye"
                :type="showPassword ? 'text' : 'password'"
                :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
                :placeholder="mode === 'login' ? '输入密码' : '至少 8 位，含大小写字母和数字'"
                data-testid="login-password"
                @keydown.enter="submit"
              />
              <button
                type="button"
                class="input-eye"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
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
                  <path d="m4.5 4 15 16" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
                </svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
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
              <button type="button" class="text-link" @click="onForgot">忘记密码？</button>
            </div>
          </div>

          <div v-if="mode === 'register'" class="auth-field">
            <span class="auth-label">确认密码</span>
            <div class="auth-input">
              <span class="auth-input__prefix"><LockOnIcon size="18px" /></span>
              <input
                v-model="confirmPassword"
                class="auth-input__inner"
                :type="showPassword ? 'text' : 'password'"
                placeholder="再次输入密码"
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
            <span>{{ mode === 'login' ? '登 录' : '注册并进入' }}</span>
            <ArrowRightIcon size="18px" />
          </button>
        </form>

        <div class="or-divider"><span /> <em>或</em> <span /></div>

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
              <strong>申请账号</strong>
              <small>需要访问 MiQroGate？自助注册开启时可创建账号，或联系你的管理员。</small>
            </template>
            <template v-else>
              <strong>返回登录</strong>
              <small>已有门户账号？回到登录页。</small>
            </template>
          </span>
          <ArrowRightIcon size="18px" />
        </button>

        <div class="privacy-card">
          <span class="privacy-icon"><SecuredIcon size="19px" /></span>
          <span>
            <strong>你的数据受到保护</strong>
            <small>MiQroGate 运行在你的私有环境，绝不存储你的提示词与敏感数据。</small>
          </span>
        </div>
      </div>

      <footer class="auth-footer">
        <span>© MiQroGate · 私有 AI 基础设施</span>
        <span class="auth-footer-links"><span>隐私政策</span><i /> <span>服务条款</span></span>
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
    Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
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
  background:
    radial-gradient(circle at 63% 61%, rgba(96, 105, 255, 0.26), transparent 19%),
    radial-gradient(circle at 43% 28%, rgba(57, 108, 255, 0.16), transparent 26%),
    linear-gradient(156deg, #071224 0%, #0a1428 42%, #07101f 100%);
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

.hero-header,
.hero-footer {
  position: relative;
  z-index: 5;
}
.hero-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
}
.brand-name {
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.035em;
}
.brand-divider {
  width: 1px;
  height: 18px;
  margin: 0 4px 0 7px;
  background: rgba(255, 255, 255, 0.18);
}
.brand-product {
  color: #9aa6c2;
  font-size: 10px;
  letter-spacing: 0.08em;
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
.hero-locale {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #8c99b7;
  font-size: 11px;
}
.locale-chevron {
  color: #b6c1d5;
  margin-top: -3px;
}

.hero-content {
  position: relative;
  flex: 1;
  min-height: 0;
  display: grid;
  /* Copy column wide enough for the Chinese headline's 6-glyph lines (the
     reference's 0.8fr squeezed them into four ragged lines). */
  grid-template-columns: minmax(310px, 1.2fr) minmax(250px, 0.8fr);
  gap: 20px;
  align-items: center;
}
.hero-copy {
  position: relative;
  z-index: 5;
  align-self: center;
  max-width: 560px;
  padding-bottom: 22px;
}
.hero-eyebrow {
  margin: 0;
  color: #8e9dff;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.18em;
}
.hero-copy h1 {
  margin: 17px 0 24px;
  /* Sized so 密钥由你掌控。 holds one line at every desktop width (the
     reference image shows the headline as two unbroken lines). */
  font-size: clamp(40px, 3.9vw, 64px);
  line-height: 0.99;
  letter-spacing: -0.07em;
  font-weight: 760;
}
.hero-copy h1 span {
  color: #eef2ff;
}
.hero-copy h1 em {
  color: #8190ff;
  font-style: normal;
  text-shadow: 0 0 34px rgba(94, 105, 255, 0.26);
}
.hero-description {
  max-width: 500px;
  margin: 0 0 36px;
  color: #a4afc7;
  font-size: 13px;
  line-height: 1.9;
  letter-spacing: 0.005em;
}

.hero-capabilities {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 18px;
  max-width: 480px;
}
.capability {
  display: grid;
  grid-template-columns: 34px 1fr;
  gap: 11px;
  align-items: start;
}
.capability-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(139, 155, 255, 0.14);
  border-radius: 10px;
  color: #7c8aff;
  background: rgba(83, 98, 179, 0.08);
  box-shadow: inset 0 0 14px rgba(86, 104, 255, 0.04);
}
.capability strong {
  display: block;
  color: #e7ebf7;
  font-size: 11px;
  font-weight: 700;
}
.capability small {
  display: block;
  margin-top: 4px;
  color: #7d8aa3;
  font-size: 9px;
  line-height: 1.55;
}

.gate-scene {
  position: relative;
  min-height: 600px;
  margin-right: -24px;
  align-self: stretch;
}
.scene-aura {
  position: absolute;
  left: 31%;
  top: 35%;
  width: 42%;
  height: 30%;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(114, 114, 255, 0.4), rgba(83, 83, 255, 0.06) 52%, transparent 72%);
  filter: blur(35px);
}
.scene-floor {
  position: absolute;
  left: 12%;
  right: 0;
  bottom: 11%;
  height: 24%;
  transform: perspective(850px) rotateX(64deg);
  transform-origin: center bottom;
  border-top: 1px solid rgba(129, 146, 255, 0.1);
  background: linear-gradient(to bottom, rgba(61, 77, 144, 0.09), rgba(10, 17, 35, 0.75));
  box-shadow: 0 -40px 100px rgba(72, 79, 255, 0.08);
}
.scene-grid {
  position: absolute;
  inset: 10% -2% 10% 6%;
  opacity: 0.14;
  background-image:
    linear-gradient(rgba(115, 136, 255, 0.18) 1px, transparent 1px),
    linear-gradient(90deg, rgba(115, 136, 255, 0.18) 1px, transparent 1px);
  background-size: 38px 38px;
  mask-image: radial-gradient(circle at 58% 53%, #000, transparent 67%);
}
.scene-lines {
  position: absolute;
  inset: 13% 0 14% 0;
  width: 100%;
  height: 72%;
  overflow: visible;
}

.provider-card {
  position: absolute;
  z-index: 6;
  min-width: 150px;
  height: 48px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid rgba(151, 165, 255, 0.19);
  border-radius: 11px;
  background: linear-gradient(180deg, rgba(35, 47, 77, 0.83), rgba(13, 22, 43, 0.83));
  box-shadow:
    0 16px 30px rgba(0, 0, 0, 0.26),
    inset 0 1px 0 rgba(255, 255, 255, 0.07);
  backdrop-filter: blur(14px);
  color: #ecf0ff;
  font-size: 10px;
  letter-spacing: 0.01em;
}
.provider-card i {
  margin-left: auto;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #6fe4b1;
  box-shadow: 0 0 12px rgba(111, 228, 177, 0.8);
}
.provider-logo {
  width: 23px;
  height: 23px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  font-size: 10px;
  color: #fff;
  background: rgba(255, 255, 255, 0.09);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.provider-openai {
  left: 7%;
  top: 33%;
}
.provider-anthropic {
  left: 2%;
  top: 49%;
}
.provider-deepseek {
  left: 7%;
  top: 65%;
}
.provider-custom {
  left: 18%;
  top: 80%;
  min-width: 164px;
}

.gate-arch {
  position: absolute;
  z-index: 4;
  left: 33%;
  top: 22%;
  width: 38%;
  height: 55%;
  filter: drop-shadow(0 22px 40px rgba(0, 0, 0, 0.3));
}
.gate-column {
  position: absolute;
  top: 6%;
  width: 31%;
  height: 83%;
  border: 1px solid rgba(128, 145, 255, 0.55);
  background: linear-gradient(90deg, rgba(51, 63, 114, 0.85), rgba(21, 29, 58, 0.96));
}
.gate-column::after {
  content: '';
  position: absolute;
  inset: 5%;
  border: 1px solid rgba(180, 190, 255, 0.12);
  border-radius: 12px 12px 0 0;
}
.gate-column-left {
  left: 0;
  border-right: 0;
  border-radius: 22px 0 0 10px;
  transform: skewY(0deg) perspective(200px) rotateY(7deg);
  box-shadow:
    -22px 0 50px rgba(71, 90, 255, 0.1),
    inset 10px 0 23px rgba(101, 112, 255, 0.12);
}
.gate-column-right {
  right: 0;
  border-left: 0;
  border-radius: 0 22px 10px 0;
  transform: perspective(200px) rotateY(-7deg);
  box-shadow:
    22px 0 50px rgba(75, 89, 255, 0.13),
    inset -10px 0 25px rgba(101, 112, 255, 0.16);
}
.gate-top {
  position: absolute;
  left: 4%;
  right: 4%;
  top: 0;
  height: 18%;
  border: 1px solid rgba(145, 159, 255, 0.54);
  border-bottom: 0;
  border-radius: 28px 28px 0 0;
  background: linear-gradient(180deg, rgba(54, 66, 117, 0.92), rgba(34, 43, 79, 0.88));
  box-shadow:
    0 0 38px rgba(99, 111, 255, 0.11),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}
.gate-inner-glow {
  position: absolute;
  left: 16%;
  right: 16%;
  top: 17%;
  bottom: 10%;
  border-radius: 50px 50px 0 0;
  background: linear-gradient(180deg, rgba(64, 76, 143, 0.22), rgba(19, 27, 54, 0.04));
  box-shadow: inset 0 0 70px rgba(94, 105, 255, 0.08);
}
.gate-light-edge {
  position: absolute;
  top: 18%;
  bottom: 8%;
  width: 2px;
  background: linear-gradient(
    to bottom,
    transparent 0%,
    #7d89ff 16%,
    #c2b8ff 53%,
    rgba(86, 95, 255, 0.2) 100%
  );
  box-shadow:
    0 0 20px rgba(128, 125, 255, 0.9),
    0 0 45px rgba(102, 103, 255, 0.38);
}
.gate-light-left {
  left: 27%;
  transform: skewX(1deg);
}
.gate-light-right {
  right: 27%;
  transform: skewX(-1deg);
}
.gate-floor-reflection {
  position: absolute;
  left: 28%;
  right: 28%;
  bottom: -10%;
  height: 14%;
  background: radial-gradient(ellipse at center, rgba(116, 112, 255, 0.33), transparent 70%);
  filter: blur(18px);
}
.gate-status-card {
  position: absolute;
  z-index: 7;
  right: 2%;
  top: 39%;
  width: 150px;
  padding: 14px;
  border: 1px solid rgba(179, 189, 255, 0.22);
  border-radius: 14px;
  background: rgba(15, 24, 45, 0.82);
  box-shadow:
    0 20px 40px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(18px);
}
.gate-status-brand {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-bottom: 10px;
  margin-bottom: 9px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 11px;
}
.mini-symbol {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  background: linear-gradient(135deg, rgba(116, 126, 255, 0.25), rgba(67, 87, 255, 0.12));
  border: 1px solid rgba(145, 155, 255, 0.26);
}
.mini-symbol i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  background: #7f91ff;
  box-shadow: 0 0 10px rgba(127, 145, 255, 0.9);
}
.status-check {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-top: 6px;
  color: #abb5cb;
  font-size: 9px;
}
.status-check :deep(svg) {
  color: #62d8a8;
}
.scene-terminal {
  position: absolute;
  left: 24%;
  right: 9%;
  bottom: 4%;
  height: 31px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  border: 1px solid rgba(128, 145, 255, 0.12);
  border-radius: 8px;
  color: #72809c;
  background: rgba(16, 25, 45, 0.68);
  font: 8px ui-monospace, SFMono-Regular, Menlo, monospace;
  letter-spacing: 0.08em;
}
.scene-terminal b {
  margin-left: auto;
  color: #8a97bb;
  font-weight: 600;
}
.terminal-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #65d8a3;
  box-shadow: 0 0 9px rgba(101, 216, 163, 0.7);
}

.hero-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  color: #75829d;
  font-size: 9px;
}
.footer-trust {
  display: flex;
  align-items: center;
  gap: 22px;
}
.footer-trust span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.footer-slash {
  width: 1px;
  height: 13px;
  background: rgba(160, 170, 199, 0.15);
}
.hero-footer-version {
  color: #4f5b73;
  letter-spacing: 0.04em;
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
  color: #7f8ba4;
  font-size: 10px;
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
  font: 9px ui-monospace, SFMono-Regular, Menlo, monospace;
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
  font: 8px ui-monospace, SFMono-Regular, Menlo, monospace;
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
  /* Fractions only: the desktop column minimums would push the scene out of
     the (narrower) hero in the 920–1180 band. */
  .hero-content {
    grid-template-columns: minmax(260px, 1.25fr) minmax(0px, 0.75fr);
  }
  .hero-copy h1 {
    font-size: clamp(40px, 4.6vw, 58px);
  }
  .gate-scene {
    margin-right: -36px;
    transform: scale(0.92);
    transform-origin: center center;
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
  .gate-scene {
    position: absolute;
    inset: 0 -20px 0 28%;
    margin: 0;
    opacity: 0.72;
    transform: scale(0.9);
    transform-origin: center;
  }
  .hero-content {
    display: block;
  }
  .hero-copy {
    max-width: 490px;
  }
  .hero-description {
    max-width: 430px;
  }
  .hero-capabilities {
    grid-template-columns: 1fr 1fr;
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
  .brand-product,
  .brand-divider,
  .hero-locale,
  .hero-footer-version {
    display: none;
  }
  .hero-content {
    min-height: 520px;
  }
  .hero-copy {
    padding-top: 52px;
  }
  .hero-copy h1 {
    font-size: 47px;
  }
  .hero-description {
    font-size: 12px;
  }
  .hero-capabilities {
    position: relative;
    grid-template-columns: 1fr;
    gap: 12px;
    max-width: 300px;
  }
  .gate-scene {
    inset: 16% -80px 0 22%;
    opacity: 0.35;
  }
  .hero-footer {
    display: none;
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
