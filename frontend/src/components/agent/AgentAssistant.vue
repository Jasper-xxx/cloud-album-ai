<template>
  <Teleport to="body">
    <div class="agent-assistant">
      <Transition name="agent-panel">
        <section v-if="hasOpened" v-show="isOpen" class="agent-panel" aria-label="云忆相册助手">
          <header class="agent-panel-header">
            <div>
              <p class="agent-eyebrow">Cloud-Album</p>
              <h2>云忆相册助手</h2>
            </div>
            <div class="agent-actions">
              <el-tooltip content="配置助手地址" placement="top">
                <button
                  type="button"
                  class="agent-icon-btn"
                  aria-label="配置云忆相册助手地址"
                  @click="showConfiguration"
                >
                  <el-icon><i-ep-setting /></el-icon>
                </button>
              </el-tooltip>
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

          <nav class="agent-tabs" aria-label="助手视图">
            <button type="button" :aria-pressed="activeTab === 'chat'" @click="activeTab = 'chat'">对话</button>
            <button type="button" :aria-pressed="activeTab === 'activity'" @click="activeTab = 'activity'">操作记录与照片</button>
          </nav>
          <p v-if="activeTab === 'chat' && agentUrl && !isConfiguring" class="agent-chat-help">
            左上角菜单可切换历史会话；输入框附件按钮可添加一张图片。
          </p>
          <AgentActivityPanel v-if="activeTab === 'activity'" />

          <iframe
            v-if="agentUrl && !isConfiguring"
            v-show="activeTab === 'chat'"
            class="agent-frame"
            :src="agentUrl"
            title="云忆相册助手"
            sandbox="allow-same-origin allow-scripts allow-forms allow-popups allow-downloads"
          />
          <div v-else-if="activeTab === 'chat'" class="agent-empty">
            <el-icon class="agent-empty-icon"><i-ep-chat-dot-round /></el-icon>
            <h3>{{ agentUrl ? '配置 Dify 助手地址' : '还没有配置 Dify 入口' }}</h3>
            <p>粘贴 Dify 中已发布应用的 WebApp 地址，例如 <code>http://localhost/chat/应用标识</code>。</p>
            <form class="agent-config-form" @submit.prevent="saveAgentUrl">
              <label for="dify-agent-url">Dify WebApp 地址</label>
              <input
                id="dify-agent-url"
                v-model="draftAgentUrl"
                type="url"
                inputmode="url"
                autocomplete="url"
                placeholder="http://localhost/chat/..."
                aria-describedby="dify-agent-url-help"
              />
              <p v-if="urlError" class="agent-config-error" role="alert">{{ urlError }}</p>
              <div class="agent-config-actions">
                <button type="submit" class="agent-config-save">保存并打开</button>
                <button
                  v-if="agentUrl"
                  type="button"
                  class="agent-config-cancel"
                  @click="cancelConfiguration"
                >
                  取消
                </button>
              </div>
            </form>
            <p id="dify-agent-url-help" class="agent-config-help">
              地址仅保存在当前浏览器。也可以在仓库根目录的 <code>.env</code> 中配置
              <code>VITE_DIFY_AGENT_URL</code>。
            </p>
          </div>
        </section>
      </Transition>

      <el-tooltip content="云忆相册助手" placement="left">
        <button
          type="button"
          class="agent-fab"
          :class="{ 'agent-fab--active': isOpen }"
          aria-label="打开云忆相册助手"
          @click="togglePanel"
        >
          <el-icon><i-ep-chat-dot-round /></el-icon>
        </button>
      </el-tooltip>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import AgentActivityPanel from './AgentActivityPanel.vue'

const AGENT_URL_STORAGE_KEY = 'cloud-album:dify-agent-url'
const isOpen = ref(false)
const hasOpened = ref(false)
const activeTab = ref<'chat' | 'activity'>('chat')
const isConfiguring = ref(false)
const urlError = ref('')

