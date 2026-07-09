<template>
  <div class="info-container">
    <el-form ref="formRef" :model="editForm" label-width="100px" class="user-form">
      <el-form-item label="账号 ID">
        <el-input v-model="editForm.userId" disabled />
      </el-form-item>

      <el-form-item label="昵称" prop="userName">
        <!-- 绑定 editForm，不直接修改快照，避免实时同步左侧栏 -->
        <el-input v-model="editForm.userName" />
      </el-form-item>

      <el-form-item label="注册邮箱">
        <el-input v-model="editForm.email" disabled />
      </el-form-item>

      <el-form-item label="注册时间">
        <el-input :value="formatDate(editForm.createTime)" disabled />
      </el-form-item>

      <el-form-item label="个人简介" prop="profile">
        <el-input v-model="editForm.profile" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }"
          :maxlength="200" show-word-limit resize='none' />
      </el-form-item>

      <el-form-item>
        <!-- 无改动时禁用，避免无效请求 -->
        <el-button type="primary" :disabled="!hasChanges" @click="handleSubmit">保存修改</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { updateUserInfo, getUserInfo } from '@/api/user/index'
import { storage } from '@/utils/storage'
import $bus from '@/utils/bus'

// ── 原始数据快照（加载后不变，用于对比是否有改动）
const userInfo = ref(<API.UserInfo>{
  userId: 1000000001,
  userName: "鹏",
  account: "xzp",
  email: "1014537454@qq.com",
  createTime: "2025-02-20T15:18:26",
  updateTime: "2025-03-04T22:31:21",
  profile: "您好",
  avatarUrl: "",
  totalSpace: 21474836480,
  usedSpace: 32988616,
  accountStatus: "svip",
  membershipDays: 100000
})

// ── 可编辑副本（v-model 绑定此对象，不直接改快照）
const editForm = ref({ ...userInfo.value })

const formRef = ref<FormInstance>()

onMounted(() => {
  const Info = storage.get<API.UserInfo>('USER_INFO')
  if (Info) {
    userInfo.value = Info
    editForm.value = { ...Info }
  } else {
    selectUserInfo()
  }
  // 头像在对话框中确认后，通知此处更新可编辑副本的 avatarUrl
  // 使保存按钮可用，且保存时会将新头像 URL 一并提交
  $bus.on('avatarConfirmed', handleAvatarConfirmed)
})

onUnmounted(() => {
  $bus.off('avatarConfirmed', handleAvatarConfirmed)
})

const handleAvatarConfirmed = (newUrl: unknown) => {
  editForm.value.avatarUrl = newUrl as string
}

const selectUserInfo = async () => {
  const token = localStorage.getItem('token')
  if (token) {
    const res = await getUserInfo()
    if (res.code == 200) {
      userInfo.value = res.data
      editForm.value = { ...res.data }
    }
  }
}

// ── 有改动才允许保存（比较昵称、简介、头像三个可编辑字段）
const hasChanges = computed(() => {
  return editForm.value.userName !== userInfo.value.userName
    || editForm.value.profile !== userInfo.value.profile
    || editForm.value.avatarUrl !== userInfo.value.avatarUrl
})

const handleSubmit = async () => {
  // 将可编辑副本的改动合并到完整数据对象再提交
  const dataToSave: API.UserInfo = { ...userInfo.value, ...editForm.value }
  const res = await updateUserInfo(dataToSave)
  if (res.code == 200) {
    ElMessage.success('用户信息修改成功！')
    // 更新快照，使 hasChanges 归零
    userInfo.value = { ...dataToSave }
    editForm.value = { ...dataToSave }
    storage.set('USER_INFO', userInfo.value)
    // 通知左侧栏（UserMid）和右上角（GlobalHeader）同步昵称、头像
    $bus.emit('userInfoUpdated', userInfo.value)
  } else {
    ElMessage.error("用户信息修改失败！")
  }
}

const formatDate = (isoString: string) => {
  const date = new Date(isoString)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = date.getHours()
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const period = hours >= 12 ? '下午' : '上午'
  return `${year}年${month}月${day}日 ${period}${String(hours).padStart(2, '0')}:${minutes}`
}
</script>

<style scoped>
.info-container {
  width: 100%;
  height: 100%;
}

.user-form {
  padding: 20px;
  padding-left: 0;
}
</style>
