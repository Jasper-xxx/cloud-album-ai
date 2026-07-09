<template>
  <div class="image-search-container">
    <div class="page-header">
      <div>
        <h2 class="page-title">
          <el-icon class="title-icon"><Search /></el-icon>
          以图搜图
        </h2>
        <p class="page-desc">支持相册、标签、尺寸筛选，多模式搜索和批量结果处理。</p>
      </div>
      <el-tag type="info" effect="plain">{{ currentModeMeta.label }}</el-tag>
    </div>

    <el-card class="search-panel" shadow="never">
      <div class="search-panel-grid">
        <div class="upload-column">
          <el-upload
            ref="uploadRef"
            class="image-uploader"
            drag
            :auto-upload="false"
            :show-file-list="false"
            :on-change="handleFileChange"
            :before-upload="() => false"
            accept="image/jpeg,image/png,image/gif,image/bmp,image/webp"
          >
            <template v-if="!previewUrl">
              <el-icon class="upload-icon"><UploadFilled /></el-icon>
              <div class="upload-text">
                <span class="upload-primary">点击或拖拽图片到此处</span>
                <span class="upload-hint">支持 JPG / PNG / GIF / BMP / WEBP，最大 20MB</span>
              </div>
            </template>
            <template v-else>
              <div class="preview-wrapper">
                <img :src="previewUrl" class="preview-image" alt="查询图片预览" />
                <div class="preview-overlay">
                  <span>{{ querySource === 'album' ? '已从相册选择，点击可重新选择' : '点击或拖拽更换图片' }}</span>
                </div>
              </div>
            </template>
          </el-upload>

          <div class="query-meta" v-if="selectedFile">
            <div class="query-meta-row">
              <el-icon><Picture /></el-icon>
              <span class="query-meta-name">{{ selectedFile.name }}</span>
            </div>
            <div class="query-meta-tags">
              <el-tag size="small" type="info">{{ formatFileSize(selectedFile.size) }}</el-tag>
              <el-tag size="small" :type="querySource === 'album' ? 'success' : 'warning'">
                {{ querySource === 'album' ? '来自相册' : '本地上传' }}
              </el-tag>
            </div>
          </div>
        </div>

        <div class="control-column">
          <div class="control-block">
            <div class="control-title">搜索模式</div>
            <el-radio-group v-model="searchMode" class="mode-group">
              <el-radio-button
                v-for="mode in modeOptions"
                :key="mode.value"
                :label="mode.value"
              >
                {{ mode.label }}
              </el-radio-button>
            </el-radio-group>
            <p class="control-tip">{{ currentModeMeta.description }}</p>
          </div>

          <div class="control-block">
            <div class="control-title">筛选条件</div>
            <div class="filter-grid">
              <el-select
                v-model="filterState.albumIds"
                multiple
                collapse-tags
                collapse-tags-tooltip
                filterable
                clearable
                placeholder="筛选相册"
              >
                <el-option
                  v-for="album in albumOptions"
                  :key="album.albumId"
                  :label="album.albumName"
                  :value="album.albumId"
                />
              </el-select>

              <el-select
                v-model="filterState.tagNames"
                multiple
                collapse-tags
                collapse-tags-tooltip
                filterable
                clearable
                placeholder="筛选标签"
              >
                <el-option
                  v-for="tag in tagOptions"
                  :key="tag.tagName"
                  :label="`${tag.tagName} (${tag.count})`"
                  :value="tag.tagName"
                />
              </el-select>

              <el-select
                v-model="filterState.sizeRange"
                clearable
                placeholder="筛选尺寸"
              >
                <el-option
                  v-for="item in sizeOptions"
                  :key="item.value || 'all'"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </div>
            <p class="control-tip">筛选条件只在选择相册、标签或尺寸后生效，未选择时按相似度阈值返回全部命中。</p>
          </div>

          <div class="action-buttons">
            <el-button
              type="primary"
              size="large"
              :loading="searching"
              :disabled="!selectedFile"
              @click="handleSearch"
            >
              <el-icon><Search /></el-icon>
              {{ searching ? '搜索中...' : '开始搜索' }}
            </el-button>

            <el-button size="large" :disabled="searching" @click="selectFromAlbumVisible = true">
              <el-icon><Collection /></el-icon>
              从相册选择
            </el-button>

            <el-button size="large" :disabled="searching" @click="handleReset">
              <el-icon><RefreshLeft /></el-icon>
              重置
            </el-button>
          </div>
        </div>
      </div>
    </el-card>

    <el-card v-if="searchDone" class="result-section" shadow="never">
      <div class="result-header">
        <div class="result-summary">
          <el-icon><Grid /></el-icon>
          <span v-if="results.length">
            共找到 <strong>{{ results.length }}</strong> 张相似图片
          </span>
          <span v-else>当前条件下未找到相似图片</span>
        </div>
        <div class="result-summary-meta">
          <el-tag size="small" type="primary">{{ currentModeMeta.label }}</el-tag>
          <span>{{ currentModeMeta.hint }}</span>
        </div>
      </div>

      <div v-if="selectedCount > 0" class="batch-toolbar">
        <span class="batch-toolbar-title">已选 {{ selectedCount }} 张结果图片</span>
        <div class="batch-toolbar-actions">
          <el-button text @click="toggleCheckAllResults">
            {{ allResultsChecked ? '取消全选' : '全选结果' }}
          </el-button>
          <el-button type="primary" plain @click="openAddAlbum">
            <el-icon><FolderAdd /></el-icon>
            批量加相册
          </el-button>
          <el-button type="primary" plain @click="handleBatchDownload">
            <el-icon><Download /></el-icon>
            批量下载
          </el-button>
          <el-button type="danger" plain @click="handleBatchDelete">
            <el-icon><Delete /></el-icon>
            批量删除
          </el-button>
        </div>
      </div>

      <el-empty v-if="results.length === 0" :description="emptyDescription" :image-size="120">
        <template #description>
          <p>{{ emptyDescription }}</p>
          <p class="empty-hint">可以尝试切换模式、放宽筛选条件，或更换查询图片。</p>
        </template>
      </el-empty>

      <div v-else class="result-grid">
        <article
          v-for="item in results"
          :key="item.fileId"
          class="result-card"
          @click="handlePreview(item)"
        >
          <el-checkbox
            class="result-checkbox"
            :model-value="checkedResultIds.includes(item.fileId)"
            @change="(val: string | number | boolean) => toggleResultSelection(item.fileId, Boolean(val))"
            @click.stop
          />

          <div class="card-image-wrapper">
            <el-image
              :src="item.thumbnailUrl || item.fileUrl"
              :alt="item.originFileName"
              class="card-image"
              fit="cover"
              loading="lazy"
              :preview-src-list="[]"
            >
              <template #error>
                <div class="image-error">
                  <el-icon><Picture /></el-icon>
                  <span>图片加载失败</span>
                </div>
              </template>
            </el-image>

            <div class="similarity-badge" :class="getSimilarityClass(item.similarityPercent)">
              {{ item.similarityPercent }}%
            </div>
          </div>

          <div class="card-info">
            <div class="card-filename" :title="item.originFileName">
              {{ truncateFileName(item.originFileName) }}
            </div>
            <div class="card-meta">
              <span>{{ item.width }} × {{ item.height }}</span>
              <span>相似度 {{ item.similarityPercent }}%</span>
            </div>
            <el-progress
              :percentage="item.similarityPercent"
              :color="getSimilarityColor(item.similarityPercent)"
              :stroke-width="6"
              :show-text="false"
            />
          </div>
        </article>
      </div>
    </el-card>

    <el-dialog
      v-model="selectFromAlbumVisible"
      title="从相册中选择查询图片"
      :width="albumDialogWidth"
      :top="albumDialogTop"
      class="album-select-dialog"
      destroy-on-close
    >
      <div class="album-select-tip">请选择一张图片，选择完成后会自动开始搜索。</div>
      <div class="album-select-panel">
        <SelectPicture :imageOnly="true" />
      </div>
    </el-dialog>

    <AddAlbum
      v-model:show="showAddAlbumList"
      :checked-images="checkedImagesForAlbum"
      :current-file-id="currentFileId"
    />

    <el-dialog
      v-model="previewVisible"
      :title="previewItem?.originFileName || '图片预览'"
      :width="dialogWidth"
      class="preview-dialog"
      destroy-on-close
    >
      <div class="preview-dialog-content">
        <img
          v-if="previewItem"
          :src="previewItem.fileUrl"
          :alt="previewItem.originFileName"
          class="preview-full-image"
        />
      </div>
      <template #footer>
        <div class="preview-dialog-footer">
          <el-tag :color="getSimilarityColor(previewItem?.similarityPercent ?? 0)" class="similarity-tag">
            相似度 {{ previewItem?.similarityPercent }}%
          </el-tag>
          <span class="preview-filename">{{ previewItem?.originFileName }}</span>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, ElUpload } from 'element-plus'
