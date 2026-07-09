<!-- AccountCenter.vue -->
<template>
  <div class="account-container">
    <!-- 关闭按钮：点击返回上一页 -->
    <el-button class="close-btn" circle @click="router.replace({ name: 'home' })">
      <el-icon><Close /></el-icon>
    </el-button>

    <div class="account-overview">
        <div class="avatar-wrapper" @click="showUploadDialog">
          <el-image :src="userInfo.avatarUrl" class="avatar-image" fit="cover" />
          <div class="edit-overlay">
            <el-icon>
              <EditPen />
            </el-icon>
          </div>
        </div>

        <div class="profile-info">
          <div class="profile-kicker">PERSONAL CLOUD SPACE</div>
          <div class="user-name">{{ userInfo.userName }}</div>
          <div class="user-status">
            <el-tag :type="getTagType(userInfo.accountStatus)" size="small" :effect="getTagEffect(userInfo.accountStatus)">
              {{ getUserLevelText(userInfo.accountStatus) }}
            </el-tag>
            <el-button v-if="userInfo.accountStatus === 'normal'" type="primary" size="small" class="upgrade-btn"
              @click="navigateToMember">
              升级会员
            </el-button>
          </div>
        </div>

        <div class="account-summary">
          <div class="summary-pill">
            <span>邮箱</span>
            <strong>{{ userInfo.email }}</strong>
          </div>
          <div class="summary-pill">
            <span>账号</span>
            <strong>{{ userInfo.account }}</strong>
          </div>
          <div class="summary-pill">
            <span>会员天数</span>
            <strong>{{ userInfo.membershipDays }}</strong>
          </div>
        </div>
    </div>

    <div class="account-body">
      <div class="sidebar">
        <el-menu class="side-menu" router :default-active="currentRouteName" active-text-color="rgb(99,125,255)"
          :collapse="false">
          <el-menu-item index="userInfo" :route="{ name: 'userInfo' }">
            <el-icon class="custom-icon">
              <User />
            </el-icon>
            <span>基本信息</span>
          </el-menu-item>

          <el-menu-item index="securitySet" :route="{ name: 'securitySet' }">
            <el-icon class="custom-icon">
              <Lock />
            </el-icon>
            <span>安全设置</span>
          </el-menu-item>

          <el-menu-item index="storageSet" :route="{ name: 'storageSet' }">
            <el-icon class="custom-icon">
              <Files />
            </el-icon>
            <span>容量管理</span>
          </el-menu-item>

          <el-menu-item index="memberCenter" :route="{ name: 'memberCenter' }">
            <el-icon class="custom-icon">
              <Sell />
            </el-icon>
            <span>会员中心</span>
          </el-menu-item>
        </el-menu>
      </div>

      <div class="main-content">
        <router-view />
      </div>
    </div>

    <!-- 头像上传对话框（右上角 × 可关闭，无需取消按钮） -->
    <el-dialog v-model="uploadVisible" class="avatar-dialog" title="更换头像" width="400" :append-to-body="true">

      <el-upload :headers="headers" :limit="1" ref="uploadRef" class="avatar-uploader" :auto-upload="false"
        :action="uploadUrl" :show-file-list="false" drag :on-success="handleAvatarSuccess"
        :on-change="checkAvatarUpload" :on-exceed="handleExceed">
        <el-image v-if="tempAvatar" :src="tempAvatar" class="upload-avatar" fit="cover" />
        <el-icon v-else class="upload-icon">
          <Plus />
        </el-icon>
      </el-upload>

      <template #footer>
        <span class="upload-tip">tip:点击或者拖拽图片上传</span>
        <!-- 取消按钮已移除，直接点右上角 × 关闭 -->
        <el-button type="primary" :loading="avatarUploading" @click="confirmAvatar">确认更换</el-button>
      </template>
    </el-dialog>
  </div>



</template>

<script setup lang="ts">

