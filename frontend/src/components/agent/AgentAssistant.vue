<template>
  <Teleport to="body">
    <div class="agent-assistant">
      <Transition name="agent-panel">
        <section v-if="isOpen" class="agent-panel" aria-label="云忆相册助手">
          <header class="agent-panel-header">
            <div>
              <p class="agent-eyebrow">Cloud-Album</p>
              <h2>云忆相册助手</h2>
            </div>
            <div class="agent-actions">
              <el-tooltip content="新窗口打开" placement="top">
                <button
                  type="button"
                  class="agent-icon-btn"
                  :disabled="!agentUrl"
                  aria-label="新窗口打开云忆相册助手"
                  @click="openInNewWindow"
                >
                  <el-icon><i-ep-position /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip content="关闭" placement="top">
                <button
                  type="button"
                  class="agent-icon-btn"
                  aria-label="关闭云忆相册助手"
                  @click="isOpen = false"
                >
                  <el-icon><i-ep-close /></el-icon>
                </button>
              </el-tooltip>
            </div>
          </header>

          <iframe
            v-if="agentUrl"
            class="agent-frame"
            :src="agentUrl"
            title="云忆相册助手"
            sandbox="allow-same-origin allow-scripts allow-forms allow-popups allow-downloads"
          />
          <div v-else class="agent-empty">
            <el-icon class="agent-empty-icon"><i-ep-chat-dot-round /></el-icon>
            <h3>还没有配置 Dify 入口</h3>
            <p>在前端环境文件中配置 Dify WebApp 地址后，这里会直接嵌入助手。</p>
            <code>VITE_DIFY_AGENT_URL=http://你的-dify-webapp-url</code>
          </div>
        </section>
      </Transition>

      <el-tooltip content="云忆相册助手" placement="left">
        <button
          type="button"
          class="agent-fab"
          :class="{ 'agent-fab--active': isOpen }"
          aria-label="打开云忆相册助手"
          @click="isOpen = !isOpen"
        >
          <el-icon><i-ep-chat-dot-round /></el-icon>
        </button>
      </el-tooltip>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const isOpen = ref(false)

const agentUrl = computed(() => {
  const url = import.meta.env.VITE_DIFY_AGENT_URL?.trim()
  return url || ''
})

const openInNewWindow = () => {
  if (!agentUrl.value) return
  window.open(agentUrl.value, '_blank', 'noopener,noreferrer')
}
</script>

<style scoped>
.agent-assistant {
  position: fixed;
  right: 24px;
  bottom: 96px;
  z-index: 10080;
}

.agent-fab {
  width: 52px;
  height: 52px;
  border: none;
  border-radius: 50%;
  color: #FFFFFF;
  background: #4F46E5;
  box-shadow: 0 10px 24px rgba(79, 70, 229, 0.3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 23px;
  transition: transform 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
}

.agent-fab:hover,
.agent-fab--active {
  background: #4338CA;
  transform: translateY(-1px);
  box-shadow: 0 14px 30px rgba(67, 56, 202, 0.34);
}

.agent-panel {
  position: absolute;
  right: 0;
  bottom: 68px;
  width: min(420px, calc(100vw - 32px));
  height: min(640px, calc(100vh - 128px));
  overflow: hidden;
  border: 1px solid #E5E7EB;
  border-radius: 12px;
  background: #FFFFFF;
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.18);
  display: flex;
  flex-direction: column;
}

.agent-panel-header {
  height: 64px;
  padding: 0 14px 0 16px;
  border-bottom: 1px solid #E5E7EB;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
}

.agent-eyebrow {
  margin: 0 0 2px;
  font-size: 11px;
  line-height: 1.2;
  color: #6B7280;
}

.agent-panel-header h2 {
  margin: 0;
  font-size: 16px;
  line-height: 1.2;
  color: #111827;
}

.agent-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.agent-icon-btn {
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #6B7280;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}

.agent-icon-btn:hover:not(:disabled) {
  background: #F3F4F6;
  color: #111827;
}

.agent-icon-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.agent-frame {
  width: 100%;
  height: 100%;
  border: none;
  background: #FFFFFF;
}

.agent-empty {
  flex: 1;
  padding: 34px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #4B5563;
}

.agent-empty-icon {
  width: 48px;
  height: 48px;
  margin-bottom: 14px;
  border-radius: 14px;
  background: #EEF2FF;
  color: #4F46E5;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
}

.agent-empty h3 {
  margin: 0 0 8px;
  font-size: 16px;
  color: #111827;
}

.agent-empty p {
  max-width: 280px;
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.6;
}

.agent-empty code {
  max-width: 100%;
  padding: 8px 10px;
  border-radius: 8px;
  background: #F3F4F6;
  color: #374151;
  font-size: 12px;
  white-space: normal;
  word-break: break-all;
}

.agent-panel-enter-active,
.agent-panel-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.agent-panel-enter-from,
.agent-panel-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}

@media (max-width: 640px) {
  .agent-assistant {
    right: 16px;
    bottom: 84px;
  }

  .agent-panel {
    right: -4px;
    width: calc(100vw - 24px);
    height: min(620px, calc(100vh - 112px));
  }
}
</style>
