<template>
  <el-dialog
    v-model="dialogVisible"
    title="智能相册归类"
    :width="dialogWidth"
    :top="dialogTop"
    :append-to-body="true"
    destroy-on-close
    class="smart-album-dialog"
  >
    <div v-loading="loading" class="dialog-body">
      <div class="dialog-tip">
        请确认图片要归入的相册（可修改组名、添加新组、移动图片）
      </div>
      <div v-if="isMobile" class="dialog-mobile-tip">
        移动端暂不支持拖动移动图片，可直接点击每张图片右上角“移出”按钮调整分组。
      </div>

      <el-alert
        :title="`当前共 ${allFiles.length} 张图片，已归类 ${classifiedFileCount} 张，待分类 ${unclassifiedFileIds.length} 张`"
        type="info"
        show-icon
        :closable="false"
        class="dialog-summary"
      />

      <div class="group-list">
        <div
          v-for="group in groups"
          :key="group.localId"
          class="group-card"
          :class="{ 'is-dragover': dragOverGroupId === group.localId }"
          @dragover.prevent="handleGroupDragOver(group.localId)"
          @dragleave="handleGroupDragLeave(group.localId)"
          @drop.prevent="handleDropToGroup(group.localId)"
        >
          <div class="group-card-header">
            <div class="group-title-wrap">
              <el-checkbox :model-value="group.checked" @change="handleCheckedChange(group, !!$event)" />
              <template v-if="editingGroupId !== group.localId">
                <span class="group-title" @dblclick="startEditing(group)">
                  {{ group.albumName }}
                </span>
              </template>
              <el-input
                v-else
                :model-value="editingName"
                size="small"
                maxlength="100"
                class="group-title-input"
                @update:model-value="editingName = $event"
                @keyup.enter="confirmEditing(group)"
                @blur="cancelEditing"
                ref="renameInputRef"
              />
            </div>

            <div class="group-actions">
              <el-button text size="small" @click="startEditing(group)">编辑</el-button>
              <el-button
                v-if="group.isNew"
                text
                size="small"
                type="danger"
                @click="removeGroup(group.localId)"
              >
                删除组
              </el-button>
            </div>
          </div>

          <div class="group-meta">
            <span>{{ group.albumId ? '已存在相册' : '待创建相册' }}</span>
            <span>已有 {{ group.existingCount }} 张</span>
            <span>本次 {{ group.fileIds.length }} 张</span>
          </div>

          <div class="group-thumb-grid">
            <div
              v-for="file in getGroupFiles(group)"
              :key="`${group.localId}-${file.fileId}`"
              class="thumb-chip"
              :class="{ 'is-dragging': draggingFileId === file.fileId }"
              draggable="true"
              @dragstart="handleThumbDragStart(file.fileId, group.localId, $event)"
              @dragend="handleDragEnd"
            >
              <el-image :src="file.thumbnailUrl" fit="cover" class="thumb-image" />
              <div class="thumb-caption" :title="file.originFileName || file.fileId">
                {{ file.originFileName || file.fileId }}
              </div>
              <button class="thumb-remove" type="button" @click="removeFileFromGroup(group.localId, file.fileId)">
                移出
              </button>
            </div>
            <div v-if="group.fileIds.length === 0" class="group-empty">拖入图片到该组</div>
          </div>
        </div>
      </div>

      <div
        class="unclassified-panel"
        :class="{ 'is-dragover': dragOverZone === 'unclassified' }"
        @dragover.prevent="handleUnclassifiedDragOver"
        @dragleave="handleUnclassifiedDragLeave"
        @drop.prevent="handleDropToUnclassified"
      >
        <div class="unclassified-title">待分类图片（可拖动到上方任意组）</div>
        <div class="group-thumb-grid">
          <div
            v-for="file in unclassifiedFiles"
            :key="`unclassified-${file.fileId}`"
            class="thumb-chip"
            :class="{ 'is-dragging': draggingFileId === file.fileId }"
            draggable="true"
            @dragstart="handleThumbDragStart(file.fileId, null, $event)"
            @dragend="handleDragEnd"
          >
            <el-image :src="file.thumbnailUrl" fit="cover" class="thumb-image" />
            <div class="thumb-caption" :title="file.originFileName || file.fileId">
              {{ file.originFileName || file.fileId }}
            </div>
          </div>
          <el-empty v-if="unclassifiedFiles.length === 0" :image-size="56" description="当前没有待分类图片" />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button @click="addNewGroup">添加新组</el-button>
        <el-button :disabled="!canUndo" @click="undoLastAction">撤销上一步</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">确认保存</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { InputInstance } from 'element-plus'
