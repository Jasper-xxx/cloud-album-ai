<template>
  <div class="capacity-container">
    <!-- 容量概览卡片 -->
    <el-card shadow="hover" class="capacity-card">
      <div class="capacity-header">
        <h2>存储容量管理</h2>
        <!-- 已达上限时禁用按钮 -->
        <el-button type="primary" :disabled="!canExpand" @click="openDialog">
          {{ canExpand ? '立即扩容' : '已达存储上限' }}
        </el-button>
      </div>

      <!-- 容量进度条（相对于套餐上限） -->
      <el-progress :percentage="usagePercentage" :color="customColors" :stroke-width="16" :format="formatProgress" />

      <!-- 容量统计 -->
      <div class="capacity-stats">
        <div class="stat-item">
          <span class="stat-label">总容量</span>
          <span class="stat-value">{{ formatFileSize(userInfo.totalSpace) }}</span>
        </div>
        <div class="stat-item">
          <span class="stat-label">已使用</span>
          <span class="stat-value text-danger">{{ formatFileSize(userInfo.usedSpace) }}</span>
        </div>
        <div class="stat-item">
          <span class="stat-label">剩余</span>
          <span class="stat-value text-success">{{ formatFileSize(remainingCapacity) }}</span>
        </div>
      </div>

      <!-- 套餐容量上限提示 -->
      <div class="tier-info">
        <el-tag :type="tierTagType" size="small">{{ tierLabel }}</el-tag>
        <span class="tier-cap-tip">
          套餐上限：{{ currentCapGB }} GB &nbsp;|&nbsp;
          已用套餐容量：{{ tierUsagePercent }}%
          <template v-if="canExpand">
            &nbsp;（最多还可扩容 {{ maxExpandGB }} GB）
          </template>
        </span>
      </div>
    </el-card>

    <!-- 扩容对话框 -->
    <el-dialog v-model="showDialog" title="存储扩容" width="360px" :append-to-body="true">
      <el-form :model="form" label-width="80px">
        <el-form-item label="扩容大小" required>
          <el-input-number
            v-model="form.addCapacity"
            :min="1"
            :max="Math.max(1, maxExpandGB)"
            :step="1"
            :precision="0"
            controls-position="right"
            :disabled="!canExpand"
          />
          <span class="unit">GB</span>
        </el-form-item>
        <div class="expand-hint">
          当前：{{ formatFileSize(userInfo.totalSpace) }} / 上限 {{ currentCapGB }} GB
          （还可扩容 {{ maxExpandGB }} GB）
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="handleUpgrade">确认扩容</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getUserInfo, handleAddUserStorage } from '@/api/user/index'
import { storage } from '@/utils/storage'
import $bus from '@/utils/bus.ts'

// ─── 各身份容量上限（字节） ───────────────────────────────────────────────────
const GB = 1024 * 1024 * 1024
const TIER_CAPS: Record<string, number> = {
  normal: 5 * GB,
  vip:    20 * GB,
  svip:   50 * GB,
}

interface CapacityForm {
  addCapacity: number
}

const userInfo = ref(<API.UserInfo>{
  userId: 1000000001,
  userName: "鹏",
  account: "xzp",
  email: "1014537454@qq.com",
  createTime: "2025-02-20T15:18:26",
  updateTime: "2025-03-04T22:31:21",
  profile: "您好",
  avatarUrl: "",
  totalSpace: 5 * 1024 * 1024 * 1024,
  usedSpace: 32988616,
  accountStatus: "normal",
  membershipDays: 0
})

const selectUserInfo = async () => {
  const token = localStorage.getItem('token')
  if (token) {
    const res = await getUserInfo()
    if (res.code == 200) {
      userInfo.value = res.data
    }
  }
}

onMounted(() => {
  const Info = storage.get<API.UserInfo>('USER_INFO')
  if (Info) {
    userInfo.value = Info
  } else {
    selectUserInfo()
  }
  // 监听会员身份变更事件（来自 MemberCenter.vue），同步最新 userInfo
  $bus.on('userInfoUpdated', handleUserInfoUpdated)
})

onUnmounted(() => {
  $bus.off('userInfoUpdated', handleUserInfoUpdated)
})

const handleUserInfoUpdated = (newInfo: API.UserInfo) => {
  userInfo.value = newInfo
}

// ─── 响应式数据 ────────────────────────────────────────────────────────────────
const showDialog = ref(false)
const form = ref<CapacityForm>({ addCapacity: 1 })

// ─── 套餐上限相关计算 ──────────────────────────────────────────────────────────

// 当前身份的套餐容量上限（字节）
const currentCapBytes = computed(() => {
  const status = (userInfo.value.accountStatus || 'normal').toLowerCase()
  return TIER_CAPS[status] ?? TIER_CAPS['normal']
})