import {
  Collection,
  Delete,
  Download,
  FolderAdd,
  Grid,
  Picture,
  RefreshLeft,
  Search,
  UploadFilled,
} from '@element-plus/icons-vue'
import $bus from '@/utils/bus'
import requestPublicConfig from '@/api/config'
import { deleteFileByIds, getDownloadToken, searchSimilarImages } from '@/api/file/file'
import { selectAllAlbum } from '@/api/album/album'
import { selectAllTags } from '@/api/visual/visual'
import AddAlbum from '@/components/album/AddAlbum.vue'
import SelectPicture from '@/components/select_picture/SelectPicture.vue'

type SearchMode = 'fuzzy' | 'exact' | 'local'
type SizeRange = '' | 'small' | 'medium' | 'large'

interface ImageSearchResult {
  fileId: string
  originFileName: string
  fileUrl: string
  thumbnailUrl: string
  width: number
  height: number
  similarity: number
  similarityPercent: number
}

interface TagOption {
  tagName: string
  count: number
}

const uploadRef = ref<InstanceType<typeof ElUpload>>()
const selectedFile = ref<File | null>(null)
const previewUrl = ref('')
const querySource = ref<'upload' | 'album'>('upload')
const searching = ref(false)
const searchDone = ref(false)
const results = ref<ImageSearchResult[]>([])
const checkedResultIds = ref<string[]>([])
const previewVisible = ref(false)
const previewItem = ref<ImageSearchResult | null>(null)
const selectFromAlbumVisible = ref(false)
const showAddAlbumList = ref(false)
const currentFileId = ref('')
const albumOptions = ref<API.albumInfo[]>([])
const tagOptions = ref<TagOption[]>([])
const searchMode = ref<SearchMode>('fuzzy')
const screenWidth = ref(window.innerWidth)