import { getAlbumByTagName, saveClassification, selectAllAlbum } from '@/api/album/album'
import { selectMetaDataByFileId } from '@/api/file/file'

interface Props {
  show: boolean
  fileId?: string
  fileIds?: string[]
  tags?: string[]
  thumbnailUrl?: string
  fileItems?: Array<{
    fileId: string
    thumbnailUrl?: string
    originFileName?: string
  }>
  fileTagsMap?: Record<string, string[]>
}

interface FileCard {
  fileId: string
  thumbnailUrl: string
  originFileName?: string
}

interface GroupCard {
  localId: string
  albumId: number | null
  albumName: string
  checked: boolean
  isNew: boolean
  tagName: string | null
  existingCount: number
  fileIds: string[]
  stashFileIds: string[]
}

interface SnapshotState {
  groups: GroupCard[]
}

const AI_TAG_MAP: Record<string, string> = {
  人物: 'person',
  建筑: 'building',
  文档: 'document',
  电子设备: 'device',
  食物: 'food',
  其他: 'other',
  宠物: 'pet',
  植物: 'plant',
  旅行: 'travel',
  交通工具: 'transportation',
}

const props = withDefaults(defineProps<Props>(), {
  show: false,
  fileId: '',
  fileIds: () => [],
  tags: () => [],
  thumbnailUrl: '',
  fileItems: () => [],
  fileTagsMap: () => ({}),
})

const emit = defineEmits<{
  'update:show': [value: boolean]
  saved: [value: API.SaveClassificationResult]
}>()

const dialogVisible = computed({
  get: () => props.show,
  set: (value: boolean) => emit('update:show', value),
})

const loading = ref(false)
const saving = ref(false)
const allAlbums = ref<API.albumInfo[]>([])
const allFiles = ref<FileCard[]>([])
const groups = ref<GroupCard[]>([])
const editingGroupId = ref('')
const editingName = ref('')
const renameInputRef = ref<InputInstance | InputInstance[]>()
const dragOverGroupId = ref('')
const dragOverZone = ref<'unclassified' | ''>('')
const draggingFileId = ref('')
const dragSourceGroupId = ref<string | null>(null)
const lastSnapshot = ref<SnapshotState | null>(null)
const screenWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1280)

const fileMap = computed(() => new Map(allFiles.value.map((file) => [file.fileId, file])))
const classifiedFileIds = computed(() => {
  const ids = new Set<string>()
  groups.value.forEach((group) => {
    group.fileIds.forEach((fileId) => ids.add(fileId))
  })
  return ids
})
const unclassifiedFileIds = computed(() =>
  allFiles.value
    .map((file) => file.fileId)
    .filter((fileId) => !classifiedFileIds.value.has(fileId))
)
const unclassifiedFiles = computed(() =>
  unclassifiedFileIds.value
    .map((fileId) => fileMap.value.get(fileId))
    .filter(Boolean) as FileCard[]
)
const classifiedFileCount = computed(() => classifiedFileIds.value.size)
const canUndo = computed(() => !!lastSnapshot.value)
const isMobile = computed(() => screenWidth.value <= 768)
const dialogWidth = computed(() => (isMobile.value ? '94vw' : '80%'))
const dialogTop = computed(() => (isMobile.value ? '6vh' : '10vh'))

watch(
  () => [props.show, props.fileId, JSON.stringify(props.fileIds), JSON.stringify(props.tags), JSON.stringify(props.fileItems), JSON.stringify(props.fileTagsMap)],
  async ([show]) => {
    if (!show) {
      return
    }
    await initialize()
  },
  { immediate: true }
)