import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock, Files, EditPen, Plus, Close } from '@element-plus/icons-vue'
import type { UploadInstance, UploadProps, UploadRawFile } from 'element-plus'
import { genFileId } from 'element-plus'
import { useRoute } from 'vue-router'
import requestPublicConfig from '@/api/config'
import { getUserInfo } from '@/api/user/index'
import { storage } from '@/utils/storage'
import $bus from '@/utils/bus'
const route = useRoute()
const uploadRef = ref<UploadInstance>()
const currentRouteName = route.name as string

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
    accountStatus: "normal",
    membershipDays: 100000
})
const token = localStorage.getItem('token');
onMounted(() => {
  const Info = storage.get<API.UserInfo>('USER_INFO');
  if (Info) {
    userInfo.value = Info;
  } else {
    selectUserInfo();
  }
  // 监听子页面（UserInfo）保存成功后的通知，同步更新左侧栏昵称
  $bus.on('userInfoUpdated', (updated) => {
    userInfo.value = updated as API.UserInfo
  })
})
const selectUserInfo = async () => {
  const token = localStorage.getItem('token');
  if (token) {
    const res = await getUserInfo();
    if (res.code == 200) {
      userInfo.value = res.data;

    }
  }
}
const headers = computed(() => ({
  Authorization: token,
}));


const uploadUrl = requestPublicConfig.baseUrl + '/user/uploadAvatar'
const uploadVisible = ref(false)
const avatarUploading = ref(false)
// 临时存储本地预览的头像 blob URL
const tempAvatar = ref('')

const showUploadDialog = () => {
  tempAvatar.value = ''
  uploadVisible.value = true
}

const handleExceed: UploadProps['onExceed'] = (files) => {
  uploadRef.value!.clearFiles()
  const file = files[0] as UploadRawFile
  file.uid = genFileId()
  uploadRef.value!.handleStart(file)
}

// 文件选中时仅更新预览，不做格式限制（支持所有图片格式）
const checkAvatarUpload: UploadProps['onChange'] = (file) => {
  if (file.raw) {
    tempAvatar.value = URL.createObjectURL(file.raw)
  }
}

// 上传成功回调：获取服务端真实 URL，更新侧边栏预览，通知 UserInfo.vue 追踪变更
const handleAvatarSuccess: UploadProps['onSuccess'] = async () => {
  avatarUploading.value = false
  // 从服务端重新拉取用户信息，得到真实的 MinIO 头像 URL
  const res = await getUserInfo()
  const newAvatarUrl = res.code === 200 ? res.data.avatarUrl : tempAvatar.value

  // 更新左侧栏头像（立即可见）
  userInfo.value.avatarUrl = newAvatarUrl
  uploadVisible.value = false
  ElMessage.success('头像已更新，请点击保存修改以同步到右上角')

  // 通知 UserInfo.vue 将新头像 URL 纳入变更检测，点保存修改才同步 GlobalHeader
  $bus.emit('avatarConfirmed', newAvatarUrl)
}

// 确认更换：校验后触发一次上传，上传结果在 handleAvatarSuccess 中处理
const confirmAvatar = () => {
  if (!tempAvatar.value) {
    ElMessage.warning('请先选择要上传的头像')
    return
  }
  avatarUploading.value = true
  uploadRef.value!.submit()
}

const router = useRouter()
const navigateToMember = () => {
  router.push({ name: 'memberCenter' })
}

// 用户等级相关方法
const getUserLevelText = (level: string) => {
  const levelMap: Record<string, string> = {
    normal: '普通用户',
    vip: 'VIP用户',
    svip: '超级VIP用户'
  }
  return levelMap[level] || '未知身份'
}

type ValidTagType = "success" | "warning" | "info" | "primary" | "danger"
const getTagType = (level: string): ValidTagType => {
  const typeMap: Record<string, ValidTagType> = {
    normal: 'info',
    vip: 'danger',
    svip: 'warning'
  }
  return typeMap[level] || 'info'
}