const filterState = reactive<{
  albumIds: number[]
  tagNames: string[]
  sizeRange: SizeRange
}>({
  albumIds: [],
  tagNames: [],
  sizeRange: '',
})

const modeOptions: Array<{ value: SearchMode; label: string; hint: string; description: string }> = [
  {
    value: 'fuzzy',
    label: '模糊匹配',
    hint: '相似度阈值 ≥ 60%',
    description: '适合查找主题、风格或主体相近的图片。',
  },
  {
    value: 'exact',
    label: '精确匹配',
    hint: '相似度阈值 ≥ 85%',
    description: '适合查找重复图或几乎相同的图片。',
  },
  {
    value: 'local',
    label: '局部匹配',
    hint: '使用 ORB 关键点精排',
    description: '适合查找局部主体相同但构图变化较大的图片。',
  },
]

const sizeOptions: Array<{ label: string; value: SizeRange }> = [
  { label: '全部尺寸', value: '' },
  { label: '小图 (<1MP)', value: 'small' },
  { label: '中图 (1-5MP)', value: 'medium' },
  { label: '大图 (>5MP)', value: 'large' },
]

const currentModeMeta = computed(() =>
  modeOptions.find((item) => item.value === searchMode.value) ?? modeOptions[0],
)
const selectedCount = computed(() => checkedResultIds.value.length)
const allResultsChecked = computed(
  () => results.value.length > 0 && checkedResultIds.value.length === results.value.length,
)
const emptyDescription = computed(() => `当前模式“${currentModeMeta.value.label}”下未找到相似图片`)
const dialogWidth = computed(() => {
  if (screenWidth.value <= 480) return '95vw'
  if (screenWidth.value <= 768) return '90%'
  return '72%'
})
const albumDialogWidth = computed(() => {
  if (screenWidth.value <= 480) return '96vw'
  if (screenWidth.value <= 768) return '94vw'
  return '90%'
})
const albumDialogTop = computed(() => (screenWidth.value <= 768 ? '5vh' : '8vh'))
const checkedImagesForAlbum = computed<string[][]>(() =>
  checkedResultIds.value.length > 0 ? [[...checkedResultIds.value]] : [[]],
)

