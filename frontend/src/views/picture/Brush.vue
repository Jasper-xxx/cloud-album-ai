<template>
  <div id="picture" class="content brush-page">

    <!-- 顶部菜单 -->
    <div class="content-header">

      <div class="content-header-left">
        <h3 style="margin-left: 20px; white-space: nowrap;">相似图片检测</h3>
        <div class="brush-controls">
          <span style="margin-left: 20px; white-space: nowrap;">相似度：</span>
          <!-- min=0：相似度不低于0；max=1：不超过100% -->
          <el-input-number style="width: 120px;" v-model="similarity" :precision="2" :step="0.01" :min="0" :max="1" />
          <span style="margin-left: 20px;   white-space: nowrap;">检测组数：</span>
          <!-- min=1：至少检测1组；step=1：单次点击加减1组 -->
          <el-input-number style="width: 120px;" v-model="searchSize" :precision="0" :step="1" :min="1" />
        </div>
        <el-popover
          v-if="!isMobileViewport"
          v-model:visible="smartSelectPopoverVisible"
          placement="bottom-start"
          :width="360"
          popper-class="smart-select-popper"
        >
          <template #reference>
            <el-button
              type="success"
              round
              class="content-header-left-button smart-select-trigger"
              @click="handleSmartSelectTrigger"
              :disabled="isLoading"
            >
              <span class="content-header-button-span">智能选择</span>
              <el-icon class="smart-select-trigger-icon"><i-ep-arrow-down /></el-icon>
            </el-button>
          </template>

          <div class="smart-select-panel" @touchmove.stop>
            <div class="smart-select-panel-header">
              <div class="smart-select-panel-title">智能去重配置</div>
              <div class="smart-select-panel-subtitle">选择保留策略和每组保留数量</div>
            </div>

            <div class="smart-select-strategy-grid">
              <button
                v-for="option in smartSelectStrategyOptions"
                :key="option.value"
                type="button"
                class="smart-select-strategy-card"
                :class="{ 'is-active': smartSelectConfig.strategy === option.value }"
                @click="smartSelectConfig.strategy = option.value"
              >
                <div class="smart-select-strategy-top">
                  <el-radio :model-value="smartSelectConfig.strategy" :label="option.value">
                    {{ option.label }}
                  </el-radio>
                </div>
                <div class="smart-select-strategy-desc">{{ option.description }}</div>
              </button>
            </div>

            <div class="smart-select-retain-row">
              <div class="smart-select-retain-label">每组保留数量</div>
              <el-input-number
                v-model="smartSelectConfig.retainCount"
                :min="1"
                :max="5"
                :step="1"
                :precision="0"
                controls-position="right"
              />
            </div>

            <div class="smart-select-preview">
              <div class="smart-select-preview-text">将选中 {{ smartSelectPreviewCount }} 张图片</div>
              <div class="smart-select-preview-subtext">执行后会自动勾选每组中除保留项外的相似图片</div>
            </div>

            <el-button type="primary" class="smart-select-apply-button" @click="applySmartSelect">
              一键应用
            </el-button>
          </div>
        </el-popover>
        <el-button
          v-else
          type="success"
          round
          class="content-header-left-button smart-select-trigger"
          @click="handleSmartSelectTrigger"
          :disabled="isLoading"
        >
          <span class="content-header-button-span">智能选择</span>
          <el-icon class="smart-select-trigger-icon"><i-ep-arrow-down /></el-icon>
        </el-button>

        <el-button v-if="false" type="success" round class="content-header-left-button" @click="autoSelect" :disabled="isLoading">
          <span class="content-header-button-span">{{ '智能选择' }}</span>
        </el-button>
        <el-button type="primary" round class="content-header-left-button" @click="startCreateSimilar"
          :disabled="isLoading">
          <span class="content-header-button-span">开始检测</span>
        </el-button>

      </div>


      <div class="content-header-right">

        <!-- 照片类型 -->
        <el-dropdown trigger="click" @command="handleCommandImageType">
          <el-button text>

            <i-ep-camera v-if="imageTypeText.value == 'all'" />
            <i-ep-picture v-if="imageTypeText.value == 'picture'" />
            <i-ep-videoCamera v-if="imageTypeText.value == 'video'" />
            <i-fluent:gif-16-regular v-if="imageTypeText.value == 'gif'" />
            <span class="content-header-button-span">{{ imageTypeText.label }}</span>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item class="content-header-right-item"
                :style="{ color: imageTypeText.label == item.label ? '#409EFF' : '' }"
                :icon="imageTypeText.label == item.label ? Check : ''" :command="index"
                v-for="(item, index) in imageTypeList">{{ item.label
                }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <!-- 照片尺寸 -->
        <el-dropdown trigger="click" @command="handleCommandImageSize">
          <el-button text class="content-header-right-button">
            <i-ep-grid />
            <span class="content-header-button-span">{{ imageStyle.label }}</span>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item class="content-header-right-item"
                :style="{ color: imageStyle.label == item.label ? '#409EFF' : '' }"
                :icon="imageStyle.label == item.label ? Check : ''" :command="index"
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
    <el-divider />
    <!-- 图片列表 -->
    <SimilarPictureList parentComponent="AllPicture" :fileInfoList="fileInfoList" :imageStyleSize="imageStyle.value"
      :scale="scale" :albumId="-1">
    </SimilarPictureList>

    <div
      v-if="smartSelectDrawerVisible"
      class="smart-select-mobile-mask"
      @click="closeSmartSelectOverlay"
    >
      <div class="smart-select-mobile-sheet" @click.stop>
        <div class="smart-select-mobile-handle"></div>
        <div class="smart-select-panel smart-select-panel-mobile" @touchmove.stop>
          <div class="smart-select-panel-header">
            <div class="smart-select-panel-title">智能去重配置</div>
            <div class="smart-select-panel-subtitle">选择保留策略和每组保留数量</div>
          </div>

          <div class="smart-select-strategy-grid">
            <button
              v-for="option in smartSelectStrategyOptions"
              :key="option.value"
              type="button"
              class="smart-select-strategy-card"
              :class="{ 'is-active': smartSelectConfig.strategy === option.value }"
              @click="smartSelectConfig.strategy = option.value"
            >
              <div class="smart-select-strategy-top">
                <el-radio :model-value="smartSelectConfig.strategy" :label="option.value">
                  {{ option.label }}
                </el-radio>
              </div>
              <div class="smart-select-strategy-desc">{{ option.description }}</div>
            </button>
          </div>

          <div class="smart-select-retain-row">
            <div class="smart-select-retain-label">每组保留数量</div>
            <el-input-number
              v-model="smartSelectConfig.retainCount"
              :min="1"
              :max="5"
              :step="1"
              :precision="0"
              controls-position="right"
            />
          </div>

          <div class="smart-select-preview">
            <div class="smart-select-preview-text">将选中 {{ smartSelectPreviewCount }} 张图片</div>
            <div class="smart-select-preview-subtext">执行后会自动勾选每组中除保留项外的相似图片</div>
          </div>

          <el-button type="primary" class="smart-select-apply-button" @click="applySmartSelect">
            一键应用
          </el-button>
        </div>
      </div>
    </div>

  </div>
</template>

<style scoped>
.brush-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
}

