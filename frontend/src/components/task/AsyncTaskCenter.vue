<template>
  <el-popover
    placement="bottom-end"
    :width="390"
    trigger="click"
    popper-class="async-task-center-popper"
    @show="handleOpen"
  >
    <template #reference>
      <el-badge
        :value="attentionCount > 99 ? '99+' : attentionCount"
        :hidden="attentionCount === 0"
        class="task-center-badge"
      >
        <button
          type="button"
          class="task-center-trigger"
          aria-label="查看后台任务"
          title="后台任务"
        >
          <el-icon><Bell /></el-icon>
        </button>
      </el-badge>
    </template>

    <div class="task-center-panel">
      <div class="task-center-header">
        <div>
          <div class="task-center-title">后台任务</div>
          <div class="task-center-subtitle">
            {{ activeCount > 0 ? `${activeCount} 个任务处理中` : '最近任务状态' }}
          </div>
        </div>
        <el-button
          text
          circle
          :loading="refreshing"
          aria-label="刷新后台任务"
          @click="refreshTasks(false)"
        >
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>

      <div class="task-summary">
        <div class="summary-item summary-item--active">
          <span class="summary-value">{{ activeCount }}</span>
          <span class="summary-label">处理中</span>
        </div>
        <div class="summary-item summary-item--success">
          <span class="summary-value">{{ successCount }}</span>
          <span class="summary-label">已完成</span>
        </div>
        <div class="summary-item summary-item--danger">
          <span class="summary-value">{{ failedCount }}</span>
          <span class="summary-label">需处理</span>
        </div>
      </div>

      <div v-loading="refreshing && tasks.length === 0" class="task-list">
        <div v-if="tasks.length === 0 && !refreshing" class="task-empty">
          暂无后台任务
        </div>

        <div
          v-for="task in tasks"
          :key="task.id"
          class="task-item"
          :class="`task-item--${task.status.toLowerCase()}`"
        >
          <div class="task-status-icon">
            <el-icon v-if="task.status === 'SUCCESS'"><CircleCheck /></el-icon>
            <el-icon v-else-if="task.status === 'DEAD'"><CircleClose /></el-icon>
            <el-icon v-else-if="task.status === 'CANCELLED'"><CircleClose /></el-icon>
            <el-icon v-else-if="task.status === 'FAILED'"><Warning /></el-icon>
            <el-icon v-else class="is-loading"><Loading /></el-icon>
          </div>

          <div class="task-main">
            <div class="task-row">
              <span class="task-name">{{ taskTypeLabel(task.taskType) }}</span>
              <el-tag :type="statusTagType(task.status)" size="small" effect="plain">
                {{ statusLabel(task.status) }}
              </el-tag>
            </div>
            <div class="task-meta">
              <span>{{ formatTaskTime(task) }}</span>
              <span v-if="task.retryCount > 0">
                已重试 {{ task.retryCount }}/{{ task.maxRetries }}
              </span>
            </div>
            <div
              v-if="task.lastError && (task.status === 'FAILED' || task.status === 'DEAD')"
              class="task-error"
              :title="task.lastError"
            >
              {{ task.lastError }}
            </div>
          </div>

          <el-button
            v-if="task.status === 'FAILED' || task.status === 'DEAD'"
            size="small"
            type="primary"
            plain
            :loading="retryingTaskIds.has(task.id)"
            @click="retryTask(task)"
          >
            重试
          </el-button>
        </div>
      </div>

      <div class="task-center-footer">展示最近 {{ tasks.length }} 条任务</div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  Bell,
  CircleCheck,
  CircleClose,
  Loading,
  Refresh,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage, ElNotification } from 'element-plus'
import { listAsyncTasks, retryAsyncTask } from '@/api/task/task'

const props = defineProps<{
  userId: number
}>()