const updateScreenWidth = () => {
  screenWidth.value = window.innerWidth
}

const handleBusCloseAddAlbum = () => {
  showAddAlbumList.value = false
}

const handleBusAddSelectPicture = async (pictures: API.SelectPicture[]) => {
  if (!Array.isArray(pictures) || pictures.length === 0) {
    return
  }
  selectFromAlbumVisible.value = false
  if (pictures.length > 1) {
    ElMessage.info('已使用首张图片作为查询图继续搜索')
  }
  await hydrateQueryFromPicture(pictures[0])
}

onMounted(async () => {
  window.addEventListener('resize', updateScreenWidth)
  $bus.on('addSelectPicture', handleBusAddSelectPicture)
  $bus.on('closeAddAlbum', handleBusCloseAddAlbum)
  await loadFilterOptions()
})

onUnmounted(() => {
  window.removeEventListener('resize', updateScreenWidth)
  $bus.off('addSelectPicture', handleBusAddSelectPicture)
  $bus.off('closeAddAlbum', handleBusCloseAddAlbum)
  revokePreviewUrl()
})

async function loadFilterOptions() {
  try {
    const [albumRes, tagRes] = await Promise.all([
      selectAllAlbum({ current: 1, size: 200, orderKeyword: 'create_time', orderType: 'desc' }),
      selectAllTags(),
    ])
    if (albumRes.code === 200) {
      albumOptions.value = albumRes.data?.records || []
    }
    if (tagRes.code === 200) {
      tagOptions.value = tagRes.data || []
    }
  } catch (error) {
    console.error('加载搜索筛选项失败:', error)
  }
}

function handleFileChange(uploadFile: any) {
  const file: File | undefined = uploadFile?.raw
  if (!file) return

  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/bmp', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    ElMessage.error('仅支持 JPG / PNG / GIF / BMP / WEBP 格式的图片')
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error('图片大小不能超过 20MB')
    return
  }

  applySelectedFile(file, 'upload')
}

function applySelectedFile(file: File, source: 'upload' | 'album') {
  revokePreviewUrl()
  selectedFile.value = file
  querySource.value = source
  previewUrl.value = URL.createObjectURL(file)
  searchDone.value = false
  results.value = []
  checkedResultIds.value = []
}

async function handleSearch() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择一张查询图片')
    return
  }

  searching.value = true
  searchDone.value = false
  results.value = []
  checkedResultIds.value = []

  try {
    const formData = new FormData()
    formData.append('image', selectedFile.value)
    formData.append('mode', searchMode.value)
    filterState.albumIds.forEach((albumId) => formData.append('albumIds', String(albumId)))
    filterState.tagNames.forEach((tagName) => formData.append('tagNames', tagName))
    if (filterState.sizeRange) {
      formData.append('sizeRange', filterState.sizeRange)
    }

    const res: API.BaseResponse = await searchSimilarImages(formData)
    if (res.code !== 200) {
      ElMessage.error(res.message || '搜索失败，请稍后重试')
      return
    }

    results.value = res.data || []
    searchDone.value = true
    if (results.value.length === 0) {
      ElMessage.info(emptyDescription.value)
    } else {
      ElMessage.success(`搜索完成，共找到 ${results.value.length} 张相似图片`)
    }
  } catch (error: any) {
    console.error('以图搜图失败:', error)
    const msg = error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
    ElMessage.error(`搜索失败：${msg}`)
  } finally {
    searching.value = false
  }
}

