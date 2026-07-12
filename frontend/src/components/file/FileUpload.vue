<template>
  <input
    id="uploadImage"
    ref="fileInput"
    type="file"
    multiple
    :accept="acceptType.join(',')"
    class="hidden-input"
    @change="handleFileSelection"
  />
  <input
    id="uploadFolder"
    ref="folderInput"
    type="file"
    multiple
    webkitdirectory=""
    :accept="acceptType.join(',')"
    class="hidden-input"
    @change="handleFileSelection"
  />

  <section v-if="showPanel" class="upload-panel">
    <header class="upload-header">
      <strong>{{ uploadStatus }}</strong>
      <div class="header-actions">
        <el-button link @click="showDetails = !showDetails">
          {{ showDetails ? '收起' : '展开' }}
        </el-button>
        <el-button link @click="closePanel">关闭</el-button>
      </div>
    </header>

    <div v-show="showDetails" class="upload-list">
      <article v-for="task in tasks" :key="task.id" class="upload-item">
        <div class="progress-bg" :style="{ width: `${task.progress}%` }"></div>
        <div class="file-icon">{{ isImage(task.file.name) ? 'IMG' : 'VID' }}</div>
        <div class="file-info">
          <div class="file-name" :title="task.file.name">{{ task.file.name }}</div>
          <div class="file-size">{{ formatSize(task.file.size) }}</div>
        </div>
        <div class="file-state">
          <span :class="`state-${task.status}`">{{ statusText(task) }}</span>
          <small v-if="task.error" :title="task.error">{{ task.error }}</small>
        </div>
        <div class="file-actions">
          <el-button v-if="task.status === 'error'" link @click="retryTask(task)">重试</el-button>
          <el-button
            v-if="!['success', 'duplicate', 'cancelled'].includes(task.status)"
            link
            @click="cancelTask(task)"
          >
            取消
          </el-button>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, markRaw, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessageBox, ElNotification } from 'element-plus'
import SparkMD5 from 'spark-md5'
import $bus from '@/utils/bus'
import {
  abortMultipartUpload,
  completeMultipartUpload,
  getMultipartUploadStatus,
  initializeMultipartUpload,
  refreshMultipartUploadUrls,
  type MultipartUploadInitRequest,
  type MultipartUploadPart,
  type MultipartUploadSession,
} from '@/api/file/file'
import { Sha256 } from '@/utils/sha256'

type UploadStatus =
  | 'queued'
  | 'hashing'
  | 'initializing'
  | 'uploading'
  | 'completing'
  | 'success'
  | 'duplicate'
  | 'error'
  | 'cancelled'

interface UploadTask {
  id: string
  file: File
  albumId: number
  progress: number
  status: UploadStatus
  error: string
  sessionId?: string
  md5?: string
  sha256?: string
  resumeKey?: string
  cancelled: boolean
  xhrs: Set<XMLHttpRequest>
  partLoaded: Map<number, number>
  hashWorker?: Worker
  rejectHash?: (reason: Error) => void
  rejectWait?: (reason: Error) => void
}

interface FileFingerprint {
  md5: string
  sha256: string
}

interface ResumeRecord {
  key: string
  sessionId: string
  fileName: string
  fileSize: number
  contentType: string
  lastModified: number
  albumId: number
  md5: string
  sha256: string
  partSize: number
  partCount: number
  updatedAt: number
}

const acceptImageType = ['.png', '.jpg', '.jpeg', '.gif', '.bmp']
const acceptVideoType = ['.mp4', '.rmvb', '.mkv', '.wmv', '.flv']
const acceptType = [...acceptImageType, ...acceptVideoType]
const imageMaxSize = 50 * 1024 * 1024
const videoMaxSize = 2 * 1024 * 1024 * 1024
const fileConcurrency = 3
const partConcurrency = 3
const hashChunkSize = 16 * 1024 * 1024
const hashWorkerStallTimeoutMs = 5000
const uploadInitIntervalMs = 250
const resumeDbName = 'memory-upload-resume'
const resumeStoreName = 'sessions'
const resumeDbVersion = 1

const fileInput = ref<HTMLInputElement>()
const folderInput = ref<HTMLInputElement>()
const tasks = ref<UploadTask[]>([])
const showPanel = ref(false)
const showDetails = ref(true)
const albumId = ref(-1)
let activeFiles = 0
let nextUploadInitAt = 0
let uploadInitChain: Promise<unknown> = Promise.resolve()
const queue: UploadTask[] = []
const inFlightHashes = new Map<string, Promise<boolean>>()

