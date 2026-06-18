<template>
  <div :class="['layout-root', { 'layout-root--exam': isExamFullscreen }]">
    <template v-if="!isExamFullscreen && isStudent">
      <div class="student-shell">
        <aside class="student-sidebar">
          <div class="student-brand">
            <span class="student-brand__mark">E</span>
            <span>Exam</span>
          </div>

          <nav class="student-nav" aria-label="学生端导航">
            <button
              v-for="item in studentNavItems"
              :key="item.index"
              type="button"
              :class="['student-nav__item', { 'is-active': active === item.index }]"
              @click="onSelect(item.index)"
            >
              <svg class="student-nav__icon" viewBox="0 0 24 24" aria-hidden="true">
                <template v-if="item.icon === 'clipboard'">
                  <path d="M9 4.5h6" />
                  <path d="M9.5 3.5h5a1.5 1.5 0 0 1 1.5 1.5v1H8V5a1.5 1.5 0 0 1 1.5-1.5Z" />
                  <path d="M8 5.5H6.8A2.8 2.8 0 0 0 4 8.3v9.9A2.8 2.8 0 0 0 6.8 21h10.4a2.8 2.8 0 0 0 2.8-2.8V8.3a2.8 2.8 0 0 0-2.8-2.8H16" />
                  <path d="m9 13 2 2 4-4" />
                </template>
                <template v-else-if="item.icon === 'shield'">
                  <path d="M12 3.5 19 6v5.4c0 4.2-2.7 7.4-7 9.1-4.3-1.7-7-4.9-7-9.1V6l7-2.5Z" />
                  <path d="m9.5 12 1.7 1.7 3.4-3.4" />
                </template>
                <template v-else>
                  <path d="M5 19V13" />
                  <path d="M10 19V9" />
                  <path d="M15 19V5" />
                  <path d="M20 19V11" />
                  <path d="M4 19h17" />
                </template>
              </svg>
              <span>{{ item.label }}</span>
            </button>
          </nav>

          <div ref="studentAccountRef" class="student-sidebar__footer">
            <transition name="student-account-menu">
              <div
                v-if="studentMenuOpen"
                class="student-account-menu"
                role="menu"
                aria-label="学生账号菜单"
              >
                <div class="student-account-menu__header">
                  <strong>{{ auth.username || '未登录' }}</strong>
                  <span>学生账号</span>
                </div>
                <button type="button" class="student-account-menu__item" role="menuitem" @click="openProfileDialog">
                  <el-icon class="student-account-menu__icon"><Postcard /></el-icon>
                  <span>个人资料</span>
                </button>
                <button type="button" class="student-account-menu__item" role="menuitem" @click="openPasswordDialog">
                  <el-icon class="student-account-menu__icon"><Lock /></el-icon>
                  <span>修改密码</span>
                </button>
                <div class="student-account-menu__divider"></div>
                <button
                  type="button"
                  class="student-account-menu__item student-account-menu__item--danger"
                  role="menuitem"
                  @click="logout"
                >
                  <el-icon class="student-account-menu__icon"><SwitchButton /></el-icon>
                  <span>退出登录</span>
                </button>
              </div>
            </transition>

            <button
              type="button"
              :class="['student-account-trigger', { 'is-open': studentMenuOpen }]"
              :aria-expanded="studentMenuOpen"
              aria-haspopup="menu"
              @click="toggleStudentMenu"
            >
              <div class="student-profile__avatar">{{ userInitial }}</div>
              <div class="student-account-trigger__text">
                <strong>{{ auth.username || '未登录' }}</strong>
                <span>学生端</span>
              </div>
              <el-icon class="student-account-trigger__chevron">
                <ArrowDownBold v-if="studentMenuOpen" />
                <ArrowUpBold v-else />
              </el-icon>
            </button>
          </div>
        </aside>

        <main class="student-main">
          <div class="student-main__bar">
            <span>学生工作台</span>
            <span>{{ auth.username || 'student' }}</span>
          </div>
          <div class="student-content-wrapper">
            <router-view v-slot="{ Component }">
              <transition name="fade" mode="out-in">
                <component :is="Component" />
              </transition>
            </router-view>
          </div>
        </main>
      </div>
    </template>

    <template v-else>
      <header v-if="!isExamFullscreen" class="top-nav glass-card">
        <div class="nav-container">
          <div class="logo">
            <span class="logo-icon">✨</span>
            <span class="logo-text">Exam</span>
          </div>

          <el-menu 
            :default-active="active" 
            mode="horizontal" 
            @select="onSelect" 
            class="top-menu" 
            :ellipsis="false"
          >
            <!-- 教师端菜单 -->
            <el-sub-menu v-if="isTeacher" index="teacher">
              <template #title>教师工作台</template>
              <el-menu-item index="/teacher/exams">考试发布</el-menu-item>
              <el-menu-item index="/teacher/papers">组卷管理</el-menu-item>
              <el-menu-item index="/teacher/questions">题库管理</el-menu-item>
              <el-menu-item index="/teacher/proctoring">监考中心</el-menu-item>
              <el-menu-item index="/teacher/grading">主观阅卷</el-menu-item>
              <el-menu-item index="/teacher/classes">班级管理</el-menu-item>
              <el-menu-item index="/teacher/analytics">数据看板</el-menu-item>
            </el-sub-menu>

            <!-- 管理端菜单 -->
            <el-sub-menu v-if="isAdmin" index="admin">
              <template #title>系统管理</template>
              <el-menu-item index="/admin/users">用户管理</el-menu-item>
              <el-menu-item index="/admin/courses">课程管理</el-menu-item>
              <el-menu-item index="/admin/teaching-classes">教学班管理</el-menu-item>
              <el-menu-item index="/admin/bulk-import">批量导入</el-menu-item>
              <el-menu-item index="/admin/exam-monitor">考试监控</el-menu-item>
              <el-menu-item index="/admin/audit-logs">操作日志</el-menu-item>
            </el-sub-menu>
          </el-menu>

          <div class="user-actions">
            <div class="user-info">
              <div class="avatar">{{ userInitial }}</div>
              <span class="username">{{ auth.username || '未登录' }}</span>
            </div>
            <el-button size="small" plain round @click="openPasswordDialog" class="pwd-btn">修改密码</el-button>
            <el-button size="small" type="danger" plain round @click="logout" class="logout-btn">退出</el-button>
          </div>
        </div>
      </header>

      <main :class="['content', { 'content--exam': isExamFullscreen }]">
        <div v-if="!isExamFullscreen" class="content-wrapper">
          <router-view v-slot="{ Component }">
            <transition name="fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
        <router-view v-else v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </template>

    <el-dialog
      v-model="showPasswordDialog"
      title="修改密码"
      width="min(420px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      @closed="resetPasswordForm"
      class="student-custom-dialog"
    >
      <el-form :model="passwordForm" label-position="top" @submit.prevent="handleChangePassword" class="student-custom-form">
        <el-form-item label="当前密码" required>
          <el-input v-model="passwordForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="6-64 位新密码" />
        </el-form-item>
        <el-form-item label="确认新密码" required>
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <button type="button" class="student-btn student-btn--cancel" @click="showPasswordDialog = false">取消</button>
        <button type="button" class="student-btn student-btn--confirm" :disabled="changingPassword" @click="handleChangePassword">确认修改</button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="showProfileDialog"
      title="个人资料"
      width="min(460px, calc(100vw - 32px))"
      class="student-custom-dialog"
    >
      <div v-loading="profileLoading" class="student-profile-card">
        <div class="student-profile-card__header">
          <div class="student-profile-card__avatar">{{ userInitial }}</div>
          <div>
            <strong>{{ displayProfileValue(profile.realName) }}</strong>
            <span>{{ displayProfileValue(profile.username) }}</span>
          </div>
        </div>
        <dl class="student-profile-details">
          <div class="student-profile-details__item">
            <dt>姓名</dt>
            <dd>{{ displayProfileValue(profile.realName) }}</dd>
          </div>
          <div class="student-profile-details__item">
            <dt>用户名</dt>
            <dd>{{ displayProfileValue(profile.username) }}</dd>
          </div>
          <div class="student-profile-details__item">
            <dt>学号</dt>
            <dd>{{ displayProfileValue(profile.studentNo) }}</dd>
          </div>
          <div class="student-profile-details__item">
            <dt>入学年份</dt>
            <dd>{{ displayProfileValue(profile.enrollmentYear) }}</dd>
          </div>
        </dl>
      </div>
      <template #footer>
        <button type="button" class="student-btn student-btn--cancel" @click="showProfileDialog = false">关闭</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDownBold, ArrowUpBold, Lock, Postcard, SwitchButton } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { changePasswordApi, logoutApi, meApi } from '../api'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const active = computed(() => route.path)