function handleReset() {
  revokePreviewUrl()
  selectedFile.value = null
  querySource.value = 'upload'
  searchMode.value = 'fuzzy'
  filterState.albumIds = []
  filterState.tagNames = []
  filterState.sizeRange = ''
  results.value = []
  checkedResultIds.value = []
  searchDone.value = false
  previewVisible.value = false
  previewItem.value = null
  uploadRef.value?.clearFiles()
}

function handlePreview(item: ImageSearchResult) {
  previewItem.value = item
  previewVisible.value = true
}

function toggleResultSelection(fileId: string, checked: boolean) {
  const next = new Set(checkedResultIds.value)
  if (checked) {
    next.add(fileId)
  } else {
    next.delete(fileId)
  }
  checkedResultIds.value = Array.from(next)
}

function toggleCheckAllResults() {
  checkedResultIds.value = allResultsChecked.value ? [] : results.value.map((item) => item.fileId)
}

function openAddAlbum() {
  if (checkedResultIds.value.length === 0) {
    ElMessage.warning('请先勾选搜索结果图片')
    return
  }
  currentFileId.value = checkedResultIds.value[0]
  showAddAlbumList.value = true
}

async function handleBatchDownload() {
  if (checkedResultIds.value.length === 0) {
    ElMessage.warning('请先勾选搜索结果图片')
    return
  }

  const res = await getDownloadToken({ fileIds: checkedResultIds.value })
  if (res.code !== 200) {
    ElMessage.error(res.message || '下载失败')
    return
  }

  const downloadUrl = `${requestPublicConfig.baseUrl}/file/downloadFileByToken?downloadToken=${encodeURIComponent(res.data)}`
  const iframe = document.createElement('iframe')
  iframe.style.display = 'none'
  iframe.src = downloadUrl
  iframe.onload = () => {
    document.body.removeChild(iframe)
    ElMessage.success('下载已开始')
  }
  document.body.appendChild(iframe)
}