const uploadStatus = computed(() => {
  const total = tasks.value.length
  const success = tasks.value.filter((task) => task.status === 'success').length
  const duplicate = tasks.value.filter((task) => task.status === 'duplicate').length
  const failed = tasks.value.filter((task) => task.status === 'error').length
  if (!total) return '上传列表'
  if (success + duplicate === total) {
    return `处理完成 (${success} 个上传，${duplicate} 个已存在)`
  }
  return `正在上传 (${success + duplicate}/${total}${failed ? `，失败 ${failed}` : ''})`
})

onMounted(() => {
  $bus.off('showUploadList')
  $bus.on('showUploadList', openFilePicker)
})

onBeforeUnmount(() => {
  $bus.off('showUploadList')
  tasks.value.forEach(abortLocalRequests)
})

function openFilePicker(params: { albumId?: number; uploadType?: string }) {
  albumId.value = Number(params?.albumId ?? -1)
  const input = params?.uploadType === 'uploadFolder' ? folderInput.value : fileInput.value
  input?.click()
}

function handleFileSelection(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files ?? [])
  input.value = ''
  if (!selected.length) return

  showPanel.value = true
  for (const file of selected) {
    const validationError = validateFile(file)
    const task = reactive<UploadTask>({
      id: `${Date.now()}-${crypto.randomUUID()}`,
      file,
      albumId: albumId.value,
      progress: 0,
      status: validationError ? 'error' : 'queued',
      error: validationError,
      cancelled: false,
      xhrs: markRaw(new Set<XMLHttpRequest>()),
      partLoaded: markRaw(new Map<number, number>()),
    }) as UploadTask
    tasks.value.push(task)
    if (!validationError) queue.push(task)
  }
  pumpQueue()
}

function validateFile(file: File) {
  const extension = getExtension(file.name)
  if (!acceptType.includes(extension)) return '不支持的文件格式'
  const maxSize = acceptImageType.includes(extension) ? imageMaxSize : videoMaxSize
  if (file.size <= 0) return '文件为空'
  if (file.size > maxSize) {
    return acceptImageType.includes(extension) ? '图片不能超过 50MB' : '视频不能超过 2GB'
  }
  return ''
}

function pumpQueue() {
  while (activeFiles < fileConcurrency && queue.length) {
    const task = queue.shift()!
    if (task.cancelled) continue
    activeFiles++
    processTask(task)
      .catch(() => undefined)
      .finally(() => {
        activeFiles--
        pumpQueue()
        notifyWhenFinished()
      })
  }
}

