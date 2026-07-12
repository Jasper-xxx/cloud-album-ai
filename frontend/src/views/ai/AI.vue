<template>
  <div id="picture" class="content ai-page">
    <div class="content-header">
      <div class="content-header-left">
        <h3 class="page-title">图像分类</h3>
        <el-button @click="dialogPicutureVisible = true" type="success" round class="content-header-left-button">
          <span class="content-header-button-span">{{ tableData.length > 0 ? '重新选择' : '选择照片' }}</span>
        </el-button>

        <span class="header-label">标签数量：</span>
        <el-input-number
          class="tag-count-input"
          style="width: 120px"
          v-model="tagCount"
          @change="handleTagNumChange"
          :precision="0"
          :step="1"
          :min="1"
          :max="10"
        />

        <el-tooltip
          content="开启后将自动保存每张图片置信度最高的识别标签"
          :hide-after="1000"
          placement="right"
          effect="customized"
        >
          <el-switch class="auto-tag-switch" style="margin-left: 20px" v-model="IsAutoAddTag" active-text="自动" inactive-text="手动" />
        </el-tooltip>
      </div>

      <div class="content-header-right">
        <el-button @click="startGetPictureTag" type="primary" round class="content-header-left-button">
          <span class="content-header-button-span">{{ IsAI ? '重新识别' : '开始识别' }}</span>
        </el-button>
        <el-button @click="setAllPictureTag" type="primary" round>
          设置全部
        </el-button>
      </div>
    </div>

    <el-divider />

    <div v-if="tableData.length > 0" class="batch-summary-card">
      <div class="batch-summary-top">
        <div class="summary-stats">
          <span>已选择 {{ tableData.length }} 张图片</span>
          <span>已完成 {{ recognitionState.processed }} 张</span>
          <span>成功 {{ recognitionState.success }} 张</span>
          <span>失败 {{ recognitionState.failed }} 张</span>
        </div>
        <div class="summary-actions">
          <el-button v-if="failedRecognitionRows.length > 0" size="small" @click="retryFailedRecognition">
            重试失败项
          </el-button>
          <el-button
            v-if="canOpenBatchClassification"
            size="small"
            type="primary"
            plain
            @click="openClassificationDialog(tableData)"
          >
            智能归类
          </el-button>
        </div>
      </div>

      <el-progress :percentage="recognitionPercentage" :status="recognitionState.failed > 0 ? 'warning' : 'success'" />

      <div v-if="recommendedTags.length > 0" class="tag-statistics">
        <span class="tag-statistics-label">推荐标签：</span>
        <el-tag v-for="item in recommendedTags" :key="item.tag" type="success" effect="plain">
          {{ item.label }} · {{ item.count }}
        </el-tag>
      </div>
    </div>

    <div id="picture-table">
      <div v-if="isCompact" class="ai-card-list">
        <div v-if="paginatedData.length === 0" class="ai-empty">
          <el-empty description="暂无数据，请先选择图片" />
        </div>

        <div v-for="(row, idx) in paginatedData" :key="row.fileId" class="ai-card">
          <div class="ai-card-head">
            <el-image
              :src="row.thumbnailUrl"
              class="thumbnail"
              fit="cover"
              @click="() => { isPreviewImage = true; currentImageIndex = (currentPage - 1) * pageSize + idx }"
            />
            <div class="ai-card-meta">
              <div class="ai-card-name" :title="row.originFileName">{{ row.originFileName }}</div>
              <div class="ai-card-tags">
                <el-tag size="small" type="info">{{ row.contentType }}</el-tag>
                <el-tag size="small" type="warning">{{ formatFileSize(row.size) }}</el-tag>
              </div>
            </div>
          </div>

          <div class="ai-card-body">
            <div class="ai-card-section-title">识别结果（含置信度）</div>
            <div
              v-loading="loadingStates[(currentPage - 1) * pageSize + idx]"
              element-loading-background="rgba(255, 255, 255, 0)"
              element-loading-text="识别中..."
              class="ai-card-result"
            >
              <el-radio-group
                v-if="!loadingStates[(currentPage - 1) * pageSize + idx]"
                v-model="row.selectedTagIndex"
                @change="handleTagChange(row)"
                class="ai-card-radio"
              >
                <template v-for="index in tagCount" :key="index - 1">
                  <el-radio
                    v-if="row.tags.length > 0 && index - 1 < row.tags.length"
                    :value="index - 1"
                    class="ai-card-radio-item"
                  >
                    <span class="ai-tag-main">{{ row.tags[index - 1]?.imageType }}</span>
                    <span v-if="row.tags[index - 1]?.tagName !== row.tags[index - 1]?.imageType" class="ai-tag-sub">
                      / {{ row.tags[index - 1]?.tagName }}
                    </span>
                    <span class="ai-tag-conf">{{ formatConfidence(row.tags[index - 1]?.confidence) }}</span>
                  </el-radio>
                </template>
              </el-radio-group>

              <div v-else style="height: 64px" />
            </div>
          </div>

          <div class="ai-card-actions">
            <el-button
              type="primary"
              plain
              :disabled="loadingStates[(currentPage - 1) * pageSize + idx]"
              @click="getOnePictureTag(row, (currentPage - 1) * pageSize + idx)"
            >
              识别
            </el-button>
            <el-button type="primary" :disabled="row.tags.length === 0" @click="setOnePictureTag(row)">
              设置标签
            </el-button>
          </div>
        </div>

        <div class="pagination-container">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[5, 8, 10, 15, 20, 30, 50]"
            :total="tableData.length"
            layout="total, sizes, prev, pager, next"
            background
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
          />
        </div>
      </div>

      <template v-else>
        <el-table
          :header-cell-style="{ 'text-align': 'center' }"
          :data="paginatedData"
          class="picture-table-data"
          :row-class-name="tableRowClassName"
          empty-text="暂无数据，请先选择图片"
        >
          <el-table-column prop="thumbnailUrl" label="图片" width="90">
            <template #default="{ row, $index }">
              <el-tooltip placement="bottom">
                <template #content>预览图片</template>
                <el-image
                  :src="row.thumbnailUrl"
                  class="thumbnail"
                  style="cursor: pointer"
                  @click="() => { isPreviewImage = true; currentImageIndex = (currentPage - 1) * pageSize + $index }"
                />
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="contentType" label="照片类型" width="110" />
          <el-table-column prop="size" label="照片大小" width="90" :formatter="(row: any) => formatFileSize(row.size)" />
          <el-table-column prop="originFileName" label="照片名称" width="160" show-overflow-tooltip />
          <el-table-column prop="tags" label="AI识别结果（类型 / 置信度）">
            <template #default="{ row, $index }">
              <div
                v-loading="loadingStates[(currentPage - 1) * pageSize + $index]"
                element-loading-background="rgba(255, 255, 255, 0)"
                element-loading-text="识别中..."
                style="height: 80px; overflow: auto"
              >
                <el-radio-group
                  v-if="!loadingStates[(currentPage - 1) * pageSize + $index]"
                  v-model="row.selectedTagIndex"
                  @change="handleTagChange(row)"
                  class="tag-container"
                >
                  <template v-for="index in tagCount" :key="index - 1">
                    <el-radio v-if="row.tags.length > 0 && index - 1 < row.tags.length" :value="index - 1">
                      <el-text type="primary" style="margin-left: 4px">{{ row.tags[index - 1]?.imageType }}</el-text>
                      <el-text
                        v-if="row.tags[index - 1]?.tagName !== row.tags[index - 1]?.imageType"
                        type="success"
                        style="margin-left: 4px"
                      >
                        / {{ row.tags[index - 1]?.tagName }}
                      </el-text>
                      <el-text type="warning" style="margin-left: 4px">
                        {{ formatConfidence(row.tags[index - 1]?.confidence) }}
                      </el-text>
                    </el-radio>
                  </template>
                </el-radio-group>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="识别照片" width="90">
            <template #default="{ row, $index }">
              <el-button type="primary" round color="#626aef" @click="getOnePictureTag(row, (currentPage - 1) * pageSize + $index)">
                <el-icon><ZoomIn /></el-icon>
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="设置标签" width="90">
            <template #default="{ row }">
              <el-button type="primary" round color="#626aef" @click="setOnePictureTag(row)" :disabled="row.tags.length === 0">
                <el-icon><Setting /></el-icon>
              </el-button>
            </template>
          </el-table-column>
          <el-table-column width="20" />
        </el-table>

        <div class="pagination-container">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[5, 8, 10, 15, 20, 30, 50]"
            :total="tableData.length"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
          />
        </div>
      </template>
    </div>

    <el-image-viewer
      v-if="isPreviewImage"
      :url-list="previewSrcList"
      :teleported="true"
      :initial-index="currentImageIndex"
      @close="isPreviewImage = false"
    />

    <el-dialog class="select-picture-diag" width="90%" v-model="dialogPicutureVisible" title="选择照片" :append-to-body="true" @closed="handleDialogClosed">
      <el-tabs v-model="activeName" @tab-click="handleTabClick" style="height: 100%; width: 100%">
        <el-tab-pane label="照片" name="picture" style="height: 100%; width: 100%">
          <SelectPicture :imageOnly="true" :tagFilterEnabled="true" />
        </el-tab-pane>

        <el-tab-pane label="相册" name="album" style="height: 100%; width: 100%">
          <div v-if="currentAlbumId === null" class="album-list-wrap" v-loading="albumLoading">
            <el-empty v-if="!albumLoading && albumList.length === 0" description="暂无相册，请先创建相册并上传照片" />
            <div class="album-grid">
              <div v-for="album in albumList" :key="album.albumId" class="album-card" @click="enterAlbum(album)">
                <el-image :src="album.coverUrl || defaultCover" class="album-cover" fit="cover" />
                <div class="album-card-info">
                  <span class="album-card-name">{{ album.albumName }}</span>
                  <span class="album-card-count">{{ (album.imageCount || 0) + (album.videoCount || 0) }} 张</span>
                </div>
              </div>
            </div>
          </div>

          <div v-else style="height: 100%; display: flex; flex-direction: column">
            <div class="album-back-bar">
              <el-button size="small" text @click="currentAlbumId = null">
                <i-ep-arrow-left /> 返回相册列表
              </el-button>
              <span class="album-back-name">{{ currentAlbumName }}</span>
            </div>
            <div style="flex: 1; overflow: hidden">
              <SelectPicture :imageOnly="true" :albumIdProp="currentAlbumId" :tagFilterEnabled="true" />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <SmartAlbumClassificationDialog
      v-model:show="showClassificationDialog"
      :file-id="classificationFileId"
      :tags="classificationTags"
      :thumbnail-url="classificationThumbnailUrl"
      :file-items="classificationFileItems"
      :file-tags-map="classificationTagsMap"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Setting, ZoomIn } from '@element-plus/icons-vue'
