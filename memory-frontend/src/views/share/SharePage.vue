<template>
    <div id="sharePage">
        <GlobalHeader :showSearh="false" :show-app-menu="false" />

        <div class="sharePage-main">
            <div class="sharePage-sider">
                <div class="sharePage-card">
                    <div class="sharePage-user">
                        <img class="sharePage-user-avatar" :src="shareInfo.sharePersonAvatar" />
                        <div class="sharePage-user-copy">
                            <span class="sharePage-user-kicker">来自分享</span>
                            <span class="sharePage-user-name">{{ shareInfo.sharePersonName }}</span>
                        </div>
                    </div>

                    <div class="share-image">
                        <div class="image-container" :class="`count-${Math.min(shareImageUrlList.length, 3)}`">
                            <el-image v-for="(item, index) in shareImageUrlList" :key="index" :src="item" alt=""
                                fit="cover" />
                            <div class="image-count">共{{ fileIds.length }}张</div>
                        </div>
                    </div>

                    <div class="sharePage-title">
                        <div class="sharePage-name">照片合集</div>
                        <div class="sharePage-failure-time">
                            {{ formatDate(shareInfo.expireTime) }} 失效
                        </div>
                    </div>

                    <div class="sharePage-actions">
                        <el-button class="save-share-btn" color="#5f63df" type="primary"
                            @click="clickSaveShare">保存全部</el-button>
                        <el-button class="copy-share-btn" @click="copyShareUrl">复制链接</el-button>
                    </div>

                    <div class="sharePage-desc">
                        <div class="sharePage-desc-item">
                            <span>图片</span>
                            <strong>{{ shareInfo.imageCount }}</strong>
                        </div>
                        <div class="sharePage-desc-item">
                            <span>视频</span>
                            <strong>{{ shareInfo.videoCount }}</strong>
                        </div>
                        <div class="sharePage-desc-item">
                            <span>浏览</span>
                            <strong>{{ shareInfo.visitCount }}</strong>
                        </div>
                    </div>
                </div>
            </div>
            <div class="sharePage-content">
                <!-- 顶部菜单 -->
                <div class="content-header">
                    <div class="content-header-left">
                        <div class="share-content-title">
                            <span class="share-content-kicker">SHARED ALBUM</span>
                            <strong>分享内容</strong>
                        </div>
                    </div>
                    <div class="content-header-right">


                        <!-- 照片尺寸 -->
                        <el-dropdown trigger="click" @command="handleCommandImageSize">
                            <el-button text class="content-header-right-button">
                                <i-ep-grid />
                                <span class="content-header-button-span">{{ imageStyleText }}</span>
                            </el-button>
                            <template #dropdown>
                                <el-dropdown-menu>
                                    <el-dropdown-item class="content-header-right-item"
                                        :style="{ color: imageStyleText == item.label ? '#409EFF' : '' }"
                                        :icon="imageStyleText == item.label ? Check : ''" :command="index"
                                        v-for="(item, index) in imageStyleSizeList">{{ item.label
                                        }}</el-dropdown-item>
                                </el-dropdown-menu>
                            </template>
                        </el-dropdown>
                        <!-- 照片比例 -->
                        <el-tooltip :content="scale.label" placement="bottom">
                            <el-button text @click="handleSacle">
                                <i-carbon:fit-to-height v-if="scale.value == 'original'" />
                                <i-carbon:fit-to-width v-else />
                            </el-button>
                        </el-tooltip>
                    </div>
                </div>

                <!-- 图片列表 -->
                <SharePictureList parentComponent="SharePage" :fileInfoList="fileInfoList" :moreFileInfoList="[]"
                    :imageStyleSize="imageStyleSize" :scale="scale" :albumId="-1" :shareToken="shareToken">
                </SharePictureList>


            </div>
        </div>
        <el-dialog v-model="loginForm" style="max-width: 480px;">
            <LoginForm :showfoot="false" />
        </el-dialog>

        <el-dialog v-model="registerForm" style="max-width: 480px;">
            <RegisterForm :showfoot="false" />
        </el-dialog>
    </div>