async function processTask(task: UploadTask) {
  let trackedFingerprint: string | undefined
  let trackedUpload: Promise<boolean> | undefined
  let resolveTrackedUpload: ((success: boolean) => void) | undefined
  task.error = ''
  task.cancelled = false
  task.progress = 0
  task.partLoaded.clear()
  task.md5 = undefined
  task.sha256 = undefined
  task.resumeKey = undefined
  try {
    task.status = 'hashing'
    const fingerprint = await calculateFileFingerprint(task)
    task.md5 = fingerprint.md5
    task.sha256 = fingerprint.sha256
    task.resumeKey = buildResumeKey(task, fingerprint)
    task.progress = 0
    ensureActive(task)
    task.status = 'initializing'

    const pendingUpload = inFlightHashes.get(fingerprint.sha256)
    if (pendingUpload) {
      await waitForPendingUpload(task, pendingUpload)
      ensureActive(task)
    }

    trackedFingerprint = fingerprint.sha256
    trackedUpload = new Promise<boolean>((resolve) => {
      resolveTrackedUpload = resolve
    })
    inFlightHashes.set(fingerprint.sha256, trackedUpload)

    let session = await resumeUploadSession(task, fingerprint)
    if (!session) {
      const response = await initializeUploadSession(task, {
      fileName: task.file.name,
      fileSize: task.file.size,
      contentType: normalizedContentType(task.file),
      lastModified: task.file.lastModified,
      albumId: task.albumId,
      md5: fingerprint.md5,
      sha256: fingerprint.sha256,
    })
    ensureActive(task)
    if (response.code !== 200 || !response.data) {
      throw new Error(response.message || 'Initialize upload failed')
      /*
      throw new Error(response.message || '初始化上传失败')
    }
      session = response.data as MultipartUploadSession
      */
    }
      session = response.data as MultipartUploadSession
    }
    if (session.instantUpload || session.completed) {
      task.progress = 100
      task.status = 'duplicate'
      await deleteResumeRecord(task.resumeKey)
      return
    }
    if (!session.sessionId || !session.parts?.length) {
      const refreshed = await refreshMissingPartUrls(session)
      session = refreshed ?? session
    }
    if (!session.sessionId || (!session.parts?.length && collectMissingPartNumbers(session).length > 0)) {
      throw new Error('Upload session data is incomplete')
      /*
      throw new Error('上传会话数据不完整')
      */
    }
    task.sessionId = session.sessionId
    await saveResumeRecord(task, fingerprint, session)
    applyUploadedParts(task, session)
    task.status = 'uploading'
    await uploadAllParts(task, session)
    ensureActive(task)

    task.status = 'completing'
    const completed = await completeMultipartUpload(session.sessionId)
    if (completed.code !== 200) {
      throw new Error(completed.message || 'Complete multipart upload failed')
      /*
      throw new Error(completed.message || '合并分片失败')
      */
    }
    task.progress = 100
    task.status = 'success'
    await deleteResumeRecord(task.resumeKey)
  } catch (error) {
    if (task.cancelled) {
      if (task.sessionId) {
        try {
          await abortMultipartUpload(task.sessionId)
        } catch {
          // Scheduled cleanup remains the fallback if the abort request fails.
        }
      }
      await deleteResumeRecord(task.resumeKey)
      task.status = 'cancelled'
      return
    }
    abortLocalRequests(task)
    task.status = 'error'
    task.error = error instanceof Error ? error.message : '上传失败'
    throw error
  } finally {
    if (trackedFingerprint && trackedUpload) {
      resolveTrackedUpload?.(['success', 'duplicate'].includes(task.status))
      if (inFlightHashes.get(trackedFingerprint) === trackedUpload) {
        inFlightHashes.delete(trackedFingerprint)
      }
    }
  }
}

function initializeUploadSession(task: UploadTask, data: MultipartUploadInitRequest) {
  const run = uploadInitChain.then(async () => {
    ensureActive(task)
    const delay = Math.max(0, nextUploadInitAt - Date.now())
    if (delay > 0) {
      await waitForBrowser(delay)
    }
    ensureActive(task)
    nextUploadInitAt = Date.now() + uploadInitIntervalMs
    return initializeMultipartUpload(data)
  })
  uploadInitChain = run.catch(() => undefined)
  return run
}

async function resumeUploadSession(
  task: UploadTask,
  fingerprint: FileFingerprint,
): Promise<MultipartUploadSession | undefined> {
  const key = buildResumeKey(task, fingerprint)
  const record = await loadResumeRecord(key)
  if (!record || !isResumeRecordForTask(task, fingerprint, record)) {
    if (record) await deleteResumeRecord(key)
    return undefined
  }

  try {
    const statusResponse = await getMultipartUploadStatus(record.sessionId)
    if (statusResponse.code !== 200 || !statusResponse.data) {
      await deleteResumeRecord(key)
      return undefined
    }
    let session = statusResponse.data as MultipartUploadSession
    if (session.completed || session.instantUpload) {
      await deleteResumeRecord(key)
      return session
    }
    if (!session.sessionId || session.partSize !== record.partSize || session.partCount !== record.partCount) {
      await deleteResumeRecord(key)
      return undefined
    }
    session = (await refreshMissingPartUrls(session)) ?? session
    await saveResumeRecord(task, fingerprint, session)
    return session
  } catch {
    return undefined
  }
}

async function refreshMissingPartUrls(session: MultipartUploadSession) {
  if (!session.sessionId) return undefined
  const missing = collectMissingPartNumbers(session)
  if (!missing.length) {
    return session
  }
  const response = await refreshMultipartUploadUrls(session.sessionId, missing)
  if (response.code !== 200 || !response.data) {
    return undefined
  }
  return mergeSessionParts(session, response.data as MultipartUploadSession)
}