import SelectPicture from '@/components/select_picture/SelectPicture.vue'
import SmartAlbumClassificationDialog from '@/components/album/SmartAlbumClassificationDialog.vue'
import $bus from '@/utils/bus.ts'
import { addPictureTag, addSomePictureTag, batchGetPictureTag, getPictureTag, selectTagByFileId } from '@/api/file/file'
import { getAsyncTask } from '@/api/task/task'
import { selectAllAlbum } from '@/api/album/album'
import defaultCover from '@/assets/image/album.png'

const tableData = ref<API.SelectPicture[]>([])
const isPreviewImage = ref(false)
const currentImageIndex = ref(0)
const showClassificationDialog = ref(false)
const classificationFileId = ref('')
const classificationTags = ref<string[]>([])
const classificationThumbnailUrl = ref('')
const classificationFileItems = ref<Array<{ fileId: string; thumbnailUrl?: string; originFileName?: string }>>([])
const classificationTagsMap = ref<Record<string, string[]>>({})

const IsAI = ref(false)
const currentPage = ref(1)
const pageSize = ref(5)
const loadingStates = ref<boolean[]>([])
const IsAutoAddTag = ref(false)
const tagCount = ref(10)
const isCompact = ref(false)
const dialogPicutureVisible = ref(false)
const activeName = ref('picture')
const albumList = ref<any[]>([])
const albumLoading = ref(false)
const currentAlbumId = ref<number | null>(null)
const currentAlbumName = ref('')
const failedRecognitionFileIds = ref<string[]>([])
const recognitionState = ref({
  running: false,
  total: 0,
  processed: 0,
  success: 0,
  failed: 0,
})
let activeRecognitionController: AbortController | null = null