const updateScreenWidth = () => {
  screenWidth.value = window.innerWidth
}

onMounted(() => {
  window.addEventListener('resize', updateScreenWidth)
})

onUnmounted(() => {
  window.removeEventListener('resize', updateScreenWidth)
})

const initialize = async () => {
  loading.value = true
  editingGroupId.value = ''
  editingName.value = ''
  dragOverGroupId.value = ''
  dragOverZone.value = ''
  draggingFileId.value = ''
  dragSourceGroupId.value = null
  lastSnapshot.value = null

  try {
    allFiles.value = await resolveFiles()
    await loadAlbums()
    groups.value = await buildGroups()
  } finally {
    loading.value = false
  }
}

const resolveFiles = async () => {
  const fileMapById = new Map<string, FileCard>()

  props.fileItems.forEach((file) => {
    if (!file.fileId) {
      return
    }
    fileMapById.set(file.fileId, {
      fileId: file.fileId,
      thumbnailUrl: file.thumbnailUrl || '',
      originFileName: file.originFileName,
    })
  })

  if (!fileMapById.size && props.fileId) {
    fileMapById.set(props.fileId, {
      fileId: props.fileId,
      thumbnailUrl: props.thumbnailUrl || '',
    })
  }

  const files = Array.from(fileMapById.values())
  await Promise.all(
    files.map(async (file) => {
      if (file.thumbnailUrl) {
        return
      }
      const res = await selectMetaDataByFileId({ fileId: file.fileId })
      if (res.code === 200) {
        file.thumbnailUrl = res.data?.fileInfo?.thumbnailUrl || ''
        file.originFileName = file.originFileName || res.data?.fileInfo?.originFileName
      }
    })
  )

  return files
}

const loadAlbums = async () => {
  const res = await selectAllAlbum({
    current: 1,
    size: 500,
    orderKeyword: 'create_time',
    orderType: 'desc',
  })
  allAlbums.value = res.code === 200 ? (res.data?.records ?? []) : []
}

const buildGroups = async () => {
  const tagsByFileId = buildTagsByFileId()
  const orderedTags: string[] = []
  const tagToFileIds = new Map<string, string[]>()

  allFiles.value.forEach((file) => {
    const tags = tagsByFileId[file.fileId] || []
    tags.forEach((tag) => {
      if (!tagToFileIds.has(tag)) {
        orderedTags.push(tag)
        tagToFileIds.set(tag, [])
      }
      const fileIds = tagToFileIds.get(tag)!
      if (!fileIds.includes(file.fileId)) {
        fileIds.push(file.fileId)
      }
    })
  })

  const result = await Promise.all(
    orderedTags.map(async (tag) => {
      const tagName = AI_TAG_MAP[tag] || null
      let album: API.albumInfo | null = null
      if (tagName) {
        const res = await getAlbumByTagName({ tagName })
        album = res.code === 200 ? res.data || null : null
      } else {
        album = allAlbums.value.find((item) => item.albumName === tag) || null
      }
      return {
        localId: `${tag}-${tagName || 'custom'}-${album?.albumId || Date.now()}`,
        albumId: album?.albumId ?? null,
        albumName: album?.albumName ?? tag,
        checked: true,
        isNew: !album?.albumId,
        tagName,
        existingCount: album ? (album.imageCount || 0) + (album.videoCount || 0) : 0,
        fileIds: [...(tagToFileIds.get(tag) || [])],
        stashFileIds: [...(tagToFileIds.get(tag) || [])],
      } satisfies GroupCard
    })
  )

  return result
}

const buildTagsByFileId = () => {
  if (props.fileItems.length > 0 && Object.keys(props.fileTagsMap).length > 0) {
    return props.fileTagsMap
  }
  if (props.fileId) {
    return { [props.fileId]: props.tags }
  }
  return props.fileTagsMap
}

