<template>
  <div id="basicLayout">
    <GlobalHeader :showSearh="true" @toggle-sidebar="onToggleSidebar" />
    <div class="GlobalBody">
      <GlobalSider ref="siderRef" />
      <div class="GlobalContent">
        <div class="content-glow content-glow--top" aria-hidden="true"></div>
        <div class="content-glow content-glow--bottom" aria-hidden="true"></div>
        <main class="route-surface" :data-route="String(route.name || '')">
          <router-view></router-view>
        </main>
        <div class="GlobalUpload">
          <FileUpload />
        </div>
        <AgentAssistant />
      </div>
    </div>
  </div>
</template>


<script setup lang="ts">
import { defineAsyncComponent, ref } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/layouts/GlobalHeader.vue'
import GlobalSider from "@/layouts/GlobalSider.vue";

const route = useRoute()
const siderRef = ref<InstanceType<typeof GlobalSider> | null>(null)
const FileUpload = defineAsyncComponent(() => import('@/components/file/FileUpload.vue'))
const AgentAssistant = defineAsyncComponent(() => import('@/components/agent/AgentAssistant.vue'))

function onToggleSidebar() {
  siderRef.value?.toggleMobileDrawer?.()
}
</script>

<style scoped>
#basicLayout {
  position: relative;
  width: 100%;
  height: 100%;
  user-select: none;
  --app-header-height: 60px;
}

.GlobalBody {
  position: fixed;
  top: var(--app-header-height);
  height: calc(100% - var(--app-header-height));
  width: 100%;
  display: flex;
  /* flex 子项默认可缩小，避免子级无法形成内部滚动 */
  min-height: 0;
  overflow: hidden;
}

.GlobalContent {
  flex: 1;
  min-width: 0;
  min-height: 0;
  height: 100%;
  position: relative;
  background: #F9FAFB;
}

.route-surface {
  position: relative;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}

.content-glow {
  display: none;
}

.GlobalUpload {
  position: absolute;
  right: 20px;
  bottom: 20px;
}

@media (min-width: 993px) {
  #basicLayout {
    --app-header-height: 72px;
    background: #e9edf6;
  }

  .GlobalBody {
    padding: 16px 18px 18px 0;
    box-sizing: border-box;
    gap: 16px;
    background:
      linear-gradient(180deg, #e9edf6 0%, #e5eaf4 100%);
  }

  .GlobalContent {
    isolation: isolate;
    overflow: hidden;
    padding: 0;
    border: 1px solid rgba(122, 132, 177, 0.16);
    border-radius: 24px;
    background:
      linear-gradient(145deg, rgba(249, 250, 254, 0.92), rgba(239, 242, 249, 0.88));
    box-shadow:
      0 24px 65px rgba(36, 43, 75, 0.12),
      0 1px 0 rgba(255, 255, 255, 0.86) inset;
  }

  .GlobalContent::before {
    position: absolute;
    z-index: -1;
    inset: 0;
    content: '';
    pointer-events: none;
    opacity: 0.56;
    background-image:
      linear-gradient(rgba(88, 98, 145, 0.028) 1px, transparent 1px),
      linear-gradient(90deg, rgba(88, 98, 145, 0.028) 1px, transparent 1px);
    background-size: 32px 32px;
    mask-image: linear-gradient(to bottom, black, transparent 88%);
  }

  .route-surface {
    z-index: 1;
    overflow: hidden;
  }

  .content-glow {
    position: absolute;
    z-index: 0;
    display: block;
    width: 420px;
    height: 420px;
    border-radius: 50%;
    pointer-events: none;
    filter: blur(2px);
  }

  .content-glow--top {
    top: -260px;
    right: -110px;
    background: radial-gradient(circle, rgba(112, 105, 225, 0.19), transparent 68%);
  }

  .content-glow--bottom {
    left: -240px;
    bottom: -300px;
    background: radial-gradient(circle, rgba(67, 155, 199, 0.13), transparent 70%);
  }

  .GlobalUpload {
    z-index: 20;
    right: 24px;
    bottom: 24px;
    filter: drop-shadow(0 14px 24px rgba(46, 54, 110, 0.2));
  }
}
</style>