const previewSrcList = computed(() => tableData.value.map((item) => item.thumbnailUrl))
const paginatedData = computed(() => tableData.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value))
const recognitionPercentage = computed(() => {
  if (!recognitionState.value.total) {
    return 0
  }
  return Math.round((recognitionState.value.processed / recognitionState.value.total) * 100)
})
const failedRecognitionRows = computed(() => tableData.value.filter((row) => failedRecognitionFileIds.value.includes(row.fileId)))
const canOpenBatchClassification = computed(() => tableData.value.length > 0)
const recommendedTags = computed(() => {
  const counter = new Map<string, { label: string; count: number }>()
  tableData.value.forEach((row) => {
    if (!row.tags.length) {
      return
    }
    const selected = row.tags[row.selectedTagIndex] || row.tags[0]
    if (!selected) {
      return
    }
    const key = selected.tagName || selected.imageType
    const label = selected.imageType === selected.tagName ? selected.imageType : `${selected.imageType}/${selected.tagName}`
    const current = counter.get(key)
    counter.set(key, { label, count: (current?.count || 0) + 1 })
  })
  return Array.from(counter.entries())
    .map(([tag, value]) => ({ tag, ...value }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 8)
})

const showBus = () => {
  $bus.off('addSelectPicture')
  $bus.on('addSelectPicture', (val) => {
    cancelActiveRecognition()
    tableData.value = val as API.SelectPicture[]
    loadingStates.value = Array(tableData.value.length).fill(false)
    currentPage.value = 1
    dialogPicutureVisible.value = false
  })
}

const updateCompact = () => {
  isCompact.value = window.innerWidth <= 992
}

onMounted(() => {
  showBus()
  updateCompact()
  window.addEventListener('resize', updateCompact)
})

onUnmounted(() => {
  cancelActiveRecognition()
  $bus.off('addSelectPicture')
  window.removeEventListener('resize', updateCompact)
})

watch(
  () => tableData.value.length,
  (length) => {
    loadingStates.value = Array(length).fill(false)
  },
  { immediate: true }
)

const handleSizeChange = (newSize: number) => {
  pageSize.value = newSize
  currentPage.value = 1
}

const handleCurrentChange = (newPage: number) => {
  currentPage.value = newPage
}

const tableRowClassName = ({ rowIndex }: { rowIndex: number }) => (rowIndex % 2 === 0 ? 'even-row' : 'odd-row')

const handleTagNumChange = () => {
  tableData.value.forEach((item) => {
    if (item.selectedTagIndex >= tagCount.value) {
      item.selectedTagIndex = 0
    }
  })
}

const loadAlbums = async () => {
  if (albumLoading.value) {
    return
  }
  albumLoading.value = true
  try {
    const res = await selectAllAlbum({ current: 1, size: 200, orderKeyword: 'create_time', orderType: 'desc' })
    if (res.code === 200) {
      albumList.value = res.data?.records ?? res.data ?? []
    } else {
      ElMessage.error(res.message || '加载相册失败')
    }
  } catch {
    ElMessage.error('网络异常，加载相册失败')
  } finally {
    albumLoading.value = false
  }
}

const enterAlbum = (album: any) => {
  currentAlbumId.value = album.albumId
  currentAlbumName.value = album.albumName
}

const handleTabClick = (tab: any) => {
  if (tab.paneName === 'album' && albumList.value.length === 0) {
    loadAlbums()
  }
}

const handleDialogClosed = () => {
  currentAlbumId.value = null
  activeName.value = 'picture'
}

const handleTagChange = (_row: API.SelectPicture) => {
  // 保留选择态，由界面直接反映即可
}

const formatConfidence = (value: number | string | undefined) => {
  const num = Number(value)
  if (!Number.isFinite(num)) {
    return ''
  }
  return `${Math.round(num)}%`
}

const formatFileSize = (bytes: number) => {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = Math.abs(bytes)
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(1).replace(/\.0$/, '')} ${units[unitIndex]}`
}

const setRowLoading = (fileId: string, loading: boolean) => {
  const index = tableData.value.findIndex((row) => row.fileId === fileId)
  if (index >= 0) {
    loadingStates.value[index] = loading
  }
}

const chunkRows = (rows: API.SelectPicture[], size: number) => {
  const result: API.SelectPicture[][] = []
  for (let i = 0; i < rows.length; i += size) {
    result.push(rows.slice(i, i + size))
  }
  return result
}

const TAG_TASK_POLL_INTERVAL_MS = 800
const TAG_TASK_TIMEOUT_MS = 180000
const TAG_TASK_BATCH_SIZE = 2
const TAG_TASK_BATCH_CONCURRENCY = 2

const isRecognitionCancelled = (error: unknown) => {
  if (!error || typeof error !== 'object') {
    return false
  }
  const candidate = error as { name?: string; code?: string }
  return candidate.name === 'AbortError'
    || candidate.name === 'CanceledError'
    || candidate.code === 'ERR_CANCELED'
}

const cancelActiveRecognition = () => {
  activeRecognitionController?.abort()
  activeRecognitionController = null
  recognitionState.value.running = false
}

const beginRecognition = () => {
  cancelActiveRecognition()
  const controller = new AbortController()
  activeRecognitionController = controller
  return controller
}

const wait = (milliseconds: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Recognition cancelled', 'AbortError'))
      return
    }
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', handleAbort)
      resolve()
    }, milliseconds)
    const handleAbort = () => {
      window.clearTimeout(timer)
      reject(new DOMException('Recognition cancelled', 'AbortError'))
    }
    signal.addEventListener('abort', handleAbort, { once: true })
  })

const waitForImageTagTask = async (
  taskId: number,
  fileId: string,
  signal: AbortSignal,
): Promise<API.BatchGetPictureTagItem> => {
  const startedAt = Date.now()
  while (Date.now() - startedAt < TAG_TASK_TIMEOUT_MS) {
    const response = await getAsyncTask(taskId, signal)
    if (response.code !== 200 || !response.data) {
      throw new Error(response.message || '任务状态查询失败')
    }

    const task = response.data as API.AsyncTaskDetail
    if (task.status === 'SUCCESS') {
      return {
        taskId,
        fileId,
        tags: Array.isArray(task.result) ? task.result : [],
        status: task.status,
        success: true,
      }
    }
    if (task.status === 'DEAD') {
      return {
        taskId,
        fileId,
        tags: [],
        status: task.status,
        success: false,
        error: task.lastError || '识别任务失败',
      }
    }
    await wait(TAG_TASK_POLL_INTERVAL_MS, signal)
  }

  return {
    taskId,
    fileId,
    tags: [],
    status: 'FAILED',
    success: false,
    error: '识别任务等待超时，请稍后重试',
  }
}

const applyRecognitionItem = (item: API.BatchGetPictureTagItem) => {
  const row = tableData.value.find((entry) => entry.fileId === item.fileId)
  if (!row) {
    return
  }
  recognitionState.value.processed += 1
  if (item.success) {
    row.tags = item.tags || []
    row.selectedTagIndex = 0
    recognitionState.value.success += 1
    failedRecognitionFileIds.value = failedRecognitionFileIds.value.filter((id) => id !== item.fileId)
  } else {
    recognitionState.value.failed += 1
    if (!failedRecognitionFileIds.value.includes(item.fileId)) {
      failedRecognitionFileIds.value.push(item.fileId)
    }
  }
}

const markBatchFailed = (rows: API.SelectPicture[], message?: string) => {
  rows.forEach((row) => {
    recognitionState.value.processed += 1
    recognitionState.value.failed += 1
    if (!failedRecognitionFileIds.value.includes(row.fileId)) {
      failedRecognitionFileIds.value.push(row.fileId)
    }
  })
  if (message) {
    ElMessage.error(message)
  }
}

const recognizeRowsInBatch = async (rows: API.SelectPicture[]) => {
  if (rows.length === 0) {
    return
  }

  const controller = beginRecognition()
  recognitionState.value = {
    running: true,
    total: rows.length,
    processed: 0,
    success: 0,
    failed: 0,
  }
  failedRecognitionFileIds.value = []

  const batches = chunkRows(rows, TAG_TASK_BATCH_SIZE)
  let cursor = 0

  const worker = async () => {
    while (cursor < batches.length && !controller.signal.aborted) {
      const batchIndex = cursor
      cursor += 1
      const batch = batches[batchIndex]
      batch.forEach((row) => setRowLoading(row.fileId, true))
      try {
        const res = await batchGetPictureTag({
          fileIds: batch.map((row) => row.fileId),
          autoAddTag: IsAutoAddTag.value,
        })
        const submittedItems = (res.data?.items || []) as API.BatchGetPictureTagItem[]
        if (res.code === 200 && submittedItems.length > 0) {
          const completedItems = await Promise.all(
            submittedItems.map((item) => {
              if (!item.success || !item.taskId) {
                return Promise.resolve(item)
              }
              return waitForImageTagTask(item.taskId, item.fileId, controller.signal)
            }),
          )
          completedItems.forEach(applyRecognitionItem)
        } else {
          markBatchFailed(batch, res.message || '批量识别失败')
        }
      } catch (error) {
        if (isRecognitionCancelled(error)) {
          return
        }
        markBatchFailed(batch, '批量识别失败')
      } finally {
        batch.forEach((row) => setRowLoading(row.fileId, false))
      }
    }
  }

  const concurrency = Math.min(TAG_TASK_BATCH_CONCURRENCY, batches.length)
  await Promise.all(Array.from({ length: concurrency }, () => worker()))
  if (activeRecognitionController !== controller || controller.signal.aborted) {
    return
  }
  activeRecognitionController = null
  recognitionState.value.running = false
  IsAI.value = true

  if (recognitionState.value.failed > 0) {
    ElMessage.warning(`识别完成，成功 ${recognitionState.value.success} 张，失败 ${recognitionState.value.failed} 张`)
  } else {
    ElMessage.success(`识别完成，共 ${recognitionState.value.success} 张`)
  }
}

const startGetPictureTag = async () => {
  if (tableData.value.length === 0) {
    ElMessage.warning('请先选择图片')
    return
  }
  await recognizeRowsInBatch(tableData.value)
}

const retryFailedRecognition = async () => {
  if (failedRecognitionRows.value.length === 0) {
    return
  }
  await recognizeRowsInBatch(failedRecognitionRows.value)
}

const getOnePictureTag = async (row: API.SelectPicture, index: number) => {
  if (tableData.value.length === 0) {
    ElMessage.warning('请先选择图片')
    return
  }

  const controller = beginRecognition()
  loadingStates.value[index] = true
  try {
    const result = await getPictureTag({
      autoAddTag: IsAutoAddTag.value,
      fileId: row.fileId,
      thumbnailObjectName: row.thumbnailObjectName,
    })
    const submission = result.data as API.ImageTagTaskSubmission
    if (result.code !== 200 || !submission?.taskId) {
      throw new Error(result.message || '识别任务提交失败')
    }
    const completed = await waitForImageTagTask(
      submission.taskId,
      row.fileId,
      controller.signal,
    )
    if (!completed.success) {
      throw new Error(completed.error || '图片识别失败')
    }
    row.tags = completed.tags || []
    row.selectedTagIndex = 0
  } catch (error) {
    if (isRecognitionCancelled(error)) {
      return
    }
    ElMessage.error(`图片 ${row.originFileName} 识别失败`)
  } finally {
    if (activeRecognitionController === controller) {
      activeRecognitionController = null
    }
    loadingStates.value[index] = false
  }
}

const openClassificationDialog = async (rows: API.SelectPicture[]) => {
  if (rows.length === 0) {
    ElMessage.warning('请先选择图片')
    return
  }

  const results = await Promise.all(
    rows.map(async (row) => {
      const res = await selectTagByFileId({ fileId: row.fileId })
      return [row.fileId, res.code === 200 ? (res.data || []) : []] as const
    })
  )

  classificationTagsMap.value = Object.fromEntries(results)
  classificationFileItems.value = rows.map((row) => ({
    fileId: row.fileId,
    thumbnailUrl: row.thumbnailUrl,
    originFileName: row.originFileName,
  }))

  if (rows.length === 1) {
    classificationFileId.value = rows[0].fileId
    classificationTags.value = classificationTagsMap.value[rows[0].fileId] || []
    classificationThumbnailUrl.value = rows[0].thumbnailUrl
  } else {
    classificationFileId.value = ''
    classificationTags.value = []
    classificationThumbnailUrl.value = ''
  }

  showClassificationDialog.value = true
}

const setAllPictureTag = async () => {
  if (tableData.value.length === 0) {
    ElMessage.warning('没有选择图片')
    return
  }

  const pictureTags: API.PictureTag[] = []
  tableData.value.forEach((item, index) => {
    const selectedTag = item.tags[item.selectedTagIndex]
    if (selectedTag) {
      pictureTags.push({
        id: index,
        fileId: item.fileId,
        tagName: selectedTag.tagName,
        imageType: selectedTag.imageType,
      })
    }
  })

  if (pictureTags.length === 0) {
    ElMessage.warning('请先完成识别并选择标签')
    return
  }

  try {
    await ElMessageBox.confirm('确认设置所选图片的标签吗？', '设置标签', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return
  }

  const res = await addSomePictureTag(pictureTags)
  if (res.code === 200) {
    ElMessage.success('设置成功')
  } else {
    ElMessage.error(res.message || '设置失败')
  }
}

const setOnePictureTag = async (row: API.SelectPicture) => {
  if (row.tags.length === 0) {
    ElMessage.warning('没有识别标签')
    return
  }

  const selected = row.tags[row.selectedTagIndex]
  const labelDesc = selected.imageType === selected.tagName
    ? `${selected.imageType}  置信度 ${selected.confidence}%`
    : `${selected.imageType} / ${selected.tagName}  置信度 ${selected.confidence}%`

  try {
    await ElMessageBox.confirm(labelDesc, '设置标签', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    ElMessage.info('取消设置')
    return
  }

  const res = await addPictureTag({
    fileIds: [row.fileId],
    tagName: selected.tagName,
    imageType: selected.imageType,
  })

  if (res.code === 200) {
    ElMessage.success('设置成功')
  } else {
    ElMessage.error(res.message || '设置失败')
  }
}
</script>

<style scoped>
.ai-page {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

#picture-table {
  width: 100%;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
  padding-bottom: 12px;
}

.picture-table-data {
  width: 100%;
  height: auto;
}

.pagination-container {
  position: sticky;
  bottom: 0;
  left: auto;
  display: flex;
  justify-content: flex-end;
  padding: 12px 0 4px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), #fff 28%);
  z-index: 2;
}

.thumbnail {
  width: 60px;
  height: 60px;
  object-fit: cover;
  border-radius: 4px;
}

.tag-container {
  display: flex;
  flex-wrap: wrap;
  column-gap: 20px;
}

.ai-card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 10px 10px 72px;
  box-sizing: border-box;
}

.ai-empty {
  padding: 24px 0;
}

.ai-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  padding: 12px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.03);
}

.ai-card-head {
  display: flex;
  gap: 10px;
  align-items: center;
}

.ai-card-meta {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-card-name {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.ai-card-body {
  margin-top: 10px;
}

.ai-card-section-title {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 6px;
}

.ai-card-result {
  border-radius: 10px;
  background: #f9fafb;
  padding: 10px;
  min-height: 72px;
}

.ai-card-radio {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-card-radio-item {
  margin-right: 0 !important;
  white-space: normal;
  line-height: 1.25;
}

.ai-tag-main {
  color: #2563eb;
  font-weight: 600;
  margin-left: 4px;
}

.ai-tag-sub {
  color: #059669;
  margin-left: 4px;
}

.ai-tag-conf {
  color: #d97706;
  margin-left: 6px;
  font-weight: 600;
}

.ai-card-actions {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

.ai-card-actions :deep(.el-button) {
  flex: 1;
  min-height: 44px;
  border-radius: 10px;
}

@media (max-width: 992px) {
  .content {
    height: auto;
    min-height: 100%;
  }

  .content-header {
    min-height: auto;
    align-items: stretch;
    flex-direction: column;
    padding-bottom: 4px;
  }

  .content-header-left,
  .content-header-right {
    width: 100%;
    margin-right: 0;
    justify-content: flex-start;
  }

  .page-title {
    margin-left: 0;
  }

  .content-header-left-button,
  .header-label,
  .auto-tag-switch {
    margin-left: 0 !important;
  }

  .tag-count-input {
    width: min(180px, 100%) !important;
  }

  .batch-summary-top {
    flex-direction: column;
  }

  .summary-actions {
    width: 100%;
  }

  .summary-actions :deep(.el-button) {
    min-width: 128px;
  }

  .ai-page {
    overflow-y: auto;
  }

  #picture-table {
    flex: initial;
    min-height: auto;
    overflow: visible;
  }

  .pagination-container {
    position: relative;
    bottom: auto;
    margin-top: 8px;
    display: flex;
    justify-content: center;
  }

  .pagination-container :deep(.el-pagination) {
    justify-content: center;
    flex-wrap: wrap;
    row-gap: 8px;
  }
}

@media (max-width: 576px) {
  .content-header-left {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }

  .content-header-right {
    flex-direction: row;
    align-items: center;
    flex-wrap: nowrap;
    gap: 10px;
    width: 100%;
  }

  .content-header-left > *,
  .content-header-right > * {
    margin-left: 0 !important;
    margin-right: 0 !important;
  }

  .content-header-left > .content-header-left-button,
  .tag-count-input {
    width: 100% !important;
  }

  .content-header-right .content-header-left-button,
  .content-header-right :deep(.el-button) {
    flex: 1 1 0;
    width: auto !important;
    min-width: 0;
  }

  .header-label {
    width: 100%;
  }

  .auto-tag-switch {
    align-self: flex-start;
  }

  .summary-actions :deep(.el-button),
  .ai-card-actions :deep(.el-button) {
    width: 100%;
  }

  .ai-card-actions {
    flex-direction: column;
  }

  .album-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .album-card-info {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .album-card-name {
    max-width: none;
  }

  .album-back-bar {
    flex-wrap: wrap;
    align-items: flex-start;
  }
}

.content {
  width: 100%;
  height: 100%;
  position: relative;
}

.content-header {
  z-index: 4;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 60px;
}

.page-title {
  margin: 0 0 0 20px;
  white-space: nowrap;
}

.header-label {
  margin-left: 20px;
  white-space: nowrap;
}

.content-header-left-button {
  width: 100px;
  min-width: 100px;
  margin-left: 20px;
}

.content-header-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 0;
  margin-right: auto;
}

.content-header-right {
  width: auto;
  min-width: 220px;
  margin-right: 10px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: nowrap;
  gap: 10px;
  flex-shrink: 0;
}

.content-header-right .content-header-left-button,
.content-header-right :deep(.el-button) {
  width: 128px;
  min-width: 128px;
  height: 44px;
  margin-left: 0 !important;
  white-space: nowrap;
}

.batch-summary-card {
  margin: 0 0 14px;
  padding: 14px 16px;
  border-radius: 14px;
  background: linear-gradient(135deg, #f8fbff 0%, #eef5ff 100%);
  border: 1px solid #dbe8ff;
}

.batch-summary-top {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.summary-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  color: #334155;
  font-size: 14px;
}

.summary-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-statistics {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.tag-statistics-label {
  color: #475569;
  font-size: 13px;
}

.el-divider--horizontal {
  margin-top: 0;
  margin-bottom: 14px;
}

:deep(.el-table .even-row) {
  background-color: #fafafa;
}

:deep(.el-table .odd-row) {
  background-color: #ffffff;
}

.select-picture-diag {
  height: 500px;
}

.album-list-wrap {
  height: 100%;
  overflow-y: auto;
  padding: 10px 4px;
}

.album-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 12px;
}

.album-card {
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  border: 2px solid transparent;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.album-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.25);
}

.album-cover {
  width: 100%;
  height: 100px;
  object-fit: cover;
  display: block;
}

.album-card-info {
  padding: 6px 8px;
  background: #f5f7fa;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.album-card-name {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 80px;
}

.album-card-count {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.album-back-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0 8px;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 6px;
}

.album-back-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

:deep(.el-tabs__header) {
  margin: 0;
  height: 40px;
  padding: 0;
}

:deep(.el-loading-spinner) {
  position: relative;
  top: 30px;
}

:deep(.el-loading-parent--relative) {
  height: 72px;
}

:deep(.el-loading-spinner) .circular {
  width: 30px;
  height: 30px;
}

:deep(.el-loading-spinner) .path {
  stroke-width: 3;
}

:deep(.el-loading-spinner) .el-loading-text {
  font-size: 14px;
  margin-top: 6px;
}
</style>

<style>
.select-picture-diag {
  top: auto !important;
  height: calc(100% - 160px) !important;
  max-height: calc(100vh - 160px) !important;
  margin: 0 auto !important;
  margin-top: calc(env(safe-area-inset-top, 0px) + 72px) !important;
  overflow: hidden;
}

.select-picture-diag .el-dialog__header {
  padding-right: 44px;
}

.select-picture-diag .el-dialog__headerbtn {
  top: 18px;
  right: 16px;
}

.select-picture-diag .el-dialog__body {
  height: calc(100% - 50px);
  width: 100%;
}

@media (max-width: 992px) {
  .select-picture-diag {
    height: calc(100% - 188px) !important;
    max-height: calc(100vh - 188px) !important;
    margin-top: calc(env(safe-area-inset-top, 0px) + 88px) !important;
  }
}

@media (max-width: 576px) {
  .select-picture-diag {
    width: calc(100% - 16px) !important;
    height: calc(100% - 172px) !important;
    max-height: calc(100vh - 172px) !important;
    margin-top: calc(env(safe-area-inset-top, 0px) + 84px) !important;
  }

  .select-picture-diag .el-dialog__body {
    height: calc(100% - 54px);
  }
}

.el-popper.is-customized {
  padding: 6px 12px;
  background: linear-gradient(90deg, rgb(159, 229, 151), rgb(204, 229, 129));
}

.el-popper.is-customized .el-popper__arrow::before {
  background: linear-gradient(45deg, #b2e68d, #bce689);
  right: 0;
}
</style>