</template>

<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router';
import GlobalHeader from '@/layouts/GlobalHeader.vue'
import { Check } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue';
import { getShareFileInfo, saveSharePicture } from '@/api/file/file'
import SharePictureList from '@/components/share/SharePictureList.vue'
import LoginForm from '@/components/auth/LoginForm.vue';
import RegisterForm from '@/components/auth/RegisterForm.vue';
import $bus from '@/utils/bus.ts';
const router = useRouter();
const route = useRoute();
const shareToken = ref(route.params.shareToken);
const shareUrl = ref(`${window.location.origin}/share/${shareToken.value}`);


const shareInfo = ref({
    sharePersonName: '',
    sharePersonAvatar: '',
    visitCount: 0,
    imageCount: 0,
    videoCount: 0,
    expireTime: '',
}
)
const fileIds = ref<string[]>([]);
const loginForm = ref(false);
const registerForm = ref(false);
const clickSaveShare = async () => {
    if (!checkLogin()) return;

    const res = await saveSharePicture({
        fileIds: fileIds.value,
        shareToken: shareToken.value,
    });
    if (res.code === 200) {
        ElMessage.success('保存成功');
    }
}
const checkLogin = () => {
    let token = localStorage.getItem('token');
    if (!token) {
        ElMessage.warning('请先登录');
        return false;
    }
    return true
}
// 复制操作
const copyShareUrl = async () => {
    if (!checkLogin()) return;
    try {
        // 现代浏览器方案（推荐）
        if (navigator.clipboard) {
            await navigator.clipboard.writeText(shareUrl.value);
            ElMessage.success('链接已复制到剪切板');
            return;
        }
        // 兼容旧版浏览器的降级方案
        const textarea = document.createElement('textarea');
        textarea.value = shareUrl.value;
        textarea.style.position = 'fixed';  // 防止滚动
        document.body.appendChild(textarea);
        textarea.select();

        const result = document.execCommand('copy');
        document.body.removeChild(textarea);

        if (result) {
            ElMessage.success('链接已复制');
        } else {
            throw new Error('复制失败');
        }
    } catch {
        ElMessage.error('复制失败');
    }
};
const showBus = () => {
    $bus.off('loginForm');
    $bus.on('loginForm', (val) => {
        loginForm.value = <boolean>val;
        if (!val) {
            $bus.emit('login');
        }
    })
    $bus.off('registerForm');
    $bus.on('registerForm', (val) => {

        registerForm.value = <boolean>val;
    })
}
onMounted(() => {
    showBus();
    selectShareFileInfo();
});
const selectShareFileInfo = () => {
    getShareFileInfo({
        shareToken: shareToken.value,
    }).then(res => {
        if (res.code === 200) {
            shareInfo.value = res.data
            fileInfoList.value = res.data.fileInfoList;
            initShareImageUrlList();
        } else {
            ElMessage.error(res.message)
        }
    })
}


const scale = reactive({
    label: "原始比例",
    value: "square"
});
const handleSacle = () => {
    if (scale.value == "original") {
        scale.value = "square";
        scale.label = "原始比例";
    } else {
        scale.value = "original";
        scale.label = "正方形";
    }
}

/*图片样式*/
const imageStyleSizeList = [
    {
        label: '大图模式',
        value: 200,
    },
    {
        label: '中图模式',
        value: 150,
    },
    {
        label: '小图模式',
        value: 120,
    },
]
const imageStyleSize = ref(imageStyleSizeList[1].value);
const imageStyleText = ref(imageStyleSizeList[1].label);
const handleCommandImageSize = (index: number) => {
    imageStyleSize.value = imageStyleSizeList[index].value;
    imageStyleText.value = imageStyleSizeList[index].label;
}

const fileInfoList = ref<API.FileInfoList[]>([])

