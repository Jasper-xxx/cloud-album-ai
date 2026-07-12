<template>
  <!--
    .sider-flex-anchor：PC 随菜单占宽；手机 flex 宽度为 0，不占主列。
    抽屉本体 #globalSider 在手机端 position:fixed 且自带宽度，translateX(-100%) 相对自身宽度才能滑入滑出。
    手机端用 Teleport 挂到 body：避免 #basicLayout 等祖先 overflow-x:hidden 裁剪 fixed 抽屉（表现为点汉堡无反应/抽屉不出）。
  -->
  <div class="sider-flex-anchor">
    <Teleport to="body" :disabled="!isCompactLayout">
      <div
        v-if="isCompactLayout && isMobileOpen"
        class="mobile-backdrop"
        @click="closeMobileSidebar"
      />

      <div
        id="globalSider"
        :class="{
          'is-collapsed': isCollapse,
          'is-mobile-open': isMobileOpen
        }"
      >

    <el-menu
      :default-openeds="openeds"
      :default-active="currentRouteName"
      :collapse="isCollapse"
      class="sider-menu"
      mode="vertical"
      @select="handleSelect"
      @open="handleOpen"
      @close="handleClose"
    >
      <!-- 折叠按钮 -->
      <el-menu-item index="0" class="collapse-btn" @click="handleClick">
        <el-icon>
          <Expand v-if="isCollapse" />
          <Fold v-else />
        </el-icon>
        <template #title>
          <span class="collapse-text">收起菜单</span>
        </template>
      </el-menu-item>

      <!-- 图片 -->
      <el-sub-menu index="1">
        <template #title>
          <el-icon><i-ep-picture /></el-icon>
          <span>图片</span>
        </template>
        <el-menu-item index="home">
          <el-icon><i-ep-picture /></el-icon>
          <template #title>全部图片</template>
        </el-menu-item>
        <el-menu-item index="recent">
          <el-icon><i-carbon-recently-viewed /></el-icon>
          <template #title>最近上传</template>
        </el-menu-item>
        <el-menu-item index="brush">
          <el-icon><i-ep-brush /></el-icon>
          <template #title>清理图片</template>
        </el-menu-item>
        <el-menu-item index="ai">
          <el-icon><i-ep-MostlyCloudy /></el-icon>
          <template #title>AI 处理</template>
        </el-menu-item>
        <el-menu-item index="imageSearch">
          <el-icon><i-ep-search /></el-icon>
          <template #title>以图搜图</template>
        </el-menu-item>
      </el-sub-menu>

      <!-- 相册 -->
      <el-sub-menu index="2">
        <template #title>
          <el-icon><i-solar-album-broken /></el-icon>
          <span>相册</span>
        </template>
        <el-menu-item index="album">
          <el-icon><i-solar-album-broken /></el-icon>
          <template #title>相册</template>
        </el-menu-item>
        <el-menu-item index="person">
          <el-icon><i-ep-user /></el-icon>
          <template #title>人物</template>
        </el-menu-item>
        <el-menu-item index="location">
          <el-icon><i-ep-location /></el-icon>
          <template #title>地点</template>
        </el-menu-item>
        <el-menu-item index="model">
          <el-icon><i-ep-iphone /></el-icon>
          <template #title>设备</template>
        </el-menu-item>
      </el-sub-menu>

      <!-- 可视化 -->
      <el-sub-menu index="3">
        <template #title>
          <el-icon><i-ep-DataAnalysis /></el-icon>
          <span>可视化</span>
        </template>
        <el-menu-item index="fileVisual">
          <el-icon><i-ep-files /></el-icon>
          <template #title>文件类型</template>
        </el-menu-item>
        <el-menu-item index="locationVisual">
          <el-icon><i-ep-location /></el-icon>
          <template #title>照片足迹</template>
        </el-menu-item>
        <el-menu-item index="tagVisual">
          <el-icon><i-ep-PriceTag /></el-icon>
          <template #title>图片标签</template>
        </el-menu-item>
      </el-sub-menu>

      <!-- 回收站 -->
      <el-menu-item index="recycle">
        <el-icon><i-ep-delete /></el-icon>
        <template #title>回收站</template>
      </el-menu-item>

      <!-- 操作记录 -->
      <el-menu-item index="record">
        <el-icon><i-ep-Document /></el-icon>
        <template #title>操作记录</template>
      </el-menu-item>
    </el-menu>

        <div v-if="!isCollapse" class="sider-footer">
          <div class="sider-footer-orbit" aria-hidden="true">
            <span></span>
          </div>
          <div class="sider-footer-copy">
            <strong>私人影像空间</strong>
            <span><i class="status-dot"></i>云端服务已连接</span>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Expand, Fold } from '@element-plus/icons-vue'