const isExamFullscreen = computed(() => Boolean(route.meta?.examFullscreen))
const isAdmin = computed(() => auth.roles.includes('ADMIN'))
const isTeacher = computed(() => auth.roles.includes('TEACHER') || auth.roles.includes('ADMIN'))
const isStudent = computed(() => auth.roles.includes('STUDENT'))
const userInitial = computed(() => auth.username?.charAt(0)?.toUpperCase() || 'U')
const studentAccountRef = ref(null)
const studentMenuOpen = ref(false)
const showPasswordDialog = ref(false)
const changingPassword = ref(false)
const showProfileDialog = ref(false)
const profileLoading = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})
const profile = reactive({
  realName: '',
  username: '',
  studentNo: '',
  enrollmentYear: ''
})
const studentNavItems = [
  { index: '/student/exams', label: '我的考试', icon: 'clipboard' },
  { index: '/student/environment-check', label: '环境检测', icon: 'shield' },
  { index: '/student/results', label: '考试结果', icon: 'chart' }
]

const onSelect = (index) => {
  router.push(index)
}

const closeStudentMenu = () => {
  studentMenuOpen.value = false
}

const toggleStudentMenu = () => {
  studentMenuOpen.value = !studentMenuOpen.value
}

const onStudentDocumentClick = (event) => {
  if (!studentMenuOpen.value || studentAccountRef.value?.contains(event.target)) {
    return
  }
  closeStudentMenu()
}