const POLL_INTERVAL_MS = 15000
const SNAPSHOT_PREFIX = 'ASYNC_TASK_STATUS_SNAPSHOT:'
const SEEN_PREFIX = 'ASYNC_TASK_SEEN_TERMINAL:'
const RECOVERY_PROMPT_PREFIX = 'ASYNC_TASK_RECOVERY_PROMPTED:'
const ACTIVE_STATUSES = new Set<API.AsyncTaskDetail['status']>(['PENDING', 'RUNNING', 'FAILED'])
const TERMINAL_STATUSES = new Set<API.AsyncTaskDetail['status']>([
  'SUCCESS',
  'DEAD',
  'CANCELLED',
])

const tasks = ref<API.AsyncTaskDetail[]>([])
const refreshing = ref(false)
const retryingTaskIds = ref(new Set<number>())
const seenTerminalIds = ref(new Set<number>())
let refreshTimer: number | null = null
let refreshController: AbortController | null = null

const storageKey = (prefix: string) => `${prefix}${props.userId}`

const activeCount = computed(() =>
  tasks.value.filter(task => ACTIVE_STATUSES.has(task.status)).length,
)
const successCount = computed(() =>
  tasks.value.filter(task => task.status === 'SUCCESS').length,
)
const failedCount = computed(() =>
  tasks.value.filter(task => task.status === 'DEAD').length,
)
const unreadSuccessCount = computed(() => {
  return tasks.value.filter(
    task => task.status === 'SUCCESS' && !seenTerminalIds.value.has(task.id),
  ).length
})
const attentionCount = computed(
  () => activeCount.value + failedCount.value + unreadSuccessCount.value,
)

const parseStoredJson = <T,>(key: string, fallback: T): T => {
  try {
    const value = localStorage.getItem(key)
    return value ? JSON.parse(value) as T : fallback
  } catch {
    return fallback
  }
}

const loadSnapshot = () =>
  parseStoredJson<Record<string, API.AsyncTaskDetail['status']>>(
    storageKey(SNAPSHOT_PREFIX),
    {},
  )

const saveSnapshot = (items: API.AsyncTaskDetail[]) => {
  const snapshot = Object.fromEntries(
    items.slice(0, 50).map(task => [String(task.id), task.status]),
  )
  localStorage.setItem(storageKey(SNAPSHOT_PREFIX), JSON.stringify(snapshot))
}

const loadSeenTerminalIds = () =>
  new Set(parseStoredJson<number[]>(storageKey(SEEN_PREFIX), []))

const saveSeenTerminalIds = (ids: Set<number>) => {
  seenTerminalIds.value = new Set(ids)
  localStorage.setItem(
    storageKey(SEEN_PREFIX),
    JSON.stringify(Array.from(ids).slice(-100)),
  )
}

const markTerminalTasksSeen = () => {
  const seen = loadSeenTerminalIds()
  tasks.value
    .filter(task => TERMINAL_STATUSES.has(task.status))
    .forEach(task => seen.add(task.id))
  saveSeenTerminalIds(seen)
}

const notifyTaskTransition = (
  task: API.AsyncTaskDetail,
  previousStatus?: API.AsyncTaskDetail['status'],
) => {
  const wasActive = previousStatus
    ? ACTIVE_STATUSES.has(previousStatus)
    : true
  if (!wasActive) {
    return
  }
  if (task.status === 'SUCCESS') {
    ElNotification({
      title: '后台任务已完成',
      message: `${taskTypeLabel(task.taskType)}处理完成`,
      type: 'success',
      duration: 4500,
    })
  } else if (task.status === 'DEAD') {
    ElNotification({
      title: '后台任务需要处理',
      message: `${taskTypeLabel(task.taskType)}失败，可在任务中心重试`,
      type: 'error',
      duration: 6500,
    })
  }
}