const getGroupFiles = (group: GroupCard) =>
  group.fileIds
    .map((fileId) => fileMap.value.get(fileId))
    .filter(Boolean) as FileCard[]

const snapshotGroups = () =>
  groups.value.map((group) => ({
    ...group,
    fileIds: [...group.fileIds],
    stashFileIds: [...group.stashFileIds],
  }))

const saveSnapshot = () => {
  lastSnapshot.value = {
    groups: snapshotGroups(),
  }
}

const undoLastAction = () => {
  if (!lastSnapshot.value) {
    return
  }
  groups.value = lastSnapshot.value.groups.map((group) => ({
    ...group,
    fileIds: [...group.fileIds],
    stashFileIds: [...group.stashFileIds],
  }))
  lastSnapshot.value = null
  ElMessage.success('已撤销上一步操作')
}

const startEditing = async (group: GroupCard) => {
  editingGroupId.value = group.localId
  editingName.value = group.albumName
  await nextTick()
  const inputRef = Array.isArray(renameInputRef.value) ? renameInputRef.value[0] : renameInputRef.value
  inputRef?.focus()
}

const confirmEditing = (group: GroupCard) => {
  const nextName = editingName.value.trim()
  if (!nextName) {
    ElMessage.warning('组名不能为空')
    return
  }
  if (hasNameConflict(nextName, group)) {
    ElMessage.warning('该相册名称已存在')
    return
  }
  saveSnapshot()
  group.albumName = nextName
  editingGroupId.value = ''
  editingName.value = ''
}

const cancelEditing = () => {
  editingGroupId.value = ''
  editingName.value = ''
}

const hasNameConflict = (name: string, currentGroup?: GroupCard) =>
  groups.value.some((group) => group.localId !== currentGroup?.localId && group.albumName === name) ||
  allAlbums.value.some((album) => album.albumName === name && album.albumId !== currentGroup?.albumId)

const addNewGroup = async () => {
  try {
    const { value } = await ElMessageBox.prompt('请输入新组名称', '添加新组', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: (inputValue) => {
        const normalized = inputValue.trim()
        if (!normalized) {
          return '组名不能为空'
        }
        if (hasNameConflict(normalized)) {
          return '该相册名称已存在'
        }
        return true
      },
    })

    saveSnapshot()
    groups.value.push({
      localId: `custom-${Date.now()}`,
      albumId: null,
      albumName: value.trim(),
      checked: true,
      isNew: true,
      tagName: null,
      existingCount: 0,
      fileIds: [...unclassifiedFileIds.value],
      stashFileIds: [...unclassifiedFileIds.value],
    })
  } catch {
    // 用户取消
  }
}

const removeGroup = (localId: string) => {
  saveSnapshot()
  groups.value = groups.value.filter((group) => group.localId !== localId)
}

const handleCheckedChange = (group: GroupCard, checked: boolean) => {
  saveSnapshot()
  if (!checked) {
    group.stashFileIds = [...group.fileIds]
    group.fileIds = []
    group.checked = false
    return
  }

  const sourceFileIds = group.stashFileIds.length > 0
    ? group.stashFileIds
    : (unclassifiedFileIds.value.length > 0 ? [...unclassifiedFileIds.value] : allFiles.value.map((file) => file.fileId))
  group.fileIds = Array.from(new Set(sourceFileIds))
  group.checked = group.fileIds.length > 0
}

const removeFileFromGroup = (groupId: string, fileId: string) => {
  const group = groups.value.find((item) => item.localId === groupId)
  if (!group) {
    return
  }
  saveSnapshot()
  group.fileIds = group.fileIds.filter((id) => id !== fileId)
  group.checked = group.fileIds.length > 0
}

const handleThumbDragStart = (fileId: string, sourceGroupId: string | null, event: DragEvent) => {
  draggingFileId.value = fileId
  dragSourceGroupId.value = sourceGroupId
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
  }
}

const handleDragEnd = () => {
  draggingFileId.value = ''
  dragSourceGroupId.value = null
  dragOverGroupId.value = ''
  dragOverZone.value = ''
}

const handleGroupDragOver = (groupId: string) => {
  dragOverGroupId.value = groupId
}

