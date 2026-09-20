<template>
  <main class="login-page">
    <div class="tech-canvas" aria-hidden="true">
      <svg class="tech-grid" viewBox="0 0 1440 900" preserveAspectRatio="none">
        <defs>
          <pattern id="login-grid" width="48" height="48" patternUnits="userSpaceOnUse">
            <path d="M 48 0 L 0 0 0 48" fill="none" stroke="currentColor" stroke-width="1" />
            <circle cx="0" cy="0" r="1.5" fill="currentColor" />
          </pattern>
        </defs>
        <rect width="1440" height="900" fill="url(#login-grid)" />
      </svg>
      <span class="signal signal-top"></span>
      <span class="signal signal-bottom"></span>
      <span class="data-point point-one"></span>
      <span class="data-point point-two"></span>
      <span class="data-point point-three"></span>
    </div>

    <section class="login-shell" aria-labelledby="login-title">
      <section class="brand-panel" aria-labelledby="brand-title">
        <header class="brand-header">
          <div class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 5.5h6.2A3.8 3.8 0 0 1 14 9.3V20a2.8 2.8 0 0 0-2.8-2.8H4z" />
              <path d="M20 5.5h-6.2A3.8 3.8 0 0 0 10 9.3V20a2.8 2.8 0 0 1 2.8-2.8H20z" />
              <path d="M7.5 9.5h3" />
              <path d="M13.5 9.5h3" />
            </svg>
          </div>
          <div>
            <p class="product-label">EKUSYS EXAM</p>
            <p class="product-name">智能测评中心</p>
          </div>
        </header>

        <div class="brand-content">
          <p class="eyebrow"><span></span> EXAMINATION SYSTEM</p>
          <h1 id="brand-title">让每一次测评<br />都有据可循</h1>
          <p class="brand-subtitle">面向教学全流程的在线考试平台，以可靠的数据链路，守护每一份答卷。</p>

          <div class="system-visual" aria-hidden="true">
            <svg viewBox="0 0 460 180" fill="none">
              <path d="M24 141H118L151 108H236L269 75H357L406 26" stroke="currentColor" stroke-width="1.5" stroke-dasharray="5 7" />
              <path d="M24 158H139L175 122H272L307 87H436" stroke="currentColor" stroke-width="1.5" opacity=".42" />
              <rect x="114" y="132" width="42" height="42" rx="8" fill="currentColor" opacity=".12" />
              <rect x="118" y="136" width="34" height="34" rx="5" stroke="currentColor" stroke-width="1.5" />
              <path d="M128 153h14M135 146v14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
              <circle cx="24" cy="141" r="5" fill="#d58b1a" />
              <circle cx="151" cy="108" r="5" fill="#0f766e" />
              <circle cx="269" cy="75" r="5" fill="#0f766e" />
              <circle cx="406" cy="26" r="5" fill="#d58b1a" />
              <circle cx="436" cy="87" r="5" fill="#0f766e" />
              <text x="4" y="119" fill="currentColor">01</text>
              <text x="132" y="88" fill="currentColor">02</text>
              <text x="250" y="55" fill="currentColor">03</text>
              <text x="387" y="17" fill="currentColor">04</text>
            </svg>
          </div>

          <div class="capability-list">
            <div class="capability-item">
              <span class="capability-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M12 3 5.5 6v5.3c0 4.3 2.8 8.2 6.5 9.7 3.7-1.5 6.5-5.4 6.5-9.7V6z" />
                  <path d="m9.2 12 1.8 1.8 3.8-4" />
                </svg>
              </span>
              <span><strong>可靠过程</strong><small>考试状态全程可追溯</small></span>
            </div>
            <div class="capability-item">
              <span class="capability-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M4 19V9" />
                  <path d="M10 19V5" />
                  <path d="M16 19v-8" />
                  <path d="M22 19V3" />
                </svg>
              </span>
              <span><strong>智能评阅</strong><small>测评数据实时沉淀</small></span>
            </div>
          </div>
        </div>

        <footer class="brand-footer">
          <span class="status-dot" aria-hidden="true"></span>
          <span>系统服务正常</span>
          <span class="footer-divider" aria-hidden="true"></span>
          <span>© 2026 EKUSYS</span>
        </footer>
      </section>

      <section class="login-panel" aria-labelledby="login-title">
        <div class="login-card">
          <div class="login-card-header">
            <p class="login-kicker">SECURE ACCESS</p>
            <h2 id="login-title">欢迎回来</h2>
            <p>验证您的身份后，即可进入专属工作台。</p>
          </div>

          <el-form :model="form" label-position="top" class="login-form" @submit.prevent="onSubmit">
            <el-form-item label="用户名">
              <el-input
                v-model="form.username"
                name="username"
                autocomplete="username"
                placeholder="请输入用户名或邮箱"
                size="large"
              >
                <template #prefix>
                  <svg class="input-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="8" r="3.5" />
                    <path d="M5 20a7 7 0 0 1 14 0" />
                  </svg>
                </template>
              </el-input>
            </el-form-item>

            <el-form-item label="密码">
              <el-input
                v-model="form.password"
                name="password"
                type="password"
                autocomplete="current-password"
                show-password
                placeholder="请输入密码"
                size="large"
              >
                <template #prefix>
                  <svg class="input-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="4.5" y="10" width="15" height="10" rx="2" />
                    <path d="M8 10V7.5a4 4 0 0 1 8 0V10" />
                  </svg>
                </template>
              </el-input>
            </el-form-item>

            <div class="form-note">
              <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 3 5.5 6v5.3c0 4.3 2.8 8.2 6.5 9.7 3.7-1.5 6.5-5.4 6.5-9.7V6z" />
                <path d="M9.5 12.5h5" />
              </svg>
              <span>登录信息将通过加密链路传输</span>
            </div>

            <el-button
              type="primary"
              class="login-btn"
              native-type="submit"
              :loading="loading"
              :disabled="loading"
              size="large"
            >
              {{ loading ? '正在验证身份' : '进入工作台' }}
              <svg v-if="!loading" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
                <path d="M5 12h14" />
                <path d="m13 6 6 6-6 6" />
              </svg>
            </el-button>
          </el-form>

          <p class="help-text">登录遇到问题？请联系系统管理员获取支持。</p>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { loginApi, meApi } from '../../api'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const onSubmit = async () => {
  if (loading.value) return
  loading.value = true
  try {
    const tokens = await loginApi(form)
    auth.setAuth({
      ...tokens,
      roles: tokens.roles || []
    })
    const me = await meApi()
    auth.setProfile(me)
    ElMessage.success('登录成功，欢迎进入系统')
    if (me.roles.includes('STUDENT')) {
      router.push('/student/exams')
    } else if (me.roles.includes('ADMIN') && !me.roles.includes('TEACHER')) {
      router.push('/admin/users')
    } else {
      router.push('/teacher/questions')
    }
  } catch {
    // Error messages are handled by the HTTP interceptor.
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  --page-bg: #f5f6ef;
  --surface: #fffefa;
  --surface-soft: #f1f7f2;
  --ink: #183b39;
  --ink-muted: #5d716e;
  --line: #d8e5dd;
  --teal: #0f766e;
  --teal-deep: #0b5d57;
  --teal-soft: #dff2e9;
  --amber: #c87912;
  --amber-soft: #fff0cf;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  position: relative;
  overflow: hidden;
  padding: 32px;
  background: var(--page-bg);
  color: var(--ink);
  font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
}

.tech-canvas,
.tech-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.tech-canvas {
  color: #b9d8cc;
}

.tech-grid {
  width: 100%;
  height: 100%;
  opacity: .52;
}

.signal {
  position: absolute;
  width: 16rem;
  height: 16rem;
  border: 1px solid #b8dacc;
  border-radius: 50%;
}

.signal::before,
.signal::after {
  position: absolute;
  content: "";
  border: 1px solid #cce5da;
  border-radius: inherit;
  inset: 1.35rem;
}

.signal::after {
  inset: 3rem;
}

.signal-top {
  top: -8.5rem;
  right: 9%;
}

.signal-bottom {
  bottom: -12rem;
  left: 4%;
  width: 23rem;
  height: 23rem;
}

.data-point {
  position: absolute;
  display: block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--amber);
  box-shadow: 0 0 0 6px rgba(200, 121, 18, .12);
}