const shareImageUrlList = ref(['']);
const initShareImageUrlList = () => {
    shareImageUrlList.value = [];
    let count1 = 0;
    let count2 = 0;
    let count3 = 0;
    fileInfoList.value.forEach((fileInfo) => {
        fileInfo.fileList.find((file) => {
            if (count1 < 3) {
                shareImageUrlList.value.push(file.thumbnailUrl);
            }
            fileIds.value.push(file.fileId);
            count1++;

            if (file.category == "image") {
                count2++;
            }
            if (file.category == "video") {
                count3++;
            }
        })
    })
    shareInfo.value.imageCount = count2;
    shareInfo.value.videoCount = count3;
}


const isLoading = ref(false)

const formatDate = (isoString: string) => {
    const date = new Date(isoString);

    // 年月日补零
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0'); // 03[6,8](@ref)
    const day = String(date.getDate()).padStart(2, '0');        // 04[6,8](@ref)

    // 时间补零与时段判断
    const hours = date.getHours();
    const minutes = String(date.getMinutes()).padStart(2, '0'); // 02[6](@ref)
    const period = hours >= 12 ? '下午' : '上午';                // 时段[5](@ref)

    // 返回两种可选格式：
    // 方案1：24小时制带时段（输出：2025年03月04日 下午14:02）
    return `${year}年${month}月${day}日${String(hours).padStart(2, '0')}:${minutes}`;

    // 方案2：12小时制带时段（输出：2025年03月04日 下午02:02）
    //const formattedHours = String(hours % 12 || 12).padStart(2, '0'); // 02[6,8](@ref)
    //return `${year}年${month}月${day}日${formattedHours}:${minutes}`;
};


</script>
<style scoped>
#sharePage {
    width: 100%;
    height: 100%;
    position: relative;
    background: #f5f7fb;
}

.sharePage-main {
    width: 100%;
    height: calc(100% - 72px);
    position: fixed;
    display: flex;
    top: 72px;
    background:
        linear-gradient(135deg, rgba(244, 247, 255, 0.96), rgba(250, 251, 255, 0.92));
}

.sharePage-sider {
    user-select: none;
    height: 100%;
    width: 300px;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    padding: 24px 22px;
    background:
        linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(241, 244, 252, 0.82));
    border-right: 1px solid rgba(108, 117, 161, 0.13);
    box-shadow: 14px 0 42px rgba(35, 43, 84, 0.08);
}

.sharePage-card {
    width: 100%;
    min-height: 0;
    padding: 18px;
    border: 1px solid rgba(108, 117, 161, 0.15);
    border-radius: 22px;
    background: rgba(255, 255, 255, 0.78);
    box-shadow: 0 18px 46px rgba(35, 43, 84, 0.11);
    backdrop-filter: blur(14px);
}

.sharePage-user {
    display: flex;
    align-items: center;
    gap: 12px;
    padding-bottom: 16px;
    border-bottom: 1px solid rgba(108, 117, 161, 0.12);
}