const handleGroupDragLeave = (groupId: string) => {
  if (dragOverGroupId.value === groupId) {
    dragOverGroupId.value = ''
  }
}

const handleDropToGroup = (groupId: string) => {
  const targetGroup = groups.value.find((group) => group.localId === groupId)
  if (!targetGroup || !draggingFileId.value) {
    return
  }
  saveSnapshot()
  if (!targetGroup.fileIds.includes(draggingFileId.value)) {
    targetGroup.fileIds.push(draggingFileId.value)
  }
  targetGroup.checked = true
  targetGroup.stashFileIds = [...targetGroup.fileIds]
  handleDragEnd()
}

const handleUnclassifiedDragOver = () => {
  dragOverZone.value = 'unclassified'
}

const handleUnclassifiedDragLeave = () => {
  dragOverZone.value = ''
}

const handleDropToUnclassified = () => {
  if (!draggingFileId.value || !dragSourceGroupId.value) {
    handleDragEnd()
    return
  }
  const sourceGroup = groups.value.find((group) => group.localId === dragSourceGroupId.value)
  if (!sourceGroup) {
    handleDragEnd()
    return
  }
  saveSnapshot()
  sourceGroup.fileIds = sourceGroup.fileIds.filter((fileId) => fileId !== draggingFileId.value)
  sourceGroup.checked = sourceGroup.fileIds.length > 0
  handleDragEnd()
}