async function handleBatchDelete() {
  if (checkedResultIds.value.length === 0) {
    ElMessage.warning('请先勾选搜索结果图片')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定删除已勾选的 ${checkedResultIds.value.length} 张图片吗？`,
      '删除提示',
      { type: 'warning' },
    )
  } catch {
    return
  }

  const deletingIds = new Set(checkedResultIds.value)
  const res = await deleteFileByIds({ fileIds: checkedResultIds.value })
  if (res.code !== 200) {
    ElMessage.error(res.message || '删除失败')
    return
  }

  results.value = results.value.filter((item) => !deletingIds.has(item.fileId))
  checkedResultIds.value = []
  ElMessage.success('删除成功')
}

async function hydrateQueryFromPicture(picture: API.SelectPicture) {
  const candidateUrls = [picture.fileUrl, picture.thumbnailUrl].filter(Boolean) as string[]
  for (const url of candidateUrls) {
    try {
      const response = await fetch(url)
      if (!response.ok) {
        continue
      }
      const blob = await response.blob()
      const file = new File([blob], picture.originFileName || 'album-search-image.jpg', {
        type: picture.contentType || blob.type || 'image/jpeg',
      })
      applySelectedFile(file, 'album')
      await handleSearch()
      return
    } catch (error) {
      console.warn('读取相册图片失败，尝试下一个地址:', error)
    }
  }
  ElMessage.error('从相册读取查询图片失败，请重试')
}

function revokePreviewUrl() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function truncateFileName(name: string): string {
  if (!name) return ''
  return name.length > 20 ? `${name.slice(0, 17)}...` : name
}

function getSimilarityClass(percent: number): string {
  if (percent >= 90) return 'similarity-very-high'
  if (percent >= 80) return 'similarity-high'
  return 'similarity-medium'
}

function getSimilarityColor(percent: number): string {
  if (percent >= 90) return '#67c23a'
  if (percent >= 80) return '#409eff'
  return '#e6a23c'
}
</script>

<style scoped>
.image-search-container {
  padding: clamp(16px, 4vw, 28px);
  max-width: 1280px;
  margin: 0 auto;
  height: auto;
  min-height: 100%;
  overflow-y: visible;
  overflow-x: hidden;
  background: linear-gradient(180deg, #f4f7fb 0%, #eef3ff 100%);
  box-sizing: border-box;
  padding-bottom: calc(24px + env(safe-area-inset-bottom, 0px));
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 6px;
  font-size: clamp(22px, 3vw, 30px);
  color: #1f2a44;
}

.page-desc {
  margin: 0;
  color: #62708a;
  font-size: 14px;
}

.title-icon {
  color: #3174ff;
}

:deep(.search-panel),
:deep(.result-section) {
  border: 1px solid rgba(49, 116, 255, 0.08);
  border-radius: 20px;
}

.search-panel-grid {
  display: grid;
  grid-template-columns: minmax(280px, 360px) 1fr;
  gap: 24px;
  align-items: start;
}

.upload-column {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.image-uploader {
  width: 100%;
}

.image-uploader :deep(.el-upload) {
  width: 100%;
  display: block;
}

.image-uploader :deep(.el-upload-dragger) {
  width: 100%;
  min-height: 280px;
  border: 2px dashed #c6d7ff;
  border-radius: 18px;
  background: linear-gradient(180deg, #f9fbff 0%, #eef4ff 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.upload-icon {
  font-size: 52px;
  color: #7ba5ff;
  margin-bottom: 12px;
}

.upload-text {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
}

.upload-primary {
  font-size: 16px;
  color: #2a3c62;
  font-weight: 600;
}

.upload-hint {
  color: #7b88a3;
  font-size: 13px;
}

.preview-wrapper {
  position: relative;
  width: 100%;
  min-height: 280px;
}

.preview-image {
  width: 100%;
  height: 280px;
  object-fit: contain;
  display: block;
}

.preview-overlay {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 10px 14px;
  background: linear-gradient(180deg, rgba(14, 26, 47, 0) 0%, rgba(14, 26, 47, 0.75) 100%);
  color: #fff;
  font-size: 13px;
}

.query-meta {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  background: #f5f8ff;
  border: 1px solid #dce6ff;
}

.query-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.query-meta-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #29406f;
}

.query-meta-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.control-column {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.control-block {
  padding: 16px 18px;
  border-radius: 16px;
  background: #f8faff;
  border: 1px solid #e0e8ff;
}

.control-title {
  font-size: 15px;
  font-weight: 600;
  color: #20314f;
  margin-bottom: 12px;
}

.mode-group {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.mode-group :deep(.el-radio-button__inner) {
  border-radius: 999px;
}

.control-tip {
  margin: 10px 0 0;
  font-size: 13px;
  color: #6f7f9f;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.action-buttons :deep(.el-button),
.batch-toolbar-actions :deep(.el-button) {
  min-height: 44px;
  border-radius: 14px;
}

.result-section {
  margin-top: 20px;
  max-height: 72vh;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-gutter: stable;
}

.result-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.result-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #223250;
}

.result-summary strong {
  color: #3174ff;
}

.result-summary-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #73819b;
  font-size: 13px;
}

.batch-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 18px;
  padding: 14px 16px;
  border-radius: 16px;
  background: linear-gradient(90deg, rgba(49, 116, 255, 0.08) 0%, rgba(49, 116, 255, 0.03) 100%);
}

.batch-toolbar-title {
  color: #24447a;
  font-weight: 600;
}

.batch-toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.result-card {
  position: relative;
  border-radius: 18px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e8eefc;
  cursor: pointer;
  transition: transform 0.24s ease, box-shadow 0.24s ease;
}

.result-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 14px 28px rgba(34, 50, 80, 0.12);
}

.result-checkbox {
  position: absolute;
  left: 12px;
  top: 12px;
  z-index: 2;
  padding: 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
}

.card-image-wrapper {
  position: relative;
  aspect-ratio: 1 / 1;
  background: #eef3ff;
}

.card-image {
  width: 100%;
  height: 100%;
}

.card-image :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.similarity-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.similarity-very-high {
  background: rgba(103, 194, 58, 0.9);
}

.similarity-high {
  background: rgba(64, 158, 255, 0.9);
}

.similarity-medium {
  background: rgba(230, 162, 60, 0.9);
}

.card-info {
  padding: 14px;
}

.card-filename {
  font-size: 14px;
  font-weight: 600;
  color: #1f2f4d;
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: #7b88a3;
  font-size: 12px;
  margin-bottom: 10px;
}

.image-error {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #94a3c4;
}

.empty-hint {
  color: #93a0b8;
  font-size: 13px;
  margin-top: 8px;
}

.album-select-tip {
  margin-bottom: 12px;
  color: #6d7a95;
}

.album-select-panel {
  height: min(68vh, calc(100vh - 210px));
  min-height: 360px;
  overflow: hidden;
  overflow-x: hidden;
  padding-right: 4px;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  display: flex;
  flex-direction: column;
}

:deep(.album-select-dialog.el-dialog),
:deep(.album-select-dialog .el-dialog) {
  max-height: 84vh;
  overflow: hidden;
}

:deep(.album-select-dialog.el-dialog .el-dialog__body),
:deep(.album-select-dialog .el-dialog__body) {
  max-height: calc(84vh - 110px);
  overflow: hidden;
  overscroll-behavior: contain;
}

:deep(.album-select-panel #picture) {
  flex: 1 1 0;
  height: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

:deep(.album-select-panel #picture-list) {
  flex: 1 1 0;
  height: auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-y;
}

.preview-dialog-content {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 240px;
  background: #09111f;
  border-radius: 10px;
  overflow: hidden;
}

.preview-full-image {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
}

.preview-dialog-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.similarity-tag {
  color: #fff !important;
  border: none;
}

.preview-filename {
  color: #55637f;
}

@media (max-width: 992px) {
  .search-panel-grid {
    grid-template-columns: 1fr;
    gap: 16px;
  }

  .filter-grid {
    grid-template-columns: 1fr;
  }

  .result-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .result-summary-meta {
    flex-wrap: wrap;
  }

  .result-section {
    max-height: none;
    overflow: visible;
  }
}

@media (max-width: 768px) {
  .image-search-container {
    padding: 12px;
    min-height: calc(100% + 1px);
    overflow-y: visible;
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .page-title {
    font-size: 22px;
  }

  .page-desc {
    font-size: 13px;
    line-height: 1.6;
  }

  .control-block {
    padding: 14px;
  }

  .mode-group {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .mode-group :deep(.el-radio-button),
  .mode-group :deep(.el-radio-button__inner) {
    width: 100%;
  }

  .action-buttons,
  .batch-toolbar-actions {
    width: 100%;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .action-buttons :deep(.el-button),
  .batch-toolbar-actions :deep(.el-button) {
    width: 100%;
    min-width: 0;
  }

  .result-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .album-select-panel {
    min-height: 0;
    height: calc(78vh - 132px);
    max-height: none;
    padding-right: 0;
  }

  :deep(.album-select-dialog.el-dialog),
  :deep(.album-select-dialog .el-dialog) {
    max-height: 78vh;
  }

  :deep(.album-select-dialog.el-dialog .el-dialog__body),
  :deep(.album-select-dialog .el-dialog__body) {
    max-height: calc(78vh - 104px);
    padding-bottom: 12px;
  }

  .preview-dialog-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 480px) {
  .page-title {
    font-size: 20px;
    gap: 8px;
  }

  .image-search-container {
    padding-top: calc(12px + env(safe-area-inset-top, 0px));
  }

  .search-panel :deep(.el-card__body),
  .result-section :deep(.el-card__body) {
    padding: 14px;
  }

  .mode-group,
  .action-buttons,
  .batch-toolbar-actions {
    grid-template-columns: 1fr;
  }

  .batch-toolbar {
    padding: 12px;
  }

  .result-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .card-meta {
    flex-direction: column;
    align-items: flex-start;
  }

  .preview-image,
  .image-uploader :deep(.el-upload-dragger),
  .preview-wrapper {
    min-height: 200px;
    height: 200px;
  }

  .album-select-panel {
    height: calc(80vh - 128px);
  }

  :deep(.album-select-dialog .el-dialog) {
    max-height: 80vh;
  }

  :deep(.album-select-dialog .el-dialog__body) {
    max-height: calc(80vh - 100px);
    padding: 12px;
  }
}
</style>