function mergeSessionParts(
  session: MultipartUploadSession,
  refreshed: MultipartUploadSession,
): MultipartUploadSession {
  const parts = new Map<number, MultipartUploadPart>()
  for (const part of session.parts ?? []) {
    parts.set(part.partNumber, part)
  }
  for (const part of refreshed.parts ?? []) {
    parts.set(part.partNumber, part)
  }
  return {
    ...session,
    parts: Array.from(parts.values()).sort((left, right) => left.partNumber - right.partNumber),
    uploadedParts: refreshed.uploadedParts ?? session.uploadedParts,
    urlsExpireAt: refreshed.urlsExpireAt ?? session.urlsExpireAt,
  }
}

function collectMissingPartNumbers(session: MultipartUploadSession) {
  const uploaded = new Set(session.uploadedParts ?? [])
  const withUrl = new Set((session.parts ?? []).map((part) => part.partNumber))
  const missing: number[] = []
  for (let partNumber = 1; partNumber <= session.partCount; partNumber++) {
    if (!uploaded.has(partNumber) && !withUrl.has(partNumber)) {
      missing.push(partNumber)
    }
  }
  return missing
}

function applyUploadedParts(task: UploadTask, session: MultipartUploadSession) {
  for (const partNumber of session.uploadedParts ?? []) {
    task.partLoaded.set(partNumber, partSizeFor(task, session, partNumber))
  }
  updateProgress(task)
}

function partSizeFor(task: UploadTask, session: MultipartUploadSession, partNumber: number) {
  const start = (partNumber - 1) * session.partSize
  return Math.max(0, Math.min(session.partSize, task.file.size - start))
}

function buildResumeKey(task: UploadTask, fingerprint: FileFingerprint) {
  return [
    fingerprint.sha256,
    task.albumId,
    task.file.name,
    task.file.size,
    task.file.lastModified,
  ].join(':')
}

function isResumeRecordForTask(
  task: UploadTask,
  fingerprint: FileFingerprint,
  record: ResumeRecord,
) {
  return (
    record.fileName === task.file.name &&
    record.fileSize === task.file.size &&
    record.lastModified === task.file.lastModified &&
    record.albumId === task.albumId &&
    record.md5 === fingerprint.md5 &&
    record.sha256 === fingerprint.sha256
  )
}

async function saveResumeRecord(
  task: UploadTask,
  fingerprint: FileFingerprint,
  session: MultipartUploadSession,
) {
  const resumeKey = task.resumeKey
  const sessionId = session.sessionId
  if (!resumeKey || !sessionId || session.instantUpload || session.completed) return
  await withResumeStore('readwrite', (store) => {
    const record: ResumeRecord = {
      key: resumeKey,
      sessionId,
      fileName: task.file.name,
      fileSize: task.file.size,
      contentType: normalizedContentType(task.file),
      lastModified: task.file.lastModified,
      albumId: task.albumId,
      md5: fingerprint.md5,
      sha256: fingerprint.sha256,
      partSize: session.partSize,
      partCount: session.partCount,
      updatedAt: Date.now(),
    }
    store.put(record)
  })
}

async function loadResumeRecord(key: string) {
  return withResumeStore<ResumeRecord | undefined>('readonly', (store, resolve, reject) => {
    const request = store.get(key)
    request.onsuccess = () => resolve(request.result as ResumeRecord | undefined)
    request.onerror = () => reject(request.error)
  })
}

async function deleteResumeRecord(key?: string) {
  if (!key) return
  await withResumeStore('readwrite', (store) => {
    store.delete(key)
  })
}

function withResumeStore<T = void>(
  mode: IDBTransactionMode,
  action: (
    store: IDBObjectStore,
    resolve: (value: T | PromiseLike<T>) => void,
    reject: (reason?: unknown) => void,
  ) => void,
) {
  return new Promise<T>((resolve, reject) => {
    if (!window.indexedDB) {
      resolve(undefined as T)
      return
    }
    const request = window.indexedDB.open(resumeDbName, resumeDbVersion)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(resumeStoreName)) {
        db.createObjectStore(resumeStoreName, { keyPath: 'key' })
      }
    }
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const db = request.result
      const transaction = db.transaction(resumeStoreName, mode)
      const store = transaction.objectStore(resumeStoreName)
      let manuallyResolved = false
      const finish = (value: T | PromiseLike<T>) => {
        manuallyResolved = true
        resolve(value)
      }
      action(store, finish, reject)
      transaction.oncomplete = () => {
        db.close()
        if (!manuallyResolved) resolve(undefined as T)
      }
      transaction.onerror = () => {
        db.close()
        reject(transaction.error)
      }
      transaction.onabort = () => {
        db.close()
        reject(transaction.error)
      }
    }
  })
}

