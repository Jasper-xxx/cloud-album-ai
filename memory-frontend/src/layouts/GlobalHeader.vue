<template>
  <div id="globalHeader" ref="headerRef">
    <div class="header-inner">

      <!-- 原生 button：部分系统 WebView 对 div+click 合成事件不稳定；无侧栏页面设 show-app-menu=false -->
      <button
        v-if="showAppMenu"
        type="button"
        class="mobile-menu-btn"
        aria-label="打开或关闭侧栏菜单"
        @click.stop="emitToggleSidebar"
      >
        <el-icon class="menu-icon"><i-ep-expand /></el-icon>
      </button>

      <!-- Logo -->
      <div class="header-logo" @click="router.push('/home')">
        <div class="logo-mark">
          <i-ep-camera class="logo-mark-icon" />
        </div>
        <div class="logo-copy">
          <span class="logo-text">Cloud Album</span>
          <span class="logo-caption">MEMORY STUDIO</span>
        </div>
      </div>

      <div class="header-context">
        <span class="context-kicker">{{ currentPage.kicker }}</span>
        <span class="context-title">{{ currentPage.title }}</span>
      </div>

      <!-- 搜索栏 -->
      <div class="header-search" v-if="showSearh">
        <el-input
          v-model="input"
          placeholder="搜索照片、地点、人物、标签..."
          class="search-input"
          :prefix-icon="Search"
          @focus="router.push('/search')"
        />
      </div>

      <!-- 右侧用户区 -->
      <div class="header-actions">
        <template v-if="isLogin">
          <AsyncTaskCenter :user-id="userInfo.userId" />
          <el-dropdown trigger="click" popper-class="user-popper">
            <div class="user-trigger">
              <el-avatar :src="userInfo.avatarUrl" :size="34" class="user-avatar" />
              <div class="user-text">
                <span class="user-name">{{ userInfo.userName }}</span>
                <el-tag
                  :type="getTagType(userInfo.accountStatus)"
                  size="small"
                  :effect="getTagEffect(userInfo.accountStatus)"
                  class="level-tag"
                >
                  {{ getUserLevelText(userInfo.accountStatus) }}
                </el-tag>
              </div>
              <el-icon class="chevron-icon"><arrow-down /></el-icon>
            </div>

            <template #dropdown>
              <div class="dropdown-panel">
                <div class="dropdown-hero">
                  <div class="hero-avatar-wrap">
                    <el-avatar :src="userInfo.avatarUrl" :size="58" class="hero-avatar" />
                  </div>
                  <div class="dropdown-info">
                    <span class="dropdown-name">{{ userInfo.userName }}</span>
                    <span class="dropdown-email">{{ userInfo.email }}</span>
                  </div>
                  <el-tag
                    :type="getTagType(userInfo.accountStatus)"
                    size="small"
                    :effect="getTagEffect(userInfo.accountStatus)"
                    class="hero-level-tag"
                  >
                    {{ getUserLevelText(userInfo.accountStatus) }}
                  </el-tag>
                </div>

                <div class="dropdown-storage-card">
                  <div class="storage-row">
                    <span class="storage-label-text">云端容量</span>
                    <span class="storage-percent">{{ storagePercentage }}%</span>
                  </div>
                  <el-progress
                    :percentage="storagePercentage"
                    :show-text="false"
                    :stroke-width="7"
                    :color="progressColor"
                  />
                  <div class="storage-usage">
                    {{ formatFileSize(userInfo.usedSpace) }} 已用 / {{ formatFileSize(userInfo.totalSpace) }}
                  </div>
                </div>

                <el-dropdown-menu class="dropdown-menu-clean dropdown-quick-grid">
                  <el-dropdown-item class="dp-item dp-card" @click="goToUserInfo">
                    <el-icon><i-ep-user /></el-icon>
                    <span>个人中心</span>
                  </el-dropdown-item>
                  <el-dropdown-item class="dp-item dp-card" @click="router.push('/storageSet')">
                    <el-icon><i-ep-folder /></el-icon>
                    <span>存储管理</span>
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="userInfo.accountStatus === 'normal'"
                    class="dp-item dp-card dp-item--upgrade"
                    @click="navigateToMember"
                  >
                    <el-icon><i-ep-star /></el-icon>
                    <span>升级会员</span>
                  </el-dropdown-item>
                </el-dropdown-menu>

                <el-dropdown-menu class="dropdown-menu-clean dropdown-exit-row">
                  <el-dropdown-item class="dp-item dp-item--danger" @click="handleLogout">
                    <el-icon><i-ep-switch-button /></el-icon>
                    <span>退出登录</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </div>
            </template>
          </el-dropdown>
        </template>

        <template v-else>
          <el-button class="btn-ghost" @click="goToLogin">登录</el-button>
          <el-button class="btn-primary" type="primary" @click="goToRegister">免费注册</el-button>
        </template>
      </div>

    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref, computed, defineAsyncComponent, onMounted, onUnmounted, nextTick } from 'vue'