const onStudentMenuKeydown = (event) => {
  if (event.key === 'Escape') {
    closeStudentMenu()
  }
}

const resetPasswordForm = () => {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
}

const resetProfile = () => {
  profile.realName = ''
  profile.username = ''
  profile.studentNo = ''
  profile.enrollmentYear = ''
}

const displayProfileValue = (value) => value || '--'

const openProfileDialog = async () => {
  closeStudentMenu()
  resetProfile()
  showProfileDialog.value = true
  profileLoading.value = true
  try {
    const data = await meApi()
    auth.setProfile(data || {})
    profile.realName = data?.realName || ''
    profile.username = data?.username || auth.username || ''
    profile.studentNo = data?.studentNo || ''
    profile.enrollmentYear = data?.enrollmentYear || ''
  } catch {
    resetProfile()
  } finally {
    profileLoading.value = false
  }
}

const openPasswordDialog = () => {
  closeStudentMenu()
  resetPasswordForm()
  showPasswordDialog.value = true
}

const handleChangePassword = async () => {
  if (!passwordForm.oldPassword) {
    ElMessage.warning('请输入当前密码')
    return
  }
  if (!passwordForm.newPassword || passwordForm.newPassword.length < 6) {
    ElMessage.warning('新密码长度至少 6 位')
    return
  }
  if (passwordForm.newPassword.length > 64) {
    ElMessage.warning('新密码长度不能超过 64 位')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changingPassword.value = true
  try {
    await changePasswordApi({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    ElMessage.success('密码修改成功，请重新登录')
    showPasswordDialog.value = false
    auth.clear()
    router.push('/login')
  } catch {
    // Error messages are handled by the HTTP interceptor.
  } finally {
    changingPassword.value = false
  }
}

const logout = async () => {
  closeStudentMenu()
  try {
    await logoutApi()
  } catch (error) {
    console.error(error)
  } finally {
    auth.clear()
    router.push('/login')
  }
}

onMounted(() => {
  document.addEventListener('click', onStudentDocumentClick)
  document.addEventListener('keydown', onStudentMenuKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onStudentDocumentClick)
  document.removeEventListener('keydown', onStudentMenuKeydown)
})
</script>

<style scoped>
.layout-root {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--bg-main);
}

.layout-root--exam {
  display: block;
}

.student-shell {
  --student-bg: #f8f5ef;
  --student-panel: #fcfaf6;
  --student-panel-strong: #ffffff;
  --student-line: #e5ddd0;
  --student-text: #1a1a18;
  --student-muted: #746f68;
  --student-accent: #cf6b4e;
  --student-accent-dark: #9d4830;
  --student-soft: #eee7dc;
  --brand: var(--student-accent);
  --brand-light: #f4ddd2;
  --brand-hover: var(--student-accent-dark);
  --bg-main: var(--student-bg);
  --bg-card: var(--student-panel-strong);
  --text-main: var(--student-text);
  --text-muted: var(--student-muted);
  --shadow-soft: 0 16px 40px rgba(54, 43, 33, 0.07);
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 100vh;
  background: var(--student-bg);
  color: var(--student-text);
}

.student-sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--student-line);
  background: rgba(252, 250, 246, 0.92);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.student-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 24px 30px;
  color: var(--student-text);
  font-family: Georgia, 'Times New Roman', 'Songti SC', serif;
  font-size: 25px;
  font-weight: 600;
}