const normalizeAgentUrl = (value: unknown) => {
  const text = String(value || '').trim()
  if (!text) return ''

  try {
    const url = new URL(text)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return ''
    // Dify /chatbot is the compact embed; /chat includes the conversation list.
    url.pathname = url.pathname.replace(/\/chatbot\/([^/]+)\/?$/, '/chat/$1')
    return url.toString()
  } catch {
    return ''
  }
}

const environmentAgentUrl = normalizeAgentUrl(import.meta.env.VITE_DIFY_AGENT_URL)

const readStoredAgentUrl = () => {
  try {
    return normalizeAgentUrl(window.localStorage.getItem(AGENT_URL_STORAGE_KEY))
  } catch {
    return ''
  }
}

const storedAgentUrl = ref(readStoredAgentUrl())
const draftAgentUrl = ref(storedAgentUrl.value || environmentAgentUrl)

const agentUrl = computed(() => {
  return storedAgentUrl.value || environmentAgentUrl
})

const togglePanel = () => {
  hasOpened.value = true
  isOpen.value = !isOpen.value
  if (isOpen.value && !agentUrl.value) {
    isConfiguring.value = true
  }
}

const showConfiguration = () => {
  activeTab.value = 'chat'
  draftAgentUrl.value = agentUrl.value
  urlError.value = ''
  isConfiguring.value = true
}

const cancelConfiguration = () => {
  draftAgentUrl.value = agentUrl.value
  urlError.value = ''
  isConfiguring.value = false
}

const saveAgentUrl = () => {
  const normalized = normalizeAgentUrl(draftAgentUrl.value)
  if (!normalized) {
    urlError.value = '请输入以 http:// 或 https:// 开头的完整 WebApp 地址。'
    return
  }

  try {
    window.localStorage.setItem(AGENT_URL_STORAGE_KEY, normalized)
  } catch {
    urlError.value = '浏览器无法保存该地址，请改用根目录 .env 配置。'
    return
  }

  storedAgentUrl.value = normalized
  draftAgentUrl.value = normalized
  urlError.value = ''
  isConfiguring.value = false
}

const openInNewWindow = () => {
  if (!agentUrl.value) return
  window.open(agentUrl.value, '_blank', 'noopener,noreferrer')
}
</script>

<style scoped>
.agent-tabs{display:flex;gap:8px;padding:8px 16px;border-bottom:1px solid #e5e7eb}
.agent-tabs button{border:0;border-radius:6px;padding:7px 10px;background:#f3f4f6;cursor:pointer;color:#374151}
.agent-tabs button[aria-pressed="true"]{background:#e8e7ff;color:#3730a3}
.agent-chat-help{margin:0;padding:6px 14px;color:#6b7280;font-size:12px;flex-shrink:0}
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
  width: min(560px, calc(100vw - 32px));
  height: min(760px, calc(100vh - 164px));
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
  flex: 1;
  min-height: 0;
  width: 100%;
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

.agent-config-form {
  width: min(100%, 320px);
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  text-align: left;
}

.agent-config-form label {
  color: #374151;
  font-size: 12px;
  font-weight: 600;
}

.agent-config-form input {
  width: 100%;
  height: 38px;
  box-sizing: border-box;
  padding: 0 11px;
  border: 1px solid #D1D5DB;
  border-radius: 8px;
  outline: none;
  color: #111827;
  background: #FFFFFF;
  font: inherit;
}

.agent-config-form input:focus {
  border-color: #4F46E5;
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.12);
}

.agent-config-actions {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}

.agent-config-save,
.agent-config-cancel {
  min-height: 36px;
  padding: 0 14px;
  border-radius: 8px;
  cursor: pointer;
  font: inherit;
}

.agent-config-save {
  border: 1px solid #4F46E5;
  color: #FFFFFF;
  background: #4F46E5;
}

.agent-config-cancel {
  border: 1px solid #D1D5DB;
  color: #374151;
  background: #FFFFFF;
}

.agent-empty .agent-config-error {
  max-width: none;
  margin: 0;
  color: #DC2626;
  font-size: 12px;
}

.agent-empty .agent-config-help {
  max-width: 320px;
  margin: 14px 0 0;
  color: #6B7280;
  font-size: 12px;
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