import { isLayoutCompact } from '@/utils/layoutBreakpoint'

const router = useRouter()
const route = useRoute()
const currentRouteName = computed(() => route.name as string)

const openeds = ref([])
/** 与 CSS 断点一致，供 Teleport 使用（须响应 resize） */
const isCompactLayout = ref(
  typeof window !== 'undefined' ? isLayoutCompact() : false
)
/** PC端侧边栏折叠状态（图标模式 / 展开模式） */
const isCollapse = ref(false)
/** 手机端抽屉是否打开 */
const isMobileOpen = ref(false)

/**
 * 手机端：关闭侧边栏抽屉
 */
const closeMobileSidebar = () => {
  isMobileOpen.value = false
}

/**
 * 菜单项选中：跳转路由，手机端同时关闭侧边栏
 */
const handleSelect = (key: string, keyPath: string[]) => {
  if (key !== '0') {
    router.push({ name: key })
    if (isLayoutCompact()) {
      closeMobileSidebar()
    }
  }
}

/**
 * PC端折叠按钮 / 手机端：切换侧边栏状态
 */
const handleClick = (event: any) => {
  event
  if (isLayoutCompact()) {
    isMobileOpen.value = !isMobileOpen.value
  } else {
    isCollapse.value = !isCollapse.value
  }
}

/**
 * 屏幕尺寸变化时自动处理：
 * - 切换到PC：关闭手机抽屉，取消强制折叠
 * - 切换到手机：关闭PC折叠状态（手机端用抽屉模式）
 */
const handleResize = () => {
  const compact = isLayoutCompact()
  isCompactLayout.value = compact
  if (!compact) {
    isMobileOpen.value = false
  } else {
    /* 抽屉内始终用完整菜单宽，避免 PC 折叠态带到手机后像「抽屉没打开」 */
    isCollapse.value = false
  }
}

/** 由 BasicLayout 通过 ref 直接调用（避免部分 WebView 下 mitt 不可靠） */
function toggleMobileDrawer() {
  isMobileOpen.value = !isMobileOpen.value
}

defineExpose({ toggleMobileDrawer })

/**
 * 子菜单打开时的处理
 */
const handleOpen = (_key: string, _keyPath: string[]) => {}

/**
 * 子菜单关闭时的处理
 */
const handleClose = (_key: string, _keyPath: string[]) => {}

