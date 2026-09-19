<template>
  <div class="activity-panel">
    <div class="activity-controls">
      <button type="button" :disabled="busy" @click="startDiscovery">发现相似照片</button>
      <button type="button" :disabled="loading" @click="refresh">刷新</button>
    </div>
    <p class="help">相似照片是整理候选，删除前请逐张核对。确认或取消整理操作，请回到原对话。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <article v-for="job in jobs" :key="job.jobId" class="activity-card">
      <strong>相似照片发现 · {{ labels[job.status] || '状态未知' }}</strong>
      <progress v-if="['QUEUED','RUNNING'].includes(job.status)" :value="job.progress" max="100" aria-label="相似发现进度" />
      <p>{{ job.progress }}% · {{ job.message }}</p>
      <button v-if="['QUEUED','RUNNING'].includes(job.status)" type="button" @click="cancelJob(job.jobId)">取消发现</button>
      <details v-for="group in job.groups || []" :key="group.similarId">
        <summary>相似组 · {{ group.totalFiles }} 张{{ group.truncated ? '（显示前50张）' : '' }}</summary>
        <div class="photo-grid">
          <figure v-for="photo in group.fileList" :key="photo.fileId">
            <img v-if="safeImage(photo.thumbnailUrl)" :src="photo.thumbnailUrl" :alt="photo.originFileName" loading="lazy" />
            <figcaption>{{ photo.originFileName }}</figcaption>
          </figure>
        </div>
      </details>
    </article>
    <p v-if="!loading && !activity.length">暂无整理操作。可在对话中检索照片、生成预览，再确认执行。</p>
    <article v-for="item in activity" :key="item.operation.pendingActionId" class="activity-card">
      <strong>{{ labels[item.operation.status] || '状态未知' }}</strong>
      <p>{{ item.operation.summary }}</p>
      <p>预览 {{ item.operation.previewAffectedFileCount || 0 }} 张<span v-if="item.operation.actualAffectedFileCount != null"> · 实际处理 {{ item.operation.actualAffectedFileCount }} 张 · 跳过 {{ item.operation.skippedFileCount || 0 }} 张</span></p>
      <p v-if="item.operation.failureMessage" role="status">{{ item.operation.failureMessage }}</p>
      <div class="photo-grid">
        <figure v-for="photo in item.photos" :key="photo.fileId">
          <img v-if="safeImage(photo.thumbnailUrl)" :src="photo.thumbnailUrl" :alt="photo.name" loading="lazy" />
          <figcaption>{{ photo.name }}</figcaption>
        </figure>
      </div>
    </article>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import request from '@/api/request'

type Photo = { fileId: string; name: string; thumbnailUrl?: string }
type Operation = { pendingActionId: string; status: string; summary: string; previewAffectedFileCount: number; actualAffectedFileCount?: number; skippedFileCount?: number; failureMessage?: string }
type Job = { jobId: string; status: string; progress: number; message: string; groups?: { similarId: string; totalFiles: number; truncated: boolean; fileList: { fileId: string; originFileName: string; thumbnailUrl?: string }[] }[] }
type Envelope<T> = { code: number; data: T }
const activity = ref<{ operation: Operation; photos: Photo[] }[]>([])
const jobs = ref<Job[]>([])
const error = ref('')
const loading = ref(false)
const busy = ref(false)
const conversationId = `panel-${crypto.randomUUID()}`
const labels: Record<string,string> = { PREVIEWED:'等待本人确认', QUEUED:'排队中', RUNNING:'分析中', EXECUTING:'执行中', SUCCEEDED:'已完成', FAILED:'未完成', CANCELLED:'已取消', EXPIRED:'确认已过期', UNKNOWN:'状态未知' }
let stopped = false
let timer: ReturnType<typeof setTimeout> | undefined
const safeImage = (url?: string) => {
  try { return !!url && ['http:','https:'].includes(new URL(url).protocol) } catch { return false }
}
const refresh = async () => {
  if (loading.value || stopped) return
  loading.value = true
  try {
    const [a,j] = await Promise.all([
      request<Envelope<typeof activity.value>>('/agent/activity',{method:'GET'}),
      request<Envelope<Job[]>>('/agent/discoveryJobs',{method:'GET'}),
    ])
    if (a.code !== 200 || j.code !== 200) throw new Error('无法确认服务端状态')
    if (!stopped) {activity.value=a.data; jobs.value=j.data; error.value=''}
  } catch {if (!stopped) error.value='暂时无法获取操作状态，请稍后刷新。'}
  finally {loading.value=false}
}
const startDiscovery = async () => {
  busy.value=true
  try {
    await request('/agent/discoverSimilarFiles',{method:'POST',params:{conversationId},data:{similarity:.9,size:20}})
    await refresh()
  } catch {error.value='未能提交发现任务，请检查是否已有任务在执行。'}
  finally {busy.value=false}
}
const cancelJob = async (id: string) => {
  try {await request(`/agent/discoveryJobs/${encodeURIComponent(id)}/cancel`,{method:'POST'}); await refresh()}
  catch {error.value='暂时无法确认取消结果，请刷新状态。'}
}
const poll = async () => {await refresh(); if (!stopped) timer=setTimeout(poll,4000)}
onMounted(poll)
onUnmounted(() => {stopped=true; if (timer) clearTimeout(timer)})
</script>

<style scoped>
.activity-panel{overflow:auto;flex:1;padding:16px;background:#f6f7fb;color:#243047}
.activity-controls{display:flex;gap:8px}.activity-panel button{padding:8px 12px;background:white;border:1px solid #b9bfd0;border-radius:8px;cursor:pointer}.activity-panel button:disabled{opacity:.5}
.help,.activity-card p{font-size:12px;line-height:1.6}.help{color:#58657a}.activity-card{padding:14px;margin-top:12px;border:1px solid #e0e3eb;border-radius:12px;background:white}.activity-card strong{font-size:14px}.activity-card progress{display:block;width:100%;margin-top:10px}
.photo-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}figure{margin:0}img{width:100%;aspect-ratio:1;object-fit:cover;border-radius:6px}figcaption{font-size:11px;overflow-wrap:anywhere;line-height:1.4}summary{cursor:pointer;margin:10px 0;font-size:13px}
</style>