const getTagEffect = (level: string) => {
  return level === 'normal' ? 'plain' : 'dark'
}
</script>

<style scoped>
.account-container {
  position: relative;
  margin: 20px auto;
  padding: 20px;
  box-sizing: border-box;
  border-radius: 24px;
  box-shadow: 0 24px 60px rgba(35, 43, 84, 0.13);
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 800px;
  transition: 0.5s ease-in-out;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.86), rgba(241, 245, 255, 0.76));
}

.close-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 10;
  width: 28px;
  height: 28px;
  padding: 0;
  font-size: 14px;
  color: #909399;
  background: transparent;
  border-color: transparent;
}

.close-btn:hover {
  color: #303133;
  background: rgba(0, 0, 0, 0.06);
  border-color: transparent;
}




/* 顶部个人概览 */
.account-overview {
  display: grid;
  grid-template-columns: auto minmax(180px, 1fr) minmax(320px, 1.4fr);
  align-items: center;
  gap: 18px;
  min-height: 136px;
  padding: 22px 56px 22px 24px;
  border: 1px solid rgba(105, 115, 160, 0.14);
  border-radius: 22px;
  background:
    radial-gradient(circle at 8% 18%, rgba(216, 182, 92, 0.18), transparent 26%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(231, 236, 252, 0.88));
  box-shadow: 0 16px 38px rgba(35, 43, 84, 0.1);
}

.profile-kicker {
  color: #98a0b7;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 1.2px;
  margin-bottom: 7px;
}

.account-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.summary-pill {
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid rgba(105, 115, 160, 0.13);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
}

.summary-pill span {
  display: block;
  color: #8a91a7;
  font-size: 12px;
  margin-bottom: 6px;
}

.summary-pill strong {
  display: block;
  color: #252a3d;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-body {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

/* 导航区 */
.sidebar {
  width: 220px !important;
  max-width: 220px !important;
  padding: 12px;
  box-sizing: border-box;
  border: 1px solid rgba(105, 115, 160, 0.13) !important;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.7) !important;
  box-shadow: 0 12px 30px rgba(35, 43, 84, 0.08);
}

.side-menu {
  width: 100% !important;
  height: auto !important;
  border: 0;
  background: transparent;
}

.side-menu .el-menu-item {
  width: auto !important;
  display: flex;
  justify-content: flex-start;
  height: 48px;
  margin: 6px 0;
  border-radius: 14px;
}

.main-content {
  flex: 1;
  width: auto !important;
  min-width: 0;
  height: 100% !important;
  margin-top: 0 !important;
  padding: 18px 22px;
  box-sizing: border-box;
  overflow: auto;
  border: 1px solid rgba(105, 115, 160, 0.13);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.72) !important;
  box-shadow: 0 12px 30px rgba(35, 43, 84, 0.08);
}

@media screen and (max-height: 1280px) {
  .account-container {
    height: 800px;
  }
}

@media screen and (max-height: 1080px) {
  .account-container {
    height: 800px;
  }
}

@media screen and (max-height: 868px) {
  .account-container {
    height: 630px;
  }
}




@media screen and (max-width: 2048px) {
  .account-container {
    max-width: 1500px;

  }
}

/* 当屏幕宽度小于等于 1920px 时应用的样式 */
@media screen and (max-width: 1920px) {
  .account-container {
    max-width: 1120px;

  }
}

/* 当屏幕宽度小于等于 1366px 时应用的样式 */
@media screen and (max-width: 1280px) {
  .account-container {
    max-width: 960px;

  }
}

/* 当屏幕宽度小于等于 1366px 时应用的样式 */
@media screen and (max-width: 1280px) {
  .side-menu {
    width: 150px;
  }

  .main-content {
    width: calc(100% - 150px);
  }
}

/* 当屏幕宽度小于等于 1366px 时应用的样式 */
@media screen and (max-width: 868px) {
  .side-menu {
    width: 100px;
  }

  .main-content {
    width: calc(100% - 100px);
  }
}