import { Search, ArrowDown } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { accountLogout } from '@/api/user/auth'
import { getUserInfo } from '@/api/user/index'
import $bus from '@/utils/bus.ts'
import { storage } from '@/utils/storage'
import { clearAllAuthData, getToken } from '@/utils/auth'
const AsyncTaskCenter = defineAsyncComponent(() => import('@/components/task/AsyncTaskCenter.vue'))
interface Props {
  showSearh: boolean
  /** 无 GlobalSider 的页面（如分享页）请设为 false */
  showAppMenu?: boolean
}
const props = withDefaults(defineProps<Props>(), { showAppMenu: true })

const emit = defineEmits<{ toggleSidebar: [] }>()

const headerRef = ref<HTMLElement | null>(null)

const syncHeaderHeightVar = () => {
  if (typeof window === 'undefined') return
  if (window.innerWidth > 992) return
  const el = headerRef.value
  if (!el) return
  const h = Math.ceil(el.getBoundingClientRect().height)
  document.documentElement.style.setProperty('--mobile-header-total', `${h}px`)
}

const isLogin = ref(false);
const router = useRouter()
const route = useRoute()
const input = ref('')

const pageMeta: Record<string, { title: string; kicker: string }> = {
  home: { title: '全部图片', kicker: 'LIBRARY' },
  recent: { title: '最近上传', kicker: 'LIBRARY' },
  brush: { title: '相似图片清理', kicker: 'TOOLS' },
  ai: { title: 'AI 图像处理', kicker: 'INTELLIGENCE' },
  imageSearch: { title: '以图搜图', kicker: 'DISCOVERY' },
  album: { title: '相册', kicker: 'COLLECTIONS' },
  AlbumPicture: { title: '相册详情', kicker: 'COLLECTIONS' },
  person: { title: '人物', kicker: 'MEMORIES' },
  personAlbum: { title: '人物相册', kicker: 'MEMORIES' },
  location: { title: '地点', kicker: 'MEMORIES' },
  LocationAlbum: { title: '地点相册', kicker: 'MEMORIES' },
  model: { title: '拍摄设备', kicker: 'MEMORIES' },
  ModelAlbum: { title: '设备相册', kicker: 'MEMORIES' },
  fileVisual: { title: '文件分析', kicker: 'INSIGHTS' },
  locationVisual: { title: '照片足迹', kicker: 'INSIGHTS' },
  tagVisual: { title: '标签词云', kicker: 'INSIGHTS' },
  recycle: { title: '回收站', kicker: 'SYSTEM' },
  record: { title: '操作记录', kicker: 'SYSTEM' },
  userMid: { title: '个人中心', kicker: 'ACCOUNT' },
  userInfo: { title: '基本信息', kicker: 'ACCOUNT' },
  storageSet: { title: '容量管理', kicker: 'ACCOUNT' },
  securitySet: { title: '安全设置', kicker: 'ACCOUNT' },
  memberCenter: { title: '会员中心', kicker: 'ACCOUNT' },
}

const currentPage = computed(() =>
  pageMeta[String(route.name || '')] ?? { title: '影像工作台', kicker: 'WORKSPACE' }
)

const progressColor = [
    { color: '#6366F1', percentage: 60 },
    { color: '#F59E0B', percentage: 80 },
    { color: '#EF4444', percentage: 100 }
]