function waitForPendingUpload(task: UploadTask, pendingUpload: Promise<boolean>) {
  return new Promise<boolean>((resolve, reject) => {
    let settled = false
    const finish = () => {
      settled = true
      task.rejectWait = undefined
    }
    task.rejectWait = (reason) => {
      if (settled) return
      finish()
      reject(reason)
    }
    pendingUpload.then(
      (uploaded) => {
        if (settled) return
        finish()
        resolve(uploaded)
      },
      (error) => {
        if (settled) return
        finish()
        reject(error)
      },
    )
  })
}

function calculateFileFingerprint(task: UploadTask) {
  let worker: Worker
  try {
    worker = markRaw(
      new Worker(new URL('../../workers/md5.worker.ts', import.meta.url), { type: 'module' }),
    )
  } catch {
    return calculateFileFingerprintInMainThread(task)
  }
  task.hashWorker = worker

  return new Promise<FileFingerprint>((resolve, reject) => {
    let settled = false
    let stallTimer: number | undefined

    const cleanupWorker = () => {
      if (stallTimer !== undefined) {
        window.clearTimeout(stallTimer)
        stallTimer = undefined
      }
      worker.terminate()
      if (task.hashWorker === worker) {
        task.hashWorker = undefined
      }
    }
    const settle = (callback: () => void) => {
      if (settled) return
      settled = true
      cleanupWorker()
      task.rejectHash = undefined
      callback()
    }
    const fallbackToMainThread = async () => {
      if (settled) return
      cleanupWorker()
      try {
        const fingerprint = await calculateFileFingerprintInMainThread(task)
        settle(() => resolve(fingerprint))
      } catch (error) {
        const reason = error instanceof Error ? error : new Error('文件校验失败')
        settle(() => reject(reason))
      }
    }
    const resetStallTimer = () => {
      if (stallTimer !== undefined) {
        window.clearTimeout(stallTimer)
      }
      stallTimer = window.setTimeout(() => {
        void fallbackToMainThread()
      }, hashWorkerStallTimeoutMs)
    }
    const finish = () => {
      settled = true
      cleanupWorker()
      task.rejectHash = undefined
    }
    task.rejectHash = (reason) => {
      settle(() => reject(reason))
    }
    worker.onmessage = (
      event: MessageEvent<{ md5?: string; sha256?: string; error?: string; progress?: number }>,
    ) => {
      if (typeof event.data.progress === 'number') {
        task.progress = Math.min(5, Number((event.data.progress / 20).toFixed(2)))
        resetStallTimer()
        return
      }
      finish()
      if (event.data.md5 && event.data.sha256) {
        resolve({ md5: event.data.md5, sha256: event.data.sha256 })
      } else {
        reject(new Error(event.data.error || '文件校验失败'))
      }
    }
    worker.onerror = (event) => {
      event.preventDefault()
      void fallbackToMainThread()
    }
    resetStallTimer()
    worker.postMessage(task.file)
  })
}

async function calculateFileFingerprintInMainThread(task: UploadTask): Promise<FileFingerprint> {
  const spark = new SparkMD5.ArrayBuffer()
  const sha256 = new Sha256()
  let processed = 0
  for (let offset = 0; offset < task.file.size; offset += hashChunkSize) {
    ensureActive(task)
    const buffer = await task.file.slice(offset, offset + hashChunkSize).arrayBuffer()
    spark.append(buffer)
    sha256.update(buffer)
    processed += buffer.byteLength
    task.progress = Math.min(5, Number(((processed / task.file.size) * 5).toFixed(2)))
    await waitForBrowser()
  }
  return {
    md5: spark.end(),
    sha256: sha256.digest(),
  }
}

function waitForBrowser(delayMs = 0) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, delayMs))
}

async function uploadAllParts(task: UploadTask, session: MultipartUploadSession) {
  const uploaded = new Set(session.uploadedParts ?? [])
  const uploadParts = (session.parts ?? []).filter((part) => !uploaded.has(part.partNumber))
  if (!uploadParts.length) {
    return
  }
  let cursor = 0
  const workers = Array.from(
    { length: Math.min(partConcurrency, uploadParts.length) },
    async () => {
      while (cursor < uploadParts.length) {
        const current = uploadParts[cursor++]
        await uploadPartWithRetry(task, session, current.partNumber, current.url)
      }
    },
  )
  await Promise.all(workers)
}