.point-one { top: 18%; left: 12%; }
.point-two { top: 69%; right: 10%; background: var(--teal); box-shadow: 0 0 0 6px rgba(15, 118, 110, .12); }
.point-three { bottom: 13%; left: 27%; }

.login-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(380px, .88fr);
  width: min(1120px, 100%);
  min-height: 660px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid rgba(15, 118, 110, .2);
  border-radius: 28px;
  box-shadow: 0 24px 55px rgba(20, 68, 62, .13), 0 3px 10px rgba(20, 68, 62, .05);
  animation: shell-enter .4s cubic-bezier(.16, 1, .3, 1) both;
}

.brand-panel {
  display: flex;
  flex-direction: column;
  padding: 42px 48px 32px;
  background: var(--surface-soft);
  border-right: 1px solid var(--line);
}

.brand-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-mark {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  color: var(--surface);
  background: var(--teal);
  border: 4px solid var(--teal-soft);
  border-radius: 14px;
}

.brand-mark svg { width: 24px; height: 24px; }
.product-label, .product-name { margin: 0; }

.product-label {
  color: var(--teal);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: .14em;
}

.product-name {
  margin-top: 3px;
  color: var(--ink);
  font-size: 15px;
  font-weight: 700;
}

.brand-content {
  margin: auto 0;
  padding: 46px 0 34px;
}