const processTaskChanges = (
  items: API.AsyncTaskDetail[],
  previousSnapshot: Record<string, API.AsyncTaskDetail['status']>,
  hadSnapshot: boolean,
) => {
  if (!hadSnapshot) {
    const seen = loadSeenTerminalIds()
    items
      .filter(task => TERMINAL_STATUSES.has(task.status))
      .forEach(task => seen.add(task.id))
    saveSeenTerminalIds(seen)

    const recoveredCount = items.filter(task => ACTIVE_STATUSES.has(task.status)).length
    const recoveryPromptKey = storageKey(RECOVERY_PROMPT_PREFIX)
    if (recoveredCount > 0 && sessionStorage.getItem(recoveryPromptKey) !== '1') {
      sessionStorage.setItem(recoveryPromptKey, '1')
      ElNotification({
        title: '后台任务跟踪已恢复',
        message: `${recoveredCount} 个任务仍在后台处理中`,
        type: 'info',
        duration: 4500,
      })
    }
    return
  }

  items.forEach(task => {
    const previousStatus = previousSnapshot[String(task.id)]
    if (
      TERMINAL_STATUSES.has(task.status)
      && previousStatus !== task.status
    ) {
      notifyTaskTransition(task, previousStatus)
    }
  })
}

const isCancelled = (error: unknown) => {
  if (!error || typeof error !== 'object') {
    return false
  }
  const candidate = error as { name?: string; code?: string }
  return candidate.name === 'CanceledError'
    || candidate.name === 'AbortError'
    || candidate.code === 'ERR_CANCELED'
}

const refreshTasks = async (silent = true) => {
  if (refreshing.value || !props.userId) {
    return
  }
  refreshing.value = true
  refreshController?.abort()
  const controller = new AbortController()
  refreshController = controller
  const snapshotKey = storageKey(SNAPSHOT_PREFIX)
  const hadSnapshot = localStorage.getItem(snapshotKey) !== null
  const previousSnapshot = loadSnapshot()

  try {
    const response = await listAsyncTasks(
      { current: 1, size: 20 },
      controller.signal,
    )
    if (response.code !== 200 || !response.data) {
      throw new Error(response.message || '任务列表加载失败')
    }
    const page = response.data as API.AsyncTaskPage
    const items = Array.isArray(page.records) ? page.records : []
    tasks.value = items
    processTaskChanges(items, previousSnapshot, hadSnapshot)
    saveSnapshot(items)
  } catch (error) {
    if (!silent && !isCancelled(error)) {
      ElMessage.error('后台任务加载失败')
    }
  } finally {
    if (refreshController === controller) {
      refreshController = null
    }
    refreshing.value = false
  }
}

const handleOpen = () => {
  markTerminalTasksSeen()
  void refreshTasks(true)
}

const retryTask = async (task: API.AsyncTaskDetail) => {
  if (retryingTaskIds.value.has(task.id)) {
    return
  }
  retryingTaskIds.value = new Set(retryingTaskIds.value).add(task.id)
  try {
    const response = await retryAsyncTask(task.id)
    if (response.code !== 200) {
      throw new Error(response.message || '任务重试失败')
    }
    ElMessage.success('任务已重新提交')
    await refreshTasks(true)
  } catch {
    ElMessage.error('任务重试失败')
  } finally {
    const next = new Set(retryingTaskIds.value)
    next.delete(task.id)
    retryingTaskIds.value = next
  }
}

const handleVisibilityChange = () => {
  if (document.visibilityState === 'visible') {
    void refreshTasks(true)
  }
}

const startPolling = () => {
  stopPolling()
  seenTerminalIds.value = loadSeenTerminalIds()
  void refreshTasks(true)
  refreshTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible') {
      void refreshTasks(true)
    }
  }, POLL_INTERVAL_MS)
}

const stopPolling = () => {
  if (refreshTimer !== null) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
}

const taskTypeLabel = (taskType: string) => {
  const labels: Record<string, string> = {
    IMAGE_FEATURE: '图片特征',
    FACE_ANALYSIS: '人脸分析',
    VIDEO_PROCESSING: '视频处理',
    GEO_CODING: '地点解析',
    IMAGE_TAG: '图片标签',
  }
  return labels[taskType] || '后台任务'
}