async function uploadPartWithRetry(
  task: UploadTask,
  session: MultipartUploadSession,
  partNumber: number,
  url: string,
) {
  let lastError: unknown
  let currentUrl = url
  for (let attempt = 0; attempt < 3; attempt++) {
    ensureActive(task)
    try {
      await uploadPart(task, session, partNumber, currentUrl)
      return
    } catch (error) {
      lastError = error
      task.partLoaded.set(partNumber, 0)
      updateProgress(task)
      if (task.cancelled) throw error
      const refreshedUrl = await refreshPartUrl(session, partNumber)
      if (refreshedUrl) {
        currentUrl = refreshedUrl
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error(`分片 ${partNumber} 上传失败`)
}

async function refreshPartUrl(session: MultipartUploadSession, partNumber: number) {
  if (!session.sessionId) return undefined
  try {
    const response = await refreshMultipartUploadUrls(session.sessionId, [partNumber])
    if (response.code !== 200 || !response.data) return undefined
    const refreshed = response.data as MultipartUploadSession
    const merged = mergeSessionParts(session, refreshed)
    session.parts = merged.parts
    session.uploadedParts = merged.uploadedParts
    session.urlsExpireAt = merged.urlsExpireAt
    return refreshed.parts?.find((part) => part.partNumber === partNumber)?.url
  } catch {
    return undefined
  }
}

function uploadPart(
  task: UploadTask,
  session: MultipartUploadSession,
  partNumber: number,
  url: string,
) {
  const start = (partNumber - 1) * session.partSize
  const body = task.file.slice(start, Math.min(start + session.partSize, task.file.size))
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    task.xhrs.add(xhr)
    xhr.open('PUT', url)
    xhr.timeout = 10 * 60 * 1000
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        task.partLoaded.set(partNumber, event.loaded)
        updateProgress(task)
      }
    }
    xhr.onload = () => {
      task.xhrs.delete(xhr)
      if (xhr.status >= 200 && xhr.status < 300) {
        task.partLoaded.set(partNumber, body.size)
        updateProgress(task)
        resolve()
      } else {
        reject(new Error(`分片 ${partNumber} 上传失败 (${xhr.status})`))
      }
    }
    xhr.onerror = () => {
      task.xhrs.delete(xhr)
      reject(new Error(`分片 ${partNumber} 网络错误`))
    }
    xhr.ontimeout = () => {
      task.xhrs.delete(xhr)
      reject(new Error(`分片 ${partNumber} 上传超时`))
    }
    xhr.onabort = () => {
      task.xhrs.delete(xhr)
      reject(new Error('上传已取消'))
    }
    xhr.send(body)
  })
}

function updateProgress(task: UploadTask) {
  const loaded = Array.from(task.partLoaded.values()).reduce((sum, value) => sum + value, 0)
  task.progress = Math.min(99, Number(((loaded / task.file.size) * 100).toFixed(2)))
}

async function cancelTask(task: UploadTask) {
  task.cancelled = true
  abortLocalRequests(task)
  const index = queue.indexOf(task)
  if (index >= 0) queue.splice(index, 1)
  if (task.sessionId) {
    try {
      await abortMultipartUpload(task.sessionId)
    } catch {
      // The backend session may already have expired or completed.
    }
  }
  await deleteResumeRecord(task.resumeKey)
  task.status = 'cancelled'
}

function retryTask(task: UploadTask) {
  if (activeFiles >= fileConcurrency || queue.includes(task)) {
    return
  }
  task.sessionId = undefined
  task.resumeKey = undefined
  task.cancelled = false
  task.status = 'queued'
  task.error = ''
  queue.push(task)
  pumpQueue()
}

async function closePanel() {
  const unfinished = tasks.value.filter(
    (task) => !['success', 'duplicate', 'cancelled', 'error'].includes(task.status),
  )
  if (unfinished.length) {
    try {
      await ElMessageBox.confirm(
        '仍有文件正在上传，关闭将取消这些上传。',
        '取消上传',
        {
          confirmButtonText: '取消上传',
          cancelButtonText: '继续上传',
          type: 'warning',
        },
      )
    } catch {
      return
    }
    await Promise.all(unfinished.map(cancelTask))
  }
  showPanel.value = false
}