.smart-select-trigger {
  width: 136px;
}

.smart-select-trigger-icon {
  margin-left: 4px;
  font-size: 14px;
}

.smart-select-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: min(70vh, 520px);
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-y;
}

.smart-select-panel-mobile {
  max-height: calc(85dvh - 36px);
  padding-bottom: calc(10px + env(safe-area-inset-bottom));
}

.smart-select-panel-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.smart-select-panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2a37;
}

.smart-select-panel-subtitle {
  font-size: 12px;
  color: #7b8794;
}

.smart-select-strategy-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.smart-select-strategy-card {
  width: 100%;
  border: 1px solid #d9e2f2;
  border-radius: 14px;
  background: linear-gradient(180deg, #ffffff 0%, #f7faff 100%);
  padding: 12px 14px;
  text-align: left;
  transition: all 0.2s ease;
  cursor: pointer;
}

.smart-select-strategy-card:hover {
  border-color: #7aa2ff;
  box-shadow: 0 8px 18px rgba(64, 110, 255, 0.12);
}

.smart-select-strategy-card.is-active {
  border-color: #3174ff;
  background: linear-gradient(180deg, #eef4ff 0%, #e5efff 100%);
  box-shadow: 0 10px 22px rgba(49, 116, 255, 0.16);
}

.smart-select-strategy-top {
  display: flex;
  align-items: center;
}

.smart-select-strategy-desc {
  margin-top: 6px;
  padding-left: 24px;
  font-size: 12px;
  line-height: 1.5;
  color: #5b6675;
}

.smart-select-retain-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #f7faff;
}

.smart-select-retain-label {
  font-size: 13px;
  font-weight: 500;
  color: #1f2a37;
}

.smart-select-preview {
  padding: 12px 14px;
  border-radius: 14px;
  background: linear-gradient(135deg, #eff5ff 0%, #f8fbff 100%);
  border: 1px solid #d8e5ff;
}

.smart-select-preview-text {
  font-size: 14px;
  font-weight: 600;
  color: #2457d6;
}

.smart-select-preview-subtext {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #6b7280;
}

.smart-select-apply-button {
  width: 100%;
  height: 40px;
}

.smart-select-mobile-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: flex-end;
  background: rgba(15, 23, 42, 0.42);
}

.smart-select-mobile-sheet {
  width: 100%;
  max-height: 85dvh;
  border-radius: 22px 22px 0 0;
  background: #ffffff;
  padding: 10px 16px 0;
  box-shadow: 0 -18px 40px rgba(15, 23, 42, 0.18);
}

.smart-select-mobile-handle {
  width: 44px;
  height: 5px;
  margin: 0 auto 12px;
  border-radius: 999px;
  background: #d7dee8;
}

@media (max-width: 992px) {
  /* 移动端：顶部右侧工具条隐藏，避免挤出屏幕；检测按钮回到可见区域 */
  .content-header-right {
    display: none !important;
  }

  .content-header-left {
    width: 100% !important;
    flex-wrap: wrap !important;
    gap: 8px 10px;
  }

  h3 {
    margin-left: 0 !important;
    width: 100%;
  }

  .brush-controls {
    width: 100%;
    margin-left: 0 !important;
  }

  :deep(.el-input-number) {
    width: 110px !important;
  }

  .content-header-left-button {
    width: calc(50% - 6px) !important;
    margin-left: 0 !important;
  }

  .smart-select-trigger {
    width: calc(50% - 6px) !important;
  }
}

@media (max-width: 640px) {
  .smart-select-panel {
    max-height: calc(100vh - 180px);
    padding-right: 2px;
  }

  .smart-select-panel-mobile {
    max-height: calc(86dvh - 28px);
  }

  .smart-select-strategy-card {
    padding: 10px 12px;
  }

  .smart-select-retain-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>

<style>
.smart-select-popper {
  max-width: calc(100vw - 24px) !important;
  max-height: calc(100vh - 120px) !important;
  border-radius: 18px !important;
  padding: 14px !important;
  overflow: hidden !important;
  box-shadow: 0 16px 40px rgba(31, 42, 55, 0.14) !important;
}

body.smart-select-scroll-locked {
  overflow: hidden !important;
  width: 100%;
  touch-action: none;
}
</style>

<script setup lang="ts">
import { Check } from '@element-plus/icons-vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import $bus from '@/utils/bus.ts'
import { getSimilarFileList, createSimilarFileList } from '@/api/file/file'
import SimilarPictureList from '@/components/similar/SimilarPictureList.vue'
import { storage } from '@/utils/storage';
import {
  SMART_SELECT_STRATEGY_OPTIONS,
  getSmartSelectionPreviewCount,
  getSmartSelectStrategyLabel,
  normalizeRetainCount,
  type SmartSelectConfig,
} from '@/utils/similarSmartSelect';

onMounted(() => {
   //获取当前页面设置信息
  window.addEventListener('resize', syncViewportWidth, { passive: true });
  syncViewportWidth();
  CURRENT_PAGE_GET();
  showBus();
  handleSelectAllFileInfo();
});
onUnmounted(() => {
  window.removeEventListener('resize', syncViewportWidth);
  unlockSmartSelectBodyScroll();

  $bus.off('deletePictureSuceess');
  $bus.off('removePictureSuceess');
  $bus.off('smartSelectApplied');
})
/*-----------当前页面设置------------*/
interface OptionString {
  label: string;
  value: string;
}
interface OptionNumber {
  label: string;
  value: number;
}
interface CurrentPageSet {
  imageTypeText: OptionString;
  orderKeyword: OptionString;
  orderType: OptionString;
  imageStyle: OptionNumber;
  scale: OptionString;
}
// 1.图片类型方式
const imageTypeList: OptionString[] = [
  {
    label: '全部',
    value: 'all',
  },
  {
    label: '图片',
    value: 'picture',
  },
  {
    label: '视频',
    value: 'video',
  },
  {
    label: '动图',
    value: 'gif',
  },
]
// 2.排序方式、排序关键词
const orderTypeList: OptionString[] = [
  {
    value: 'asc',
    label: '升序排列',
  },
  {
    value: 'desc',
    label: '降序排列',
  },
]
const orderKeywordList: OptionString[] = [
  {
    value: 'upload_time',
    label: '上传时间',
  },
  {
    value: 'date_time_original',
    label: '拍摄时间',
  },
]

//3.图片大小模式
const imageStyleSizeList: OptionNumber[] = [
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
//4.图片比例模式
const scale = ref<OptionString>({
  label: "正方形",
  value: "original"
});
// 默认方式
//1.图片类型方式
const imageTypeText = ref(imageTypeList[0]);
//2.排序方式、排序关键词
const orderKeyword = ref(orderKeywordList[1]);
const orderType = ref(orderTypeList[1]);
//3.图片大小模式
const imageStyle = ref(imageStyleSizeList[1]);

const CURRENT_PAGE_SET = () => {
  storage.set<CurrentPageSet>('CURRENT_PAGE_SET3', {
    imageTypeText: imageTypeText.value,
    orderKeyword: orderKeyword.value,
    orderType: orderType.value,
    imageStyle: imageStyle.value,
    scale: scale.value,
  });
}
const CURRENT_PAGE_GET = () => {
  const currentPageSet = storage.get<CurrentPageSet>('CURRENT_PAGE_SET3');
  if (currentPageSet && currentPageSet.imageTypeText && currentPageSet.orderKeyword && currentPageSet.orderType && currentPageSet.imageStyle && currentPageSet.scale) {
    imageTypeText.value = currentPageSet.imageTypeText;
    orderKeyword.value = currentPageSet.orderKeyword;
    orderType.value = currentPageSet.orderType;
    imageStyle.value = currentPageSet.imageStyle;
    scale.value = currentPageSet.scale;
    pageRequest.value.imageTypeText = imageTypeText.value.value;
    
   
  }
}
//1.修改图片类型方式
const handleCommandImageType = (index: number) => {
  imageTypeText.value = imageTypeList[index];
  pageRequest.value.imageTypeText = imageTypeText.value.value;
  handleSelectAllFileInfo();
  // 保存当前页面设置
  CURRENT_PAGE_SET();
}


//3.修改图片大小模式
const handleCommandImageSize = (index: number) => {

  imageStyle.value = imageStyleSizeList[index];
  // 保存当前页面设置
  CURRENT_PAGE_SET();
}
//4.修改图片比例模式
const handleSacle = () => {
  if (scale.value.value == "original") {
    scale.value.value = "square";
    scale.value.label = "原始比例";
  } else {
    scale.value.value = "original";
    scale.value.label = "正方形";
  }
  // 保存当前页面设置
  CURRENT_PAGE_SET();
}


const fileInfoList = ref<API.FileInfoList[]>([])
const similarity = ref(0.60);
const searchSize = ref(5);
const isLoading = ref(false);
const smartSelectLockedScrollTop = ref(0);
const viewportWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1200);
const smartSelectPopoverVisible = ref(false);
const smartSelectDrawerVisible = ref(false);
const smartSelectStrategyOptions = SMART_SELECT_STRATEGY_OPTIONS;
const smartSelectConfig = ref<SmartSelectConfig>({
  strategy: 'largestSize',
  retainCount: 1,
});
const isMobileViewport = computed(() => viewportWidth.value <= 992);
const hasDetectedResult = computed(() => fileInfoList.value.some((group) => (group.fileList?.length ?? 0) > 0));
const smartSelectPreviewCount = computed(() =>
  getSmartSelectionPreviewCount(fileInfoList.value, smartSelectConfig.value.retainCount),
);
//请求数据
const pageRequest = ref({
  imageTypeText: imageTypeText.value.value,
})




// 监听事件
const isMobileSmartSelectViewport = () => typeof window !== 'undefined' && window.innerWidth <= 992;

const lockSmartSelectBodyScroll = () => {
  if (typeof document === 'undefined' || typeof window === 'undefined') {
    return;
  }
  const body = document.body;
  if (body.classList.contains('smart-select-scroll-locked')) {
    return;
  }
  smartSelectLockedScrollTop.value = window.scrollY;
  body.classList.add('smart-select-scroll-locked');
  body.style.position = 'fixed';
  body.style.top = `-${smartSelectLockedScrollTop.value}px`;
  body.style.left = '0';
  body.style.right = '0';
}

const unlockSmartSelectBodyScroll = () => {
  if (typeof document === 'undefined' || typeof window === 'undefined') {
    return;
  }
  const body = document.body;
  if (!body.classList.contains('smart-select-scroll-locked')) {
    return;
  }
  body.classList.remove('smart-select-scroll-locked');
  body.style.position = '';
  body.style.top = '';
  body.style.left = '';
  body.style.right = '';
  window.scrollTo(0, smartSelectLockedScrollTop.value);
}

const syncViewportWidth = () => {
  viewportWidth.value = window.innerWidth;
  if (!isMobileViewport.value) {
    smartSelectDrawerVisible.value = false;
    return;
  }
  smartSelectPopoverVisible.value = false;
}

const closeSmartSelectOverlay = () => {
  smartSelectPopoverVisible.value = false;
  smartSelectDrawerVisible.value = false;
}

watch([smartSelectPopoverVisible, smartSelectDrawerVisible], ([popoverVisible, drawerVisible]) => {
  if ((popoverVisible || drawerVisible) && isMobileSmartSelectViewport()) {
    lockSmartSelectBodyScroll();
    return;
  }
  unlockSmartSelectBodyScroll();
});

const showBus = () => {

  $bus.off('deletePictureSuceess');
  $bus.on('deletePictureSuceess', () => {
    handleSelectAllFileInfo();
  });
  $bus.off('removePictureSuceess');
  $bus.on('removePictureSuceess', () => {
    handleSelectAllFileInfo();
  });
  $bus.off('smartSelectApplied');
  $bus.on('smartSelectApplied', ({ success, strategy, retainCount, selectedCount, message }) => {
    if (!success) {
      ElMessage.error(message || '智能选择失败');
      return;
    }
    if (selectedCount === 0) {
      ElMessage.success(
        `已按“${getSmartSelectStrategyLabel(strategy)}”策略处理，每组保留${retainCount}张，当前无可删除图片`,
      );
      return;
    }
    ElMessage.success(
      `已按“${getSmartSelectStrategyLabel(strategy)}”策略选择，每组保留${retainCount}张，当前选中${selectedCount}张图片`,
    );
  });

}

const autoSelect = () => {
  $bus.emit('IsAutoSelect', {
    strategy: 'largestSize',
    retainCount: 1,
  });
}

const handleSmartSelectTrigger = () => {
  if (!hasDetectedResult.value) {
    ElMessage.warning('请先执行相似图片检测');
    return;
  }
  if (isMobileViewport.value) {
    smartSelectPopoverVisible.value = false;
    smartSelectDrawerVisible.value = true;
    return;
  }
  smartSelectPopoverVisible.value = !smartSelectPopoverVisible.value;
}

const applySmartSelect = () => {
  if (!hasDetectedResult.value) {
    closeSmartSelectOverlay();
    ElMessage.warning('请先执行相似图片检测');
    return;
  }

  const normalizedRetainCount = normalizeRetainCount(smartSelectConfig.value.retainCount);
  smartSelectConfig.value.retainCount = normalizedRetainCount;

  $bus.emit('IsAutoSelect', {
    strategy: smartSelectConfig.value.strategy,
    retainCount: normalizedRetainCount,
  });

  closeSmartSelectOverlay();
}
const startCreateSimilar = async () => {
  // ── 前端二次校验（input-number 已有 min/max，这里做兜底提示）──
  if (similarity.value < 0 || similarity.value > 1) {
    ElMessage.warning('相似度须在 0 ~ 1 之间');
    return;
  }
  if (searchSize.value < 1) {
    ElMessage.warning('检测组数不能小于 1');
    return;
  }

  closeSmartSelectOverlay();
  isLoading.value = true;
  try {
    $bus.emit('IsLoading', true);

    /**
     * 新版接口：createSimilarFileList 直接返回检测结果（res.data）
     * 相似图片为实时计算，不永久存库；结果直接赋值给 fileInfoList，无需二次请求
     */
    const res = await createSimilarFileList({
      similarity: similarity.value,
      size: searchSize.value,
    });

    if (res.code === 200) {
      // 直接使用返回的分组数据渲染页面，无需再调 getSimilarFileList
      fileInfoList.value = res.data ?? [];
      ElMessage.success(res.message);
    } else {
      ElMessage.error(res.message);
    }
  } finally {
    isLoading.value = false;
    $bus.emit('IsLoading', false);
  }
};

/**
 * 获取相似图片列表（页面初始化 / 删除后刷新用）
 * 由于结果不持久化，此接口返回空列表，页面将显示空状态提示用户重新检测
 */
const handleSelectAllFileInfo = async () => {
  const res = await getSimilarFileList(pageRequest.value);
  if (res.code === 200) {
    fileInfoList.value = res.data ?? [];
  }
};


</script>
<style scoped>
.content {
  width: 100%;
  height: 100%;
  position: relative;
}

.content-header {
  z-index: 4;
  width: 100%;
  height: 60px;
  display: flex;

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

/* 右边操作 */
.content-header-right {
  height: 100%;
  margin-right: 20px;
  display: flex;
  align-items: center;
  justify-content: flex-end;

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