const statusLabel = (status: API.AsyncTaskDetail['status']) => {
  const labels: Record<API.AsyncTaskDetail['status'], string> = {
    PENDING: '等待中',
    RUNNING: '处理中',
    SUCCESS: '已完成',
    FAILED: '待重试',
    DEAD: '失败',
    CANCELLED: '已取消',
  }
  return labels[status]
}

const statusTagType = (status: API.AsyncTaskDetail['status']) => {
  const types: Record<
    API.AsyncTaskDetail['status'],
    'success' | 'warning' | 'info' | 'primary' | 'danger'
  > = {
    PENDING: 'info',
    RUNNING: 'primary',
    SUCCESS: 'success',
    FAILED: 'warning',
    DEAD: 'danger',
    CANCELLED: 'info',
  }
  return types[status]
}

const formatTaskTime = (task: API.AsyncTaskDetail) => {
  const value = task.completedAt || task.updateTime || task.createTime
  if (!value) {
    return '刚刚'
  }
  const time = new Date(value)
  if (Number.isNaN(time.getTime())) {
    return '刚刚'
  }
  return time.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

watch(
  () => props.userId,
  () => {
    refreshController?.abort()
    refreshController = null
    refreshing.value = false
    tasks.value = []
    startPolling()
  },
)

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  startPolling()
})

onUnmounted(() => {
  stopPolling()
  refreshController?.abort()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<style scoped>
:global(.async-task-center-popper) {
  max-width: calc(100vw - 24px) !important;
  box-sizing: border-box;
}

.task-center-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 11px;
  background: #fff;
  color: #6366f1;
  cursor: pointer;
  transition: 0.2s ease;
}

.task-center-trigger:hover {
  border-color: #c7d2fe;
  background: #eef2ff;
}

.task-center-trigger .el-icon {
  font-size: 18px;
}

.task-center-badge :deep(.el-badge__content) {
  border: 2px solid #fff;
  background: #ef4444;
}

.task-center-panel {
  color: #374151;
}

.task-center-header,
.task-row,
.task-meta {
  display: flex;
  align-items: center;
}

.task-center-header {
  justify-content: space-between;
  padding: 4px 2px 12px;
}

.task-center-title {
  font-size: 16px;
  font-weight: 700;
  color: #111827;
}

.task-center-subtitle {
  margin-top: 3px;
  color: #9ca3af;
  font-size: 12px;
}

.task-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 10px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px;
  border-radius: 12px;
  background: #f8fafc;
}

.summary-item--active {
  background: #eef2ff;
  color: #4f46e5;
}

.summary-item--success {
  background: #ecfdf5;
  color: #059669;
}

.summary-item--danger {
  background: #fef2f2;
  color: #dc2626;
}

.summary-value {
  font-size: 18px;
  font-weight: 750;
}

.summary-label {
  font-size: 11px;
}

.task-list {
  max-height: 380px;
  min-height: 90px;
  overflow-y: auto;
}

.task-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 4px;
  border-top: 1px solid #f0f2f5;
}

.task-status-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 9px;
  background: #eef2ff;
  color: #6366f1;
}

.task-item--success .task-status-icon {
  background: #ecfdf5;
  color: #10b981;
}

.task-item--failed .task-status-icon {
  background: #fffbeb;
  color: #f59e0b;
}

.task-item--dead .task-status-icon {
  background: #fef2f2;
  color: #ef4444;
}

.task-main {
  min-width: 0;
  flex: 1;
}

.task-row {
  justify-content: space-between;
  gap: 8px;
}

.task-name {
  overflow: hidden;
  color: #1f2937;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-meta {
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 5px;
  color: #9ca3af;
  font-size: 11px;
}

.task-error {
  margin-top: 5px;
  overflow: hidden;
  color: #dc2626;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 90px;
  color: #9ca3af;
  font-size: 13px;
}

.task-center-footer {
  padding-top: 10px;
  border-top: 1px solid #f0f2f5;
  color: #9ca3af;
  font-size: 11px;
  text-align: center;
}

@media (max-width: 600px) {
  .task-center-trigger {
    width: 34px;
    height: 34px;
  }
}
</style>