function notifyWhenFinished() {
  if (activeFiles || queue.length) return
  const successCount = tasks.value.filter((task) => task.status === 'success').length
  const duplicateCount = tasks.value.filter((task) => task.status === 'duplicate').length
  if (successCount + duplicateCount) {
    const duplicateMessage = duplicateCount ? `，${duplicateCount} 个文件已存在` : ''
    ElNotification({
      title: '上传完成',
      message: `${successCount} 个文件上传成功${duplicateMessage}`,
      type: 'success',
      position: 'bottom-right',
      offset: 100,
    })
    $bus.emit('uploadSucess', albumId.value)
  }
}

function abortLocalRequests(task: UploadTask) {
  task.rejectHash?.(new Error('上传已取消'))
  task.rejectWait?.(new Error('上传已取消'))
  task.hashWorker?.terminate()
  task.hashWorker = undefined
  task.rejectHash = undefined
  task.rejectWait = undefined
  task.xhrs.forEach((xhr) => xhr.abort())
  task.xhrs.clear()
}

function ensureActive(task: UploadTask) {
  if (task.cancelled) throw new Error('上传已取消')
}

function normalizedContentType(file: File) {
  const types: Record<string, string> = {
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.png': 'image/png',
    '.gif': 'image/gif',
    '.bmp': 'image/bmp',
    '.mp4': 'video/mp4',
    '.mkv': 'application/octet-stream',
    '.wmv': 'application/octet-stream',
    '.flv': 'video/x-flv',
    '.rmvb': 'application/octet-stream',
  }
  if (file.type && file.type !== 'application/octet-stream') return file.type
  return types[getExtension(file.name)] || 'application/octet-stream'
}

function getExtension(name: string) {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot).toLowerCase()
}

function isImage(name: string) {
  return acceptImageType.includes(getExtension(name))
}

function formatSize(size: number) {
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function statusText(task: UploadTask) {
  const labels: Record<UploadStatus, string> = {
    queued: '等待上传',
    hashing: task.progress > 0 ? `正在校验文件 ${Math.round(task.progress * 20)}%` : '正在校验文件',
    initializing: '正在准备上传',
    uploading: `${task.progress.toFixed(2)}%`,
    completing: '正在登记文件',
    success: '上传完成',
    duplicate: '文件已存在',
    error: '上传失败',
    cancelled: '已取消',
  }
  return labels[task.status]
}
</script>

<style scoped>
.hidden-input {
  display: none;
}

.upload-panel {
  z-index: 1000;
  width: min(520px, calc(100vw - 32px));
  max-height: 430px;
  overflow: hidden;
  border: 1px solid rgba(122, 132, 177, 0.18);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 12px 36px rgba(36, 43, 75, 0.18);
}

.upload-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 46px;
  padding: 0 14px;
  border-bottom: 1px solid #eef0f5;
}

.header-actions {
  display: flex;
}

.upload-list {
  max-height: 360px;
  overflow-y: auto;
  padding: 8px;
}

.upload-item {
  position: relative;
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr) 130px 80px;
  gap: 10px;
  align-items: center;
  min-height: 62px;
  overflow: hidden;
  border-bottom: 1px solid #f1f2f6;
  padding: 4px 8px;
}

.progress-bg {
  position: absolute;
  inset: 4px auto 4px 0;
  z-index: 0;
  border-radius: 8px;
  background: rgba(59, 117, 255, 0.08);
  transition: width 0.2s ease;
}

.file-icon,
.file-info,
.file-state,
.file-actions {
  position: relative;
  z-index: 1;
}

.file-icon {
  color: #3b75ff;
  font-size: 12px;
  font-weight: 700;
}

.file-name,
.file-state small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name {
  color: #303545;
  font-size: 13px;
}

.file-size,
.file-state small {
  color: #9298a8;
  font-size: 11px;
}

.file-state {
  display: flex;
  min-width: 0;
  flex-direction: column;
  font-size: 12px;
}

.state-success,
.state-duplicate {
  color: #2f9e62;
}

.state-error {
  color: #dc4c4c;
}

.file-actions {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 600px) {
  .upload-item {
    grid-template-columns: 36px minmax(0, 1fr) 100px;
  }

  .file-actions {
    grid-column: 2 / 4;
  }
}
</style>