.eyebrow,
.login-kicker {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0;
  color: var(--teal);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: .13em;
}

.eyebrow span {
  display: inline-block;
  width: 28px;
  height: 2px;
  background: var(--amber);
}

.brand-content h1 {
  margin: 18px 0 14px;
  color: var(--ink);
  font-size: clamp(36px, 3vw, 46px);
  font-weight: 800;
  letter-spacing: -.05em;
  line-height: 1.16;
}

.brand-subtitle {
  max-width: 405px;
  margin: 0;
  color: var(--ink-muted);
  font-size: 15px;
  line-height: 1.8;
}

.system-visual {
  width: min(100%, 450px);
  margin: 28px 0 21px;
  color: var(--teal);
}

.system-visual svg { display: block; width: 100%; height: auto; }
.system-visual text { font-size: 10px; font-weight: 700; letter-spacing: .08em; }

.capability-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.capability-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 68px;
  padding: 11px;
  background: rgba(255, 254, 250, .86);
  border: 1px solid var(--line);
  border-radius: 12px;
}

.capability-icon {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  place-items: center;
  color: var(--teal);
  background: var(--teal-soft);
  border-radius: 9px;
}

.capability-icon svg { width: 19px; height: 19px; }
.capability-item strong, .capability-item small { display: block; }

.capability-item strong {
  color: var(--ink);
  font-size: 13px;
  line-height: 1.4;
}

.capability-item small {
  margin-top: 2px;
  color: var(--ink-muted);
  font-size: 11px;
  line-height: 1.4;
}

.brand-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-muted);
  font-size: 11px;
  letter-spacing: .02em;
}

.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #198754;
  box-shadow: 0 0 0 4px rgba(25, 135, 84, .12);
}

.footer-divider { width: 1px; height: 12px; margin: 0 4px; background: var(--line); }

.login-panel {
  display: grid;
  place-items: center;
  padding: 48px;
  background: var(--surface);
}

.login-card { width: min(100%, 372px); }

.login-card-header { margin-bottom: 34px; }

.login-kicker { color: var(--amber); }

.login-card-header h2 {
  margin: 12px 0 10px;
  color: var(--ink);
  font-size: 32px;
  font-weight: 760;
  letter-spacing: -.04em;
  line-height: 1.18;
}

.login-card-header > p:last-child {
  margin: 0;
  color: var(--ink-muted);
  font-size: 14px;
  line-height: 1.7;
}

.login-form { margin: 0; }