const userInfo = ref(<API.UserInfo>{
    userId: 1000000001,
    userName: "鹏",
    account: "xzp",
    email: "1014537454@qq.com",
    createTime: "2025-02-20T15:18:26",
    updateTime: "2025-03-04T22:31:21",
    profile: "您好",
    avatarUrl: "http://127.0.0.1:9000/pictures//avatar/1000000001/1000000001.jpg?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=nbOVDrriWWUzwqzoeSuW%2F20250304%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20250304T143121Z&X-Amz-Expires=604800&X-Amz-SignedHeaders=host&X-Amz-Signature=45944478663dcbcd0dc86e95a91312ee598384d012d4872cdad548f35fd64b8a",
    totalSpace: 21474836480,
    usedSpace: 32988616,
    accountStatus: "svip",
    membershipDays: 100000
})

const storagePercentage = computed(() =>
    Math.round((userInfo.value.usedSpace / userInfo.value.totalSpace) * 100)
)

const formatFileSize = (bytes: number) => {
    if (bytes === 0) return '0 B'
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    let size = Math.abs(bytes)
    let unitIndex = 0
    while (size >= 1024 && unitIndex < units.length - 1) {
        size /= 1024
        unitIndex++
    }
    const formattedSize = size.toFixed(1).replace(/\.0$/, '')
    return `${formattedSize} ${units[unitIndex]}`
}

const goToLogin = () => { $bus.emit('loginForm', true) }
const goToRegister = () => { $bus.emit('registerForm', true) }

const emitToggleSidebar = () => {
  emit('toggleSidebar')
}

onMounted(() => {
    nextTick(() => {
      syncHeaderHeightVar()
    })
    window.addEventListener('resize', syncHeaderHeightVar, { passive: true } as any)
    window.addEventListener('orientationchange', syncHeaderHeightVar, { passive: true } as any)
    checkLogin();
    $bus.off('login')
    $bus.on('login', () => { checkLogin() })
    // 监听个人信息保存后的通知，同步右上角昵称/头像等信息
    $bus.on('userInfoUpdated', (updated) => {
        userInfo.value = updated as API.UserInfo
        storage.set('USER_INFO', updated as API.UserInfo)
    })
})

onUnmounted(() => {
  window.removeEventListener('resize', syncHeaderHeightVar as any)
  window.removeEventListener('orientationchange', syncHeaderHeightVar as any)
})

const checkLogin = async () => {
    const token = getToken();
    if (token) {
        const res = await getUserInfo();
        if (res.code == 200) {
            storage.set('USER_INFO', res.data);
            userInfo.value = res.data;
            isLogin.value = true;
        }
    } else {
        isLogin.value = false;
    }
}

const handleLogout = async () => {
    // 立即更新本地 UI 状态（不等待接口返回）
    isLogin.value = false

    try {
        const resp = await accountLogout()
        if (resp.code == 200) {
            ElMessage({ type: 'success', message: resp.message })
        } else {
            ElMessage({ type: 'error', message: resp.message })
        }
    } catch (error) {
        // 接口失败不影响本地登出（网络断开时也能正常退出）
        console.error('退出登录接口调用失败:', error)
    } finally {
        clearAllAuthData()

        // 跳转到登录页（无延迟，数据已清除可立即跳转）
        router.push({ name: 'login' })
    }
}

const getUserLevelText = (level: string) => {
    const levelMap: Record<string, string> = {
        normal: '普通用户', vip: 'VIP', svip: 'SVIP'
    }
    return levelMap[level] || '未知'
}

type ValidTagType = "success" | "warning" | "info" | "primary" | "danger"
const getTagType = (level: string): ValidTagType => {
    const typeMap: Record<string, ValidTagType> = {
        normal: 'info', vip: 'danger', svip: 'warning'
    }
    return typeMap[level] || 'info'
}

const navigateToMember = () => { router.push({ name: 'memberCenter' }) }
const getTagEffect = (level: string) => { return level === 'normal' ? 'plain' : 'dark' }
const goToUserInfo = () => { router.push({ name: 'userInfo' }) }
</script>