onMounted(() => {
  handleResize()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
/* PC：占位宽度随侧栏；手机：不占主列宽（见下方 @media） */
.sider-flex-anchor {
  flex-shrink: 0;
  align-self: stretch;
  height: 100%;
  display: block;
}

.sider-footer {
  display: none;
}

#globalSider {
  height: 100%;
  background: #111827;
  flex-shrink: 0;
  transition: width 0.25s ease;
  overflow: hidden;
  border-right: none;
}

/* Element Plus Menu 深色变量覆盖 */
.sider-menu {
  height: 100%;
  border-right: none !important;
  background: transparent !important;
  overflow-y: auto;
  overflow-x: hidden;

  --el-menu-bg-color: transparent;
  --el-menu-text-color: #9CA3AF;
  --el-menu-active-color: #FFFFFF;
  --el-menu-hover-bg-color: rgba(99, 102, 241, 0.12);
  --el-menu-hover-text-color: #F3F4F6;
  --el-menu-item-height: 42px;
  --el-menu-sub-item-height: 40px;
  --el-sub-menu-title-height: 44px;
  --el-menu-item-font-size: 13px;
  --el-menu-icon-width: 20px;
  --el-menu-base-level-padding: 16px;
}

/* 展开时宽度 */
.sider-menu:not(.el-menu--collapse) {
  width: 200px;
}

/* 滚动条美化 */
.sider-menu::-webkit-scrollbar {
  width: 3px;
}
.sider-menu::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 3px;
}

/* 折叠按钮 */
:deep(.collapse-btn) {
  height: 44px;
  color: #6B7280 !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06) !important;
  margin-bottom: 4px;
}

:deep(.collapse-btn:hover) {
  background: rgba(255, 255, 255, 0.05) !important;
  color: #D1D5DB !important;
}

/* Sub-menu 标题（一级分组） */
:deep(.el-sub-menu__title) {
  color: #9CA3AF !important;
  font-size: 13px !important;
  font-weight: 500 !important;
  padding-left: 16px !important;
  height: 44px !important;
  line-height: 44px !important;
  border-radius: 6px !important;
  margin: 2px 8px !important;
  transition: background 0.15s, color 0.15s !important;
}

:deep(.el-sub-menu__title:hover) {
  background: rgba(99, 102, 241, 0.1) !important;
  color: #E5E7EB !important;
}

:deep(.el-sub-menu__title .el-icon) {
  color: #6B7280 !important;
  font-size: 15px !important;
  margin-right: 10px !important;
}

:deep(.el-sub-menu.is-opened > .el-sub-menu__title) {
  color: #E5E7EB !important;
}

:deep(.el-sub-menu.is-opened > .el-sub-menu__title .el-icon) {
  color: #A5B4FC !important;
}

/* Sub-menu 箭头 */
:deep(.el-sub-menu__icon-arrow) {
  color: #4B5563 !important;
}

/* 内层菜单背景 */
:deep(.el-menu--inline) {
  background: rgba(0, 0, 0, 0.15) !important;
  border-radius: 6px !important;
  margin: 2px 8px !important;
  padding: 4px 0 !important;
}

/* 二级菜单项 */
:deep(.el-menu--inline .el-menu-item) {
  height: 38px !important;
  line-height: 38px !important;
  padding-left: 36px !important;
  border-radius: 6px !important;
  margin: 1px 6px !important;
  font-size: 13px !important;
  color: #9CA3AF !important;
  transition: background 0.15s, color 0.15s !important;
}

:deep(.el-menu--inline .el-menu-item:hover) {
  background: rgba(99, 102, 241, 0.12) !important;
  color: #F3F4F6 !important;
}

:deep(.el-menu--inline .el-menu-item .el-icon) {
  font-size: 14px !important;
  margin-right: 8px !important;
  color: #6B7280 !important;
}

:deep(.el-menu--inline .el-menu-item:hover .el-icon) {
  color: #A5B4FC !important;
}

/* 激活的二级菜单项 */
:deep(.el-menu--inline .el-menu-item.is-active) {
  background: rgba(99, 102, 241, 0.2) !important;
  color: #FFFFFF !important;
  font-weight: 500 !important;
  border-left: 3px solid #6366F1 !important;
  padding-left: 33px !important;
}

:deep(.el-menu--inline .el-menu-item.is-active .el-icon) {
  color: #818CF8 !important;
}