const handleSave = async () => {
  if (allFiles.value.length === 0) {
    ElMessage.warning('缺少图片信息')
    return
  }

  const hasClassifiedFiles = groups.value.some((group) => group.fileIds.length > 0)
  if (!hasClassifiedFiles) {
    try {
      await ElMessageBox.confirm('图片将不会归入任何相册，确定继续？', '确认保存', {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }

  saving.value = true
  try {
    const allFileIds = allFiles.value.map((file) => file.fileId)
    const payload = {
      fileId: allFileIds.length === 1 ? allFileIds[0] : undefined,
      fileIds: allFileIds.length > 1 ? allFileIds : undefined,
      groups: groups.value.map((group) => ({
        albumId: group.albumId,
        albumName: group.albumName,
        isNew: group.isNew,
        tagName: group.tagName,
        checked: group.fileIds.length > 0,
        fileIds: [...group.fileIds],
      })),
      unclassified: allFileIds.length === 1 ? unclassifiedFileIds.value.length === 1 : undefined,
      unclassifiedFileIds: [...unclassifiedFileIds.value],
    }

    const res = await saveClassification(payload)
    if (res.code === 200) {
      ElMessage.success(res.message || '保存成功')
      emit('saved', res.data)
      dialogVisible.value = false
      return
    }
    ElMessage.error(res.message || '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.smart-album-dialog :deep(.el-dialog) {
  width: min(1120px, 80vw);
  max-height: 80vh;
  border-radius: 22px;
  overflow: hidden;
}

.smart-album-dialog :deep(.el-dialog__body) {
  padding-top: 12px;
}

.dialog-body {
  max-height: calc(80vh - 140px);
  overflow-y: auto;
}

.dialog-tip {
  margin-bottom: 16px;
  color: #4b5563;
  font-size: 14px;
}

.dialog-mobile-tip {
  margin: -4px 0 16px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #f8fbff;
  border: 1px solid #dbe8ff;
  color: #4f6b95;
  font-size: 13px;
  line-height: 1.5;
}

.dialog-summary {
  margin-bottom: 16px;
}

.group-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
}

.group-card {
  border: 1px solid #dbe3f0;
  border-radius: 16px;
  background: linear-gradient(180deg, #ffffff 0%, #f7faff 100%);
  padding: 14px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.group-card:hover {
  border-color: #7aa7ff;
  box-shadow: 0 10px 24px rgba(49, 116, 255, 0.12);
  transform: translateY(-1px);
}

.group-card.is-dragover,
.unclassified-panel.is-dragover {
  border-color: #3174ff;
  box-shadow: 0 0 0 3px rgba(49, 116, 255, 0.14);
}

.group-card-header {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
}

.group-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.group-title {
  font-weight: 600;
  color: #111827;
  cursor: text;
}

.group-title-input {
  width: 160px;
}

.group-actions {
  opacity: 0;
  transition: opacity 0.2s ease;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 4px;
}

.group-card:hover .group-actions {
  opacity: 1;
}

.group-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin: 10px 0 12px;
  color: #6b7280;
  font-size: 12px;
}

.group-thumb-grid {
  min-height: 110px;
  border: 1px dashed #c8d4e8;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.8);
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 10px;
  padding: 12px;
}

.group-empty {
  color: #94a3b8;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 86px;
}

.thumb-chip {
  position: relative;
  cursor: grab;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 8px 16px rgba(17, 24, 39, 0.12);
  background: #fff;
}

.thumb-chip.is-dragging {
  opacity: 0.55;
}

.thumb-chip:active {
  cursor: grabbing;
}

.thumb-image {
  width: 100%;
  aspect-ratio: 1;
  display: block;
}

.thumb-caption {
  font-size: 12px;
  color: #1f2937;
  padding: 6px 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.thumb-remove {
  position: absolute;
  right: 6px;
  top: 6px;
  border: none;
  border-radius: 999px;
  background: rgba(17, 24, 39, 0.72);
  color: #fff;
  padding: 4px 8px;
  font-size: 12px;
  cursor: pointer;
  min-height: 28px;
}

.unclassified-panel {
  margin-top: 20px;
  border: 1px dashed #cbd5e1;
  border-radius: 18px;
  background: linear-gradient(180deg, #f8fafc 0%, #eef5ff 100%);
  padding: 16px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.unclassified-title {
  margin-bottom: 12px;
  color: #1f2937;
  font-weight: 600;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
}

@media (max-width: 768px) {
  .smart-album-dialog :deep(.el-dialog) {
    width: calc(100vw - 16px) !important;
    max-height: 88vh;
    margin: 0 auto;
  }

  .smart-album-dialog :deep(.el-dialog__header) {
    padding-right: 40px;
  }

  .smart-album-dialog :deep(.el-dialog__body) {
    padding: 12px;
  }

  .smart-album-dialog :deep(.el-dialog__footer) {
    padding: 12px;
    border-top: 1px solid #edf2f7;
    background: #fff;
  }

  .dialog-body {
    max-height: calc(88vh - 160px);
    padding-right: 2px;
  }

  .group-list {
    grid-template-columns: 1fr;
  }

  .group-card {
    padding: 12px;
    border-radius: 14px;
  }

  .group-card:hover {
    transform: none;
  }

  .group-card-header {
    flex-direction: column;
  }

  .group-title-wrap,
  .group-actions {
    width: 100%;
  }

  .group-actions {
    opacity: 1;
    justify-content: flex-end;
  }

  .group-title-input {
    width: 100%;
  }

  .group-thumb-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    padding: 10px;
  }

  .thumb-caption {
    padding: 6px;
    font-size: 11px;
  }

  .thumb-remove {
    right: 4px;
    top: 4px;
    padding: 4px 7px;
    font-size: 11px;
  }

  .unclassified-panel {
    margin-top: 16px;
    padding: 12px;
    border-radius: 14px;
  }

  .dialog-footer {
    justify-content: stretch;
  }

  .dialog-footer :deep(.el-button) {
    flex: 1 1 calc(50% - 6px);
    min-width: 132px;
    margin-left: 0;
  }
}

@media (max-width: 480px) {
  .smart-album-dialog :deep(.el-dialog) {
    width: calc(100vw - 12px) !important;
    max-height: 90vh;
  }

  .dialog-body {
    max-height: calc(90vh - 156px);
  }

  .dialog-tip,
  .dialog-mobile-tip {
    font-size: 12px;
  }

  .group-meta {
    gap: 6px 10px;
  }

  .group-thumb-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dialog-footer :deep(.el-button) {
    flex-basis: 100%;
    width: 100%;
  }
}
</style>