:deep(.el-form-item) { margin-bottom: 22px; }

:deep(.el-form-item__label) {
  padding-bottom: 8px !important;
  color: var(--ink) !important;
  font-size: 14px !important;
  font-weight: 650 !important;
  line-height: 1.4 !important;
}

:deep(.el-input__wrapper) {
  min-height: 50px;
  padding: 1px 14px;
  background: #fbfdf9 !important;
  border-radius: 12px !important;
  box-shadow: 0 0 0 1px #ccddd4 inset !important;
  transition: box-shadow .2s ease, background-color .2s ease !important;
}

:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #91bfb0 inset !important;
}

:deep(.el-input__wrapper.is-focus) {
  background: #fff !important;
  box-shadow: 0 0 0 2px var(--teal) inset, 0 0 0 4px rgba(15, 118, 110, .12) !important;
}

:deep(.el-input__inner) {
  height: 48px !important;
  color: var(--ink) !important;
  font-size: 15px !important;
}

:deep(.el-input__inner::placeholder) { color: #91a49e !important; }

:deep(.el-input__inner:-webkit-autofill),
:deep(.el-input__inner:-webkit-autofill:hover),
:deep(.el-input__inner:-webkit-autofill:focus),
:deep(.el-input__inner:-webkit-autofill:active) {
  -webkit-text-fill-color: var(--ink) !important;
  caret-color: var(--ink) !important;
  box-shadow: 0 0 0 1000px #fbfdf9 inset !important;
  transition: background-color 5000s ease-in-out 0s;
}

.input-icon { width: 19px; height: 19px; color: #6a8279; }
:deep(.el-input__wrapper.is-focus) .input-icon { color: var(--teal); }

.form-note {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 2px 0 24px;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.5;
}

.form-note svg { flex: 0 0 auto; width: 15px; height: 15px; color: var(--teal); }

.login-btn {
  width: 100%;
  height: 52px !important;
  color: #fff !important;
  background: var(--teal) !important;
  border: 1px solid var(--teal) !important;
  border-radius: 12px !important;
  box-shadow: 0 8px 14px rgba(15, 118, 110, .19) !important;
  font-size: 15px !important;
  font-weight: 700 !important;
  letter-spacing: .01em;
  transition: background-color .2s ease, box-shadow .2s ease, transform .2s ease !important;
}

.login-btn svg { width: 18px; height: 18px; margin-left: 8px; vertical-align: -4px; }

.login-btn:hover:not(.is-disabled) {
  background: var(--teal-deep) !important;
  border-color: var(--teal-deep) !important;
  box-shadow: 0 11px 18px rgba(15, 118, 110, .24) !important;
  transform: translateY(-1px);
}

.login-btn:active:not(.is-disabled) { transform: translateY(0); }

.login-btn:focus-visible {
  outline: 3px solid rgba(200, 121, 18, .45);
  outline-offset: 3px;
}

.help-text {
  margin: 23px 0 0;
  color: #72847e;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

@keyframes shell-enter {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 920px) {
  .login-page { padding: 24px; }
  .login-shell { grid-template-columns: 1fr; max-width: 540px; min-height: 0; }
  .brand-panel { padding: 28px 32px; border-right: 0; border-bottom: 1px solid var(--line); }
  .brand-content, .brand-footer { display: none; }
  .login-panel { padding: 40px 32px; }
}

@media (max-width: 520px) {
  .login-page { place-items: stretch; padding: 0; }
  .tech-grid { opacity: .32; }
  .login-shell { width: 100%; border: 0; border-radius: 0; box-shadow: none; }
  .brand-panel { padding: 24px; }
  .login-panel { align-items: start; padding: 40px 24px 48px; }
  .login-card { width: 100%; }
  .login-card-header h2 { font-size: 30px; }
  .signal-top { top: -11rem; right: -8rem; }
  .signal-bottom { left: -13rem; }
}

@media (prefers-reduced-motion: reduce) {
  .login-shell,
  .login-btn,
  :deep(.el-input__wrapper) { animation: none !important; transition: none !important; }
}
</style>