.sharePage-user-copy {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.sharePage-user-kicker {
    color: #98a0b7;
    font-size: 12px;
    font-weight: 600;
}

.sharePage-user-name {
    color: #252a3d;
    font-size: 15px;
    font-weight: 650;
    line-height: 1.25;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.sharePage-user-avatar {
    width: 48px;
    height: 48px;
    transition: transform 0.2s ease;
    border-radius: 50%;
    object-fit: cover;
    border: 3px solid rgba(255, 255, 255, 0.86);
    box-shadow: 0 8px 20px rgba(64, 77, 132, 0.16);
}

/* 基础容器 */
.share-image {
    margin-top: 18px;
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: center;
}

.image-container {
    position: relative;
    width: 190px;
    height: 190px;
    display: grid;
    gap: 6px;
    overflow: hidden;
    border: 6px solid rgba(255, 255, 255, 0.92);
    border-radius: 18px;
    box-shadow: 0 18px 38px rgba(35, 43, 84, 0.18);
}

/* 2张图片布局调整 */
.image-container.count-2 {
    grid-template: 1fr / 112px 66px;
}

/* 3张图片布局调整 */
.image-container.count-3 {
    grid-template:
        "main sub1" 89px
        "main sub2" 89px
        / 112px 66px;
}

/* 其他样式保持不变 */
.image-container.count-1 {
    grid-template: 1fr / 1fr;
}

.image-container.count-3 .el-image:nth-child(1) {
    grid-area: main;
}

.image-container.count-3 .el-image:nth-child(2) {
    grid-area: sub1;
}

.image-container.count-3 .el-image:nth-child(3) {
    grid-area: sub2;
}

.el-image {
    width: 100%;
    height: 100%;
    background: #f5f5f5;
}

.image-count {
    background: rgba(17, 24, 39, .68);
    height: 26px;
    border-radius: 999px;
    line-height: 26px;
    text-align: center;
    color: #fff;
    font-weight: 600;
    font-size: 13px;
    position: absolute;
    right: 12px;
    bottom: 12px;
    padding: 0 12px;
    backdrop-filter: blur(8px);
}

.sharePage-title {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    justify-content: center;
    margin-top: 18px;
    padding: 0 4px;
}

.sharePage-name {
    font-family: PingFangSC-Semibold;
    font-size: 22px;
    font-weight: 700;
    line-height: 1.25;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: #1f2435;
    max-width: 100%;
}

.sharePage-failure-time {
    margin-top: 8px;
    line-height: 1.4;
    font-size: 13px;
    color: #f05252;
}

.sharePage-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 18px;
}

.save-share-btn,
.copy-share-btn {
    flex: 1;
    height: 40px;
    border-radius: 12px;
    font-weight: 650;
}

.copy-share-btn {
    color: #5f63df;
    border-color: rgba(95, 99, 223, 0.22);
    background: rgba(95, 99, 223, 0.08);
}

.sharePage-desc {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    width: 100%;
    margin-top: 18px;
}

.sharePage-desc-item {
    display: flex;
    min-height: 58px;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
    padding: 8px 4px;
    border: 1px solid rgba(108, 117, 161, 0.12);
    border-radius: 14px;
    background: rgba(246, 248, 253, 0.82);
    text-align: center;
}

.sharePage-desc-item span {
    color: #7a8198;
    font-size: 12px;
}

.sharePage-desc-item strong {
    color: #282d42;
    font-size: 18px;
    line-height: 1;
}










/* 照片内容 */
.sharePage-content {
    width: calc(100% - 300px);
    height: 100%;
    position: relative;
    display: flex;
    flex-direction: column;
    background: rgba(255, 255, 255, 0.62);
}

.content-header {
    z-index: 4;
    width: 100%;
    height: 72px;
    display: flex;
    align-items: center;
    padding: 0 28px;
    border-bottom: 1px solid rgba(108, 117, 161, 0.13);
    background: rgba(255, 255, 255, 0.72);
    box-shadow: 0 10px 26px rgba(35, 43, 84, 0.05);
    box-sizing: border-box;
}

/* 左边按钮 */
.content-header-left-button {
    width: 100px;
    margin-left: 20px;
}

.content-header-right-button {
    width: 100px;
}

.content-header-menu {
    width: 160px;
}

.content-header-left {
    height: 100%;
    display: flex;
    align-items: center;
    margin-right: auto;
}

.share-content-title {
    display: flex;
    flex-direction: column;
    gap: 3px;
}

.share-content-kicker {
    color: #98a0b7;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1.4px;
}

.share-content-title strong {
    color: #252a3d;
    font-size: 18px;
    line-height: 1.2;
}

/* 右边操作 */
.content-header-right {
    height: 100%;
    margin-right: 0;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
}

.content-header-button-span {
    margin-left: 5px;
}

:deep(.content-header-right-item i) {
    font-size: 16px;
}


/* 自定义ElementUI分割线 */
.el-divider--horizontal {
    margin-top: 0px;
    margin-bottom: 0px;
}

/* 自定义Element dropdown */
:deep(.content-header-right-item) {
    display: flex;
    justify-content: flex-end;
    margin: 5px;
}
</style>