/* 顶层独立菜单项（回收站、操作记录） */
:deep(.el-menu > .el-menu-item) {
  height: 42px !important;
  line-height: 42px !important;
  color: #9CA3AF !important;
  font-size: 13px !important;
  border-radius: 6px !important;
  margin: 2px 8px !important;
  padding-left: 16px !important;
  transition: background 0.15s, color 0.15s !important;
}

:deep(.el-menu > .el-menu-item:hover) {
  background: rgba(99, 102, 241, 0.1) !important;
  color: #E5E7EB !important;
}

:deep(.el-menu > .el-menu-item .el-icon) {
  font-size: 15px !important;
  color: #6B7280 !important;
  margin-right: 10px !important;
}

:deep(.el-menu > .el-menu-item:hover .el-icon) {
  color: #A5B4FC !important;
}

:deep(.el-menu > .el-menu-item.is-active) {
  background: rgba(99, 102, 241, 0.2) !important;
  color: #FFFFFF !important;
  font-weight: 500 !important;
  border-left: 3px solid #6366F1 !important;
  padding-left: 13px !important;
}

:deep(.el-menu > .el-menu-item.is-active .el-icon) {
  color: #818CF8 !important;
}

/* 折叠模式下 tooltip 主题 */
:deep(.el-tooltip__trigger) {
  width: 100%;
}

/* ── 手机端适配（≤ 992px，与 layoutBreakpoint 一致）────────── */
@media (max-width: 992px) {
  /*
   * 外层占位：flex 宽度为 0，主内容占满屏；内层 #globalSider 为 fixed + 实宽，滑入滑出才有效。
   * （若给 #globalSider 设 width:0，则 translateX(-100%) 为 0，且 overflow 会裁掉菜单。）
   */
  .sider-flex-anchor {
    flex: 0 0 0 !important;
    width: 0 !important;
    min-width: 0 !important;
    overflow: visible !important;
    position: relative;
    z-index: 0;
  }

  #globalSider {
    position: fixed !important;
    top: var(--mobile-header-total, calc(60px + env(safe-area-inset-top, 0px)));
    left: 0;
    width: min(82vw, 260px) !important;
    min-width: 0 !important;
    max-width: min(82vw, 260px) !important;
    height: calc(100% - var(--mobile-header-total, calc(60px + env(safe-area-inset-top, 0px)))) !important;
    z-index: 10050;
    transform: translateX(-100%);
    transition: transform 0.28s cubic-bezier(0.4, 0, 0.2, 1);
    overflow-x: hidden !important;
    overflow-y: auto !important;
    -webkit-overflow-scrolling: touch;
    border-right: 1px solid rgba(255, 255, 255, 0.06);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.12);
  }

  #globalSider.is-mobile-open {
    transform: translateX(0);
  }

  .sider-menu:not(.el-menu--collapse) {
    width: 100% !important;
    min-height: 100%;
  }
}

/* 遮罩层（点击关闭侧边栏） */
/* 仅 v-if 挂载时存在，勿再写 display:none + @media 强行 block，避免误伤其它命中逻辑 */
.mobile-backdrop {
  position: fixed;
  top: var(--mobile-header-total, calc(60px + env(safe-area-inset-top, 0px)));
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 10040;
  backdrop-filter: blur(1px);
  animation: backdropFadeIn 0.2s ease;
}

@keyframes backdropFadeIn {
  from { opacity: 0; }
  to   { opacity: 1; }
}