.avatar-wrapper {
  position: relative;
  width: 88px;
  height: 88px;
  margin: 0;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.3s;
}

.avatar-image {
  width: 100%;
  height: 100%;
}

.avatar-wrapper:hover {
  transform: scale(1.05);
}

.avatar-wrapper:hover .edit-overlay {
  opacity: 1;
}

/* 头像hover */
.edit-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.3s;
}

.edit-overlay .el-icon {
  color: #fff;
  font-size: 20px;
}

.profile-info {
  min-width: 0;
  margin-top: 0;
}

.user-name {
  color: #20243b;
  font-size: 24px;
  font-weight: 750;
  margin-bottom: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-status {
  display: flex;
  gap: 10px;
  justify-content: flex-start;
  align-items: center;
}

.upgrade-btn {
  height: 24px;
  border-radius: 999px;

}


/* 更换头像 */
.avatar-dialog {
  width: 30%;
  border-radius: 20%;
  position: relative;
}

.avatar-uploader {
  display: flex;
  justify-content: center;
  align-items: center;

  /* cursor: pointer;
  border: 1px dashed #d9d9d9; */
}

:deep(.el-upload-dragger) {
  width: 200px;
  height: 200px;
  display: flex;
  justify-content: center;
  align-items: center;
  margin: 0;
  padding: 0;
  border-radius: 50%;
}

upload-avatar {
  width: 100%;
  height: 100%;
}


.upload-icon {
  font-size: 28px;
  color: #8c939d;

}



/* 自定义激活状态样式 */
:deep(.el-menu-item.is-active) {
  background-color: rgba(99, 125, 255, 0.1) !important;
  color: rgb(99, 125, 255) !important;
}

:deep(.el-menu-item:hover) {
  background-color: rgba(37, 61, 180, 0.05);
}

.upload-tip {
  display: block;
  padding-top: 8px;
  padding-bottom: 8px;
  color: red;
  border-radius: 4px;
  font-size: 14px;
  text-align: center;
}

/* ── 移动端适配：垂直堆叠布局，侧栏变横向标签栏 ──────────────────── */
@media screen and (max-width: 768px) {
  /* 整体容器：改为列方向，取消固定高度与外边距 */
  .account-container {
    flex-direction: column;
    height: auto !important;
    margin: 0;
    border-radius: 0;
    box-shadow: none;
    width: 100%;
  }

  /* 侧边栏：宽度铺满，去掉右边框，改用下边框分隔 */
  .sidebar {
    max-width: 100% !important;
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #dcdfe6;
  }

  /* 用户信息区：改为横向排列，不再竖排固定高度 */
  .user-profile {
    display: flex;
    flex-direction: row;
    align-items: center;
    text-align: left;
    height: auto !important;
    padding: 10px 16px;
    margin: 0;
    gap: 12px;
  }

  /* 头像在横向排列时不需要居中外边距 */
  .avatar-wrapper {
    flex-shrink: 0;
    width: 52px !important;
    height: 52px !important;
    margin: 0 !important;
  }

  /* 菜单：横向标签换行，单列流式下禁止左右拖动找入口 */
  .side-menu {
    width: 100% !important;
    height: auto !important;
    display: flex !important;
    flex-direction: row !important;
    flex-wrap: wrap !important;
    justify-content: center;
    overflow-x: hidden !important;
    border-top: 1px solid #f0f0f0;
    row-gap: 4px;
  }

  .side-menu .el-menu-item {
    width: auto !important;
    flex: 1 1 auto;
    min-width: 0;
    max-width: 50%;
    font-size: 12px;
    padding: 0 6px !important;
    height: 44px !important;
    line-height: 44px !important;
    box-sizing: border-box;
  }

  /* 主内容区：宽度铺满，移除固定宽度计算 */
  .main-content {
    width: 100% !important;
    height: auto !important;
    min-height: 400px;
    overflow-y: auto;
    margin-top: 0;
  }
}
</style>