// 套餐上限 GB 数（整数显示）
const currentCapGB = computed(() => currentCapBytes.value / GB)

// 还可扩容的 GB 数（向下取整，保证加上后不超上限）
const maxExpandGB = computed(() =>
  Math.max(0, Math.floor((currentCapBytes.value - userInfo.value.totalSpace) / GB))
)

// 是否还可以扩容
const canExpand = computed(() => maxExpandGB.value > 0)

// 套餐容量已用百分比（totalSpace / cap）
const tierUsagePercent = computed(() =>
  Math.min(100, Math.round((userInfo.value.totalSpace / currentCapBytes.value) * 100))
)

// 套餐标签类型与文字
const tierTagType = computed(() => {
  const s = userInfo.value.accountStatus?.toLowerCase()
  if (s === 'svip') return 'danger'
  if (s === 'vip') return 'warning'
  return 'info'
})
const tierLabel = computed(() => {
  const s = userInfo.value.accountStatus?.toLowerCase()
  if (s === 'svip') return 'SVIP 会员'
  if (s === 'vip') return 'VIP 会员'
  return '普通用户'
})

// ─── 基础计算属性 ──────────────────────────────────────────────────────────────
const remainingCapacity = computed(() => userInfo.value.totalSpace - userInfo.value.usedSpace)
const usagePercentage = computed(() =>
  Math.min(100, Math.round((userInfo.value.usedSpace / userInfo.value.totalSpace) * 100))
)

const customColors = computed(() => [
  { color: '#67c23a', percentage: 20 },
  { color: '#e6a23c', percentage: 50 },
  { color: '#f56c6c', percentage: 80 }
])

// ─── 工具方法 ──────────────────────────────────────────────────────────────────
const formatProgress = () => `${usagePercentage.value}% 已使用`

const formatFileSize = (bytes: number) => {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = Math.abs(bytes)
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(1).replace(/\.0$/, '')} ${units[unitIndex]}`
}

// 打开对话框时重置表单，并确保 addCapacity 不超过可扩容上限
const openDialog = () => {
  if (!canExpand.value) return
  form.value.addCapacity = Math.min(1, maxExpandGB.value)
  showDialog.value = true
}

// 当 maxExpandGB 变化时（如切换账号），确保 form 值在合法范围内
watch(maxExpandGB, (max) => {
  if (form.value.addCapacity > max) {
    form.value.addCapacity = max > 0 ? max : 1
  }
  // 当达到上限时，确保表单值为1（但实际会被禁用）
  if (max === 0) {
    form.value.addCapacity = 1
  }
})

// ─── 扩容逻辑 ──────────────────────────────────────────────────────────────────
const handleUpgrade = async () => {
  if (!form.value.addCapacity || form.value.addCapacity <= 0) {
    ElMessage.error('请输入有效的扩容容量')
    return
  }

  const addBytes = form.value.addCapacity * GB

  // 前端二次校验：扩容后不得超过当前套餐上限
  if (userInfo.value.totalSpace + addBytes > currentCapBytes.value) {
    ElMessage.warning(`扩容后将超过 ${tierLabel.value} 套餐上限（${currentCapGB.value} GB），请减少扩容量`)
    return
  }

  try {
    const res = await handleAddUserStorage({ size: addBytes })

    if (res.code === 200) {
      userInfo.value.totalSpace += addBytes
      storage.set('USER_INFO', userInfo.value)
      showDialog.value = false
      ElMessage.success(
        `扩容成功！新增 ${form.value.addCapacity} GB，当前总容量：${formatFileSize(userInfo.value.totalSpace)}`
      )
    } else {
      ElMessage.error(res.message || '扩容失败，请稍后重试')
    }
  } catch {
    ElMessage.error('网络异常，扩容失败，请稍后重试')
  }
}
</script>

<style scoped>
.capacity-container {
  max-width: 800px;
  margin: 20px auto;
  padding: 20px;
}

.capacity-card {
  padding: 25px;
}

.capacity-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 30px;
}

.capacity-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-top: 25px;
}

.stat-item {
  text-align: center;
  padding: 15px;
  background: #f8f9fa;
  border-radius: 8px;
}

.stat-label {
  display: block;
  color: #666;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
}

.text-danger {
  color: #f56c6c;
}

.text-success {
  color: #67c23a;
}

.unit {
  margin-left: 8px;
  color: #999;
}

.tier-info {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding: 10px 14px;
  background: #f5f7fa;
  border-radius: 6px;
}

.tier-cap-tip {
  font-size: 13px;
  color: #606266;
}

.expand-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
  padding-left: 80px;
}

/* 响应式布局 */
@media (max-width: 768px) {
  .capacity-container {
    padding: 10px;
  }

  .capacity-stats {
    grid-template-columns: 1fr;
  }
}
</style>