@media (min-width: 993px) {
  .sider-flex-anchor {
    margin-left: 18px;
  }

  #globalSider {
    position: relative;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    border: 1px solid rgba(151, 161, 221, 0.11);
    border-radius: 22px;
    background:
      radial-gradient(circle at 18% 5%, rgba(125, 112, 235, 0.2), transparent 28%),
      radial-gradient(circle at 82% 95%, rgba(35, 112, 156, 0.1), transparent 30%),
      linear-gradient(180deg, #171a36 0%, #10142b 56%, #0c1024 100%);
    box-shadow:
      12px 18px 42px rgba(27, 32, 70, 0.18),
      0 1px 0 rgba(255, 255, 255, 0.05) inset;
  }

  #globalSider::after {
    position: absolute;
    right: 0;
    top: 8%;
    width: 1px;
    height: 84%;
    content: '';
    background: linear-gradient(
      to bottom,
      transparent,
      rgba(146, 153, 235, 0.22),
      transparent
    );
  }

  .sider-menu {
    flex: 1;
    height: auto;
    min-height: 0;
    padding: 8px 6px 0;
  }

  .sider-menu:not(.el-menu--collapse) {
    width: 224px;
  }

  :deep(.collapse-btn) {
    margin: 0 6px 8px !important;
    border-radius: 10px !important;
    border-bottom-color: rgba(182, 188, 235, 0.08) !important;
  }

  :deep(.el-sub-menu__title),
  :deep(.el-menu > .el-menu-item) {
    margin-right: 6px !important;
    margin-left: 6px !important;
    border-radius: 10px !important;
  }

  :deep(.el-sub-menu__title:hover),
  :deep(.el-menu > .el-menu-item:hover) {
    background: rgba(121, 118, 226, 0.12) !important;
  }

  :deep(.el-menu--inline) {
    margin-right: 6px !important;
    margin-left: 6px !important;
    border: 1px solid rgba(151, 161, 221, 0.05);
    border-radius: 10px !important;
    background: rgba(4, 7, 22, 0.22) !important;
  }

  :deep(.el-menu--inline .el-menu-item.is-active),
  :deep(.el-menu > .el-menu-item.is-active) {
    border-left-color: transparent !important;
    border-radius: 9px !important;
    background: linear-gradient(
      90deg,
      rgba(116, 109, 230, 0.38),
      rgba(91, 97, 206, 0.14)
    ) !important;
    box-shadow:
      0 7px 20px rgba(4, 7, 26, 0.16),
      0 1px 0 rgba(255, 255, 255, 0.05) inset;
  }

  :deep(.el-menu--inline .el-menu-item.is-active .el-icon),
  :deep(.el-menu > .el-menu-item.is-active .el-icon) {
    color: #b2b4ff !important;
  }

  .sider-footer {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 10px 12px 14px;
    padding: 13px 12px;
    overflow: hidden;
    border: 1px solid rgba(158, 164, 225, 0.1);
    border-radius: 14px;
    background:
      linear-gradient(135deg, rgba(126, 115, 230, 0.12), rgba(255, 255, 255, 0.035));
  }

  .sider-footer::after {
    position: absolute;
    top: -28px;
    right: -24px;
    width: 72px;
    height: 72px;
    border-radius: 50%;
    content: '';
    background: rgba(106, 99, 216, 0.1);
  }

  .sider-footer-orbit {
    position: relative;
    display: grid;
    width: 30px;
    height: 30px;
    flex: 0 0 30px;
    place-items: center;
    border: 1px solid rgba(177, 181, 247, 0.22);
    border-radius: 50%;
  }

  .sider-footer-orbit::before {
    width: 18px;
    height: 18px;
    border: 1px dashed rgba(177, 181, 247, 0.38);
    border-radius: 50%;
    content: '';
  }

  .sider-footer-orbit span {
    position: absolute;
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #e9c86b;
    box-shadow: 0 0 12px rgba(233, 200, 107, 0.72);
  }

  .sider-footer-copy {
    position: relative;
    z-index: 1;
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;
  }

  .sider-footer-copy strong {
    overflow: hidden;
    color: #e7e9f8;
    font-size: 12px;
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sider-footer-copy span {
    display: flex;
    align-items: center;
    gap: 5px;
    color: #777e9f;
    font-size: 10px;
    white-space: nowrap;
  }

  .status-dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #69c497;
    box-shadow: 0 0 8px rgba(105, 196, 151, 0.6);
  }
}
</style>