.student-brand__mark {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--student-text);
  color: var(--student-bg);
  font: 700 15px/1 Georgia, serif;
}

.student-nav {
  display: grid;
  gap: 4px;
  padding: 0 12px;
}

.student-nav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 36px;
  padding: 0 14px 0 16px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--student-muted);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}

.student-nav__item:hover {
  color: var(--student-text);
  background: rgba(47, 45, 42, 0.035);
}

.student-nav__item.is-active {
  color: var(--student-text);
  background: #f1ece4;
  border-color: transparent;
  box-shadow: none;
}

.student-nav__item.is-active::before {
  content: '';
  position: absolute;
  left: 7px;
  top: 12px;
  bottom: 12px;
  width: 2px;
  border-radius: 999px;
  background: var(--student-accent);
}

.student-nav__icon {
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  color: #7b756f;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.student-nav__item.is-active .student-nav__icon {
  color: var(--student-accent);
}

.student-sidebar__footer {
  position: relative;
  z-index: 20;
  margin-top: auto;
  padding: 16px;
  border-top: 1px solid var(--student-line);
}

.student-account-menu {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: calc(100% + 10px);
  display: grid;
  gap: 4px;
  padding: 12px;
  border: 1px solid rgba(47, 45, 42, 0.14);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.97);
  box-shadow: 0 18px 42px rgba(47, 38, 28, 0.18), 0 2px 8px rgba(47, 38, 28, 0.08);
  color: var(--student-text);
}

.student-account-menu__header {
  min-width: 0;
  padding: 4px 8px 10px;
}

.student-account-menu__header strong,
.student-account-menu__header span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.student-account-menu__header strong {
  font-size: 14px;
  color: #8a847d;
}

.student-account-menu__header span {
  margin-top: 4px;
  font-size: 12px;
  color: var(--student-muted);
}

.student-account-menu__item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 42px;
  padding: 0 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--student-text);
  font: inherit;
  font-size: 15px;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.student-account-menu__item:hover {
  background: #f3eee6;
}

.student-account-menu__item--danger {
  color: #9d4830;
}

.student-account-menu__item--danger:hover {
  background: #fff3ed;
}

.student-account-menu__icon {
  width: 18px;
  font-size: 18px;
  color: currentColor;
}

.student-account-menu__divider {
  height: 1px;
  margin: 4px 8px;
  background: #ece4d8;
}

.student-account-trigger {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 66px;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: 16px;
  background: transparent;
  color: var(--student-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), box-shadow var(--transition-fast);
}

.student-account-trigger:hover,
.student-account-trigger.is-open {
  background: #f1ece4;
  border-color: #e0d6c8;
}