<style scoped>
#globalHeader {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 100;
  width: 100%;
  height: 60px;
  background: rgba(255, 255, 255, 0.97);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid #E5E7EB;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.header-inner {
  display: flex;
  align-items: center;
  height: 100%;
  padding: 0 20px 0 0;
  gap: 16px;
}

/* Logo */
.header-logo {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
  width: 200px;
  padding: 0 0 0 16px;
  transition: opacity 0.2s;
}

.header-logo:hover {
  opacity: 0.85;
}

.logo-mark {
  width: 30px;
  height: 30px;
  background: linear-gradient(135deg, #6366F1, #8B5CF6);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 15px;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.3);
}

.logo-mark-icon {
  font-size: 15px;
}

.logo-text {
  font-size: 17px;
  font-weight: 700;
  color: #111827;
  letter-spacing: -0.4px;
}

.logo-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.logo-caption {
  color: #9CA3AF;
  font-size: 8px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 1.8px;
}

.header-context {
  display: none;
}

/* 搜索 */
.header-search {
  flex: 1;
  max-width: 500px;
}

.search-input :deep(.el-input__wrapper) {
  background: #F3F4F6;
  border: 1.5px solid transparent;
  border-radius: 10px;
  box-shadow: none !important;
  transition: all 0.2s;
  height: 38px;
}

.search-input :deep(.el-input__wrapper:hover) {
  background: #EEF2FF;
  border-color: #C7D2FE;
}

.search-input :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  border-color: #6366F1;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12) !important;
}

.search-input :deep(.el-input__prefix) {
  color: #9CA3AF;
}

.search-input :deep(.el-input__inner::placeholder) {
  color: #9CA3AF;
  font-size: 14px;
}

/* 右侧操作区 */
.header-actions {
  display: flex;
  align-items: center;
  margin-left: auto;
  gap: 10px;
}

/* 用户触发器 */
.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 5px 10px 5px 6px;
  border-radius: 10px;
  transition: background 0.2s;
  user-select: none;
}

.user-trigger:hover {
  background: #F3F4F6;
}

.user-avatar {
  flex-shrink: 0;
  border: 2px solid #E5E7EB;
}

.user-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  line-height: 1;
}

.level-tag {
  font-size: 10px;
  padding: 0 5px;
  height: 16px;
  line-height: 16px;
  border-radius: 4px;
}

.chevron-icon {
  font-size: 11px;
  color: #9CA3AF;
  transition: transform 0.2s;
}

/* 下拉面板 */
.dropdown-panel {
  width: 318px;
  padding: 12px;
  border-radius: 20px;
  background:
    linear-gradient(180deg, #f8faff 0%, #eef3ff 100%);
}

.dropdown-hero {
  position: relative;
  display: flex;
  align-items: center;
  gap: 13px;
  min-height: 96px;
  padding: 18px;
  overflow: hidden;
  border: 1px solid rgba(99, 102, 241, 0.12);
  border-radius: 18px;
  background:
    radial-gradient(circle at 18% 10%, rgba(255, 218, 118, 0.38), transparent 28%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(231, 236, 252, 0.9));
  box-shadow: 0 14px 32px rgba(55, 65, 121, 0.11);
}

.dropdown-hero::after {
  position: absolute;
  right: -28px;
  bottom: -42px;
  width: 118px;
  height: 118px;
  content: "";
  border-radius: 50%;
  background: rgba(99, 102, 241, 0.1);
}

.hero-avatar-wrap {
  position: relative;
  z-index: 1;
  padding: 3px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffffff, #dfe5ff);
  box-shadow: 0 12px 26px rgba(50, 59, 114, 0.16);
}

.hero-avatar {
  display: block;
}

.dropdown-info {
  position: relative;
  z-index: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  overflow: hidden;
}

.dropdown-name {
  font-size: 18px;
  font-weight: 750;
  color: #20243b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dropdown-email {
  font-size: 12px;
  color: #7b839b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hero-level-tag {
  position: absolute;
  right: 16px;
  top: 14px;
  z-index: 1;
  border-radius: 999px;
}

.dropdown-storage-card {
  margin-top: 10px;
  padding: 14px;
  border: 1px solid rgba(105, 115, 160, 0.14);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 8px 22px rgba(50, 59, 114, 0.06);
}

.storage-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.storage-label-text {
  font-size: 13px;
  font-weight: 650;
  color: #3f465d;
}

.storage-percent {
  color: #6366f1;
  font-size: 13px;
  font-weight: 750;
}

.storage-usage {
  margin-top: 8px;
  font-size: 12px;
  color: #8a91a7;
}

:deep(.dropdown-menu-clean) {
  border: none !important;
  box-shadow: none !important;
  padding: 0 !important;
  background: transparent !important;
}

:deep(.dropdown-quick-grid) {
  display: grid !important;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}

:deep(.dropdown-quick-grid .dp-card) {
  width: auto !important;
  height: 76px !important;
  min-height: 76px !important;
  flex-direction: column !important;
  align-items: flex-start !important;
  justify-content: center !important;
  gap: 8px !important;
  padding: 12px !important;
  border: 1px solid rgba(105, 115, 160, 0.13);
  border-radius: 15px !important;
  background: rgba(255, 255, 255, 0.72) !important;
  box-shadow: 0 8px 20px rgba(50, 59, 114, 0.05);
}

:deep(.dp-item) {
  display: flex !important;
  align-items: center;
  gap: 10px;
  padding: 10px 12px !important;
  border-radius: 12px !important;
  font-size: 13px;
  color: #374151;
  transition: background 0.15s !important;
}

:deep(.dp-item .el-icon) {
  font-size: 18px;
}

:deep(.dp-item:hover) {
  background: #ffffff !important;
  color: #4f50b8 !important;
}

:deep(.dp-item--upgrade:hover) {
  background: #fff8e7 !important;
  color: #6366F1 !important;
}

:deep(.dropdown-exit-row) {
  margin-top: 10px;
}

:deep(.dp-item--danger) {
  width: 100%;
  justify-content: center !important;
  color: #EF4444 !important;
  background: rgba(239, 68, 68, 0.07) !important;
}

:deep(.dp-item--danger:hover) {
  background: #FEF2F2 !important;
}

/* ── 手机端汉堡按钮 ──────────────────────────────────────── */
.mobile-menu-btn {
  display: none;          /* PC端隐藏 */
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: none;
  margin: 0;
  background: transparent;
  border-radius: 8px;
  cursor: pointer;
  flex-shrink: 0;
  color: #374151;
  font: inherit;
  transition: background 0.2s;
  position: relative;
  z-index: 5;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
  /* 左侧与安全区域对齐 */
  margin-left: max(8px, env(safe-area-inset-left, 8px));
}

.mobile-menu-btn:hover {
  background: #F3F4F6;
}

.menu-icon {
  font-size: 20px;
}

/* 紧凑布局：顶栏双行单列（与 layoutBreakpoint 992px 一致） */
@media (max-width: 992px) {
  .mobile-menu-btn {
    display: flex;
  }

  .header-inner {
    flex-wrap: wrap;
    height: auto !important;
    min-height: 48px;
    align-items: center;
    padding: 6px 12px 10px 0;
    gap: 8px 10px;
    row-gap: 10px;
  }

  .mobile-menu-btn {
    order: 1;
  }

  .header-logo {
    order: 2;
    width: auto !important;
    padding: 0 0 0 4px !important;
    flex: 0 1 auto;
    min-width: 0;
  }

  .header-actions {
    order: 3;
    margin-left: auto !important;
    flex-shrink: 0;
  }

  .header-search {
    order: 10;
    flex: 1 1 100%;
    width: 100% !important;
    max-width: 100% !important;
    min-width: 0;
  }

  .logo-text {
    max-width: 42vw;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

@media (max-width: 480px) {
  .logo-text {
    display: none; /* 极小屏幕隐藏文字，只显示 logo 图标 */
  }

  /* 搜索框在极小屏幕占满剩余空间 */
  .header-search {
    max-width: none !important;
  }

  /* 手机端隐藏用户名和等级标签，只保留头像 */
  .user-text,
  .chevron-icon {
    display: none !important;
  }

  .user-trigger {
    padding: 5px !important;
    gap: 0 !important;
  }
}

/* 登录/注册按钮 */
.btn-ghost {
  height: 34px;
  padding: 0 16px;
  border-radius: 8px;
  font-size: 13px;
  color: #374151;
  border-color: #D1D5DB;
}

.btn-ghost:hover {
  border-color: #6366F1;
  color: #6366F1;
  background: #EEF2FF;
}

.btn-primary {
  height: 34px;
  padding: 0 16px;
  border-radius: 8px;
  font-size: 13px;
  background: #6366F1;
  border-color: #6366F1;
}

.btn-primary:hover {
  background: #4F46E5;
  border-color: #4F46E5;
}

@media (min-width: 993px) {
  #globalHeader {
    height: 72px;
    border-bottom-color: rgba(108, 117, 161, 0.13);
    background:
      linear-gradient(90deg, rgba(246, 248, 253, 0.94), rgba(241, 244, 251, 0.88));
    box-shadow: 0 10px 36px rgba(36, 43, 75, 0.08);
    backdrop-filter: blur(22px) saturate(135%);
    -webkit-backdrop-filter: blur(22px) saturate(135%);
  }

  .header-inner {
    gap: 22px;
    padding-right: 24px;
  }

  .header-logo {
    position: relative;
    width: 224px;
    box-sizing: border-box;
    padding-left: 22px;
  }

  .logo-mark {
    width: 38px;
    height: 38px;
    border: 1px solid rgba(255, 255, 255, 0.22);
    border-radius: 12px;
    background:
      radial-gradient(circle at 70% 20%, rgba(255, 218, 118, 0.6), transparent 18%),
      linear-gradient(145deg, #7976e8, #4d50b8);
    box-shadow:
      0 9px 22px rgba(65, 69, 166, 0.28),
      0 1px 0 rgba(255, 255, 255, 0.24) inset;
  }

  .logo-mark-icon {
    font-size: 18px;
  }

  .logo-text {
    color: #20243b;
    font-size: 18px;
    letter-spacing: -0.5px;
  }

  .logo-caption {
    color: #8c91aa;
  }

  .header-context {
    position: relative;
    display: flex;
    flex-direction: column;
    flex: 0 0 132px;
    min-width: 0;
    padding-left: 20px;
  }

  .header-context::before {
    position: absolute;
    top: 4px;
    bottom: 4px;
    left: 0;
    width: 1px;
    content: '';
    background: linear-gradient(to bottom, transparent, rgba(95, 104, 149, 0.24), transparent);
  }

  .context-kicker {
    overflow: hidden;
    color: #969bb2;
    font-size: 9px;
    font-weight: 700;
    line-height: 1.2;
    letter-spacing: 1.4px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .context-title {
    margin-top: 3px;
    overflow: hidden;
    color: #34394f;
    font-size: 14px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .search-input :deep(.el-input__wrapper) {
    height: 42px;
    border-color: rgba(111, 121, 170, 0.1);
    border-radius: 13px;
    background: rgba(229, 233, 244, 0.7);
  }

  .search-input :deep(.el-input__wrapper:hover) {
    background: rgba(242, 244, 253, 0.92);
    border-color: rgba(100, 103, 220, 0.24);
  }

  .search-input :deep(.el-input__wrapper.is-focus) {
    background: rgba(255, 255, 255, 0.94);
    border-color: rgba(100, 103, 220, 0.66);
    box-shadow: 0 0 0 3px rgba(100, 103, 220, 0.09) !important;
  }

  .user-trigger {
    min-height: 42px;
    box-sizing: border-box;
    padding-right: 12px;
    border: 1px solid transparent;
    border-radius: 13px;
  }

  .user-trigger:hover {
    border-color: rgba(111, 121, 170, 0.1);
    background: rgba(232, 235, 247, 0.74);
  }

  .user-avatar {
    border-color: rgba(100, 103, 220, 0.18);
    box-shadow: 0 4px 12px rgba(45, 53, 101, 0.1);
  }
}

@media (min-width: 993px) and (max-width: 1180px) {
  .header-context {
    display: none;
  }
}
</style>