.student-account-trigger.is-open {
  box-shadow: inset 0 0 0 1px rgba(207, 107, 78, 0.12);
}

.student-profile__avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 44px;
  background: var(--student-text);
  color: var(--student-bg);
  font-weight: 700;
}

.student-account-trigger__text {
  min-width: 0;
  flex: 1;
}

.student-account-trigger__text strong,
.student-account-trigger__text span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.student-account-trigger__text strong {
  font-size: 15px;
  color: var(--student-text);
}

.student-account-trigger__text span {
  margin-top: 2px;
  font-size: 13px;
  color: var(--student-muted);
}

.student-account-trigger__chevron {
  flex: 0 0 auto;
  color: var(--student-muted);
  font-size: 12px;
}

.student-account-menu-enter-active,
.student-account-menu-leave-active {
  transition: opacity var(--transition-fast), transform var(--transition-fast);
}

.student-account-menu-enter-from,
.student-account-menu-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}

.student-profile-card {
  min-height: 226px;
  display: grid;
  gap: 18px;
}

.student-profile-card__header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 4px 2px 2px;
}

.student-profile-card__avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 48px;
  background: var(--student-text, #1a1a18);
  color: var(--student-bg, #f8f5ef);
  font-weight: 700;
}

.student-profile-card__header strong,
.student-profile-card__header span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.student-profile-card__header strong {
  color: var(--student-text, #1a1a18);
  font-size: 18px;
}

.student-profile-card__header span {
  margin-top: 3px;
  color: var(--student-muted, #746f68);
  font-size: 13px;
}

.student-profile-details {
  display: grid;
  gap: 10px;
  margin: 0;
}

.student-profile-details__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 46px;
  padding: 0 14px;
  border: 1px solid var(--student-line, #e5ddd0);
  border-radius: 10px;
  background: #fcfaf6;
}

.student-profile-details dt {
  flex: 0 0 auto;
  color: var(--student-muted, #746f68);
  font-size: 13px;
}

.student-profile-details dd {
  min-width: 0;
  margin: 0;
  color: var(--student-text, #1a1a18);
  font-size: 14px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.student-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.student-main__bar {
  height: 0;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  padding: 0 32px;
  overflow: hidden;
  color: var(--student-muted);
  font-size: 13px;
}

.student-content-wrapper {
  width: min(1200px, calc(100% - 64px));
  margin: 0 auto;
  padding: 48px 0 64px;
}

.top-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  border-radius: 0 !important;
  border-left: none !important;
  border-right: none !important;
  border-top: none !important;
  border-bottom: 1px solid rgba(59, 130, 246, 0.1) !important;
  background: rgba(255, 255, 255, 0.85) !important;
  box-shadow: 0 4px 24px rgba(59, 130, 246, 0.05) !important;
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.nav-container {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  max-width: 1400px;
  margin: 0 auto;
  padding: 0 24px;
  width: 100%;
  gap: 24px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 800;
  color: var(--brand);
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.logo-icon {
  font-size: 22px;
}

.top-menu {
  flex: 1;
  border-bottom: none !important;
  background: transparent !important;
  justify-content: center;
}

/* Override Element Plus Menu Styles */
:deep(.el-menu--horizontal > .el-menu-item),
:deep(.el-menu--horizontal > .el-sub-menu .el-sub-menu__title) {
  height: 64px;
  line-height: 64px;
  font-weight: 600;
  color: var(--text-muted);
  border-bottom: 2px solid transparent;
  transition: all 0.3s;
}

:deep(.el-menu--horizontal > .el-menu-item:hover),
:deep(.el-menu--horizontal > .el-sub-menu:hover .el-sub-menu__title) {
  color: var(--brand) !important;
  background: rgba(59, 130, 246, 0.04) !important;
}

:deep(.el-menu--horizontal > .el-menu-item.is-active),
:deep(.el-menu--horizontal > .el-sub-menu.is-active .el-sub-menu__title) {
  color: var(--brand) !important;
  border-bottom: 2px solid var(--brand) !important;
  background: rgba(59, 130, 246, 0.06) !important;
}

.user-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand) 0%, var(--brand-hover) 100%);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.2);
}

.username {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
  white-space: nowrap;
}

.logout-btn {
  font-weight: 600;
}

.pwd-btn {
  font-weight: 600;
}

.content {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.content-wrapper {
  flex: 1;
  max-width: 1400px;
  width: 100%;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
}

.content--exam {
  display: block;
}

@media (max-width: 768px) {
  .student-shell {
    grid-template-columns: 1fr;
  }

  .student-sidebar {
    position: static;
    height: auto;
    border-right: none;
    border-bottom: 1px solid var(--student-line);
  }

  .student-brand {
    padding: 16px;
  }

  .student-nav {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    padding-top: 0;
  }

  .student-nav__item {
    justify-content: center;
    padding: 0 8px;
    font-size: 13px;
  }

  .student-nav__icon,
  .student-main__bar {
    display: none;
  }

  .student-sidebar__footer {
    margin-top: 0;
    padding: 10px 16px 14px;
    border-top: none;
  }

  .student-account-menu {
    top: calc(100% - 4px);
    bottom: auto;
  }

  .student-account-trigger {
    min-height: 58px;
    padding: 8px 10px;
  }

  .student-profile-details__item {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
    padding: 10px 12px;
  }

  .student-content-wrapper {
    width: calc(100% - 32px);
    padding: 28px 0 36px;
  }

  .nav-container {
    padding: 0 16px;
    gap: 12px;
  }
  .username {
    display: none;
  }
  .content-wrapper {
    padding: 16px;
  }
}
</style>

<style>
/* Premium dialog styling for student views */
.student-custom-dialog {
  border-radius: 20px !important;
  background: #ffffff !important;
  box-shadow: rgba(0, 0, 0, 0.08) 0px 10px 40px 0px, rgba(30, 28, 25, 0.15) 0px 0px 0px 0.5px !important;
}

.student-custom-dialog .el-dialog__header {
  padding: 24px 28px 10px;
  margin-right: 0;
  border-bottom: none;
}

.student-custom-dialog .el-dialog__title {
  font-weight: 700;
  font-size: 20px;
  color: #1a1a18;
}

.student-custom-dialog .el-dialog__body {
  padding: 16px 28px 24px;
}

.student-custom-dialog .el-dialog__footer {
  padding: 16px 28px 24px;
  border-top: 1px solid rgba(0,0,0,0.04);
}

.student-custom-form .el-form-item__label {
  font-weight: 600;
  color: #1a1a18;
  padding-bottom: 6px;
}

.student-custom-form .el-input__wrapper {
  background: #ffffff;
  border-radius: 12px;
  box-shadow: rgba(0, 0, 0, 0.02) 0px 2px 8px 0px, rgba(30, 28, 25, 0.16) 0px 0px 0px 0.5px !important;
  padding: 4px 14px;
  transition: box-shadow 0.2s ease;
}

.student-custom-form .el-input__wrapper.is-focus {
  box-shadow: rgba(0, 0, 0, 0.04) 0px 4px 16px 0px, rgba(207, 107, 78, 0.40) 0px 0px 0px 1.5px !important;
}

.student-custom-form .el-input__inner {
  color: #1a1a18;
  height: 38px;
}

.student-btn {
  min-width: 86px;
  height: 38px;
  padding: 0 18px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 650;
  cursor: pointer;
  transition: all 0.2s ease;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.student-btn--cancel {
  background: #ffffff;
  border: 1px solid rgba(47, 45, 42, 0.15);
  color: #5a4f45;
  margin-right: 12px;
}

.student-btn--cancel:hover {
  background: #f8f8f6;
  border-color: rgba(47, 45, 42, 0.25);
  color: #1a1a18;
}

.student-btn--confirm {
  background: #cf6b4e;
  border: 1px solid transparent;
  color: #ffffff;
}

.student-btn--confirm:hover:not(:disabled) {
  background: #b55a40;
  box-shadow: rgba(207, 107, 78, 0.3) 0px 4px 12px 0px;
}

.student-btn--confirm:disabled {
  background: #e2a895;
  cursor: not-allowed;
}
</style>
