<template>
  <teleport to="body">
    <transition name="viewer-fade">
      <div class="el-image-viewer-main">
        <!-- 自定义属性 --><!-- calc(100% - 312px) -->
        <div ref="wrapper" :tabindex="-1" class="el-image-viewer__wrapper"
          :style="{ zIndex, width: showDrawer ? '80%' : '100%' }">


          <div class="el-image-viewer__mask" @click.self="hideOnClickModal && hide()"></div>
          <!-- CLOSE -->
          <span class="el-image-viewer__btn el-image-viewer__close" @click="hide">
            <el-icon>
              <Close />
            </el-icon>
          </span>
          <!-- ARROW -->

          <div v-if="$slots.viewer" class="el-image-viewer__custom-actions" style="z-index: 4; position: relative;">
            <slot name="viewer">
            </slot>
          </div>
          <template v-if="!isSingle">
            <span class="el-image-viewer__btn el-image-viewer__prev" :class="{ 'is-disabled': !infinite && isFirst }"
              @click="prev">
              <el-icon>
                <ArrowLeft />
              </el-icon>
            </span>
            <span class="el-image-viewer__btn el-image-viewer__next" :class="{ 'is-disabled': !infinite && isLast }"
              @click="next">
              <el-icon>
                <ArrowRight />
              </el-icon>
            </span>
          </template>
          <!-- ACTIONS -->
          <div v-if="isImage" class="el-image-viewer__btn el-image-viewer__actions">
            <div class="el-image-viewer__actions__inner">


              <el-icon @click="handleActions('zoomOut')">
                <ZoomOut />
              </el-icon>
              <el-icon @click="handleActions('zoomIn')">
                <ZoomIn />
              </el-icon>
              <i class="el-image-viewer__actions__divider"></i>

              <el-icon @click="toggleMode">
                <component :is="mode.icon"></component>
              </el-icon>
              <i class="el-image-viewer__actions__divider"></i>

              <el-icon @click="handleActions('anticlocelise')">
                <RefreshLeft />
              </el-icon>

              <el-icon @click="handleActions('clocelise')">
                <RefreshRight />
              </el-icon>
            </div>
          </div>
          <!-- CANVAS -->
          <div class="el-image-viewer__canvas">
            <template v-for="(url, i) in urlList">
              <img v-if="i === index && isImage" ref="media" :key="url" :src="url" :style="mediaStyle"
                class="el-image-viewer__img" @load="handleMediaLoad" @error="handleMediaError"
                @mousedown="handleMouseDown" />
              <video controls v-if="i === index && isVideo" ref="media" :key="url" :src="url" :style="mediaStyle"
                class="el-image-viewer__img" @loadedmetadata="handleMediaLoad" @canplay="handleMediaLoad"
                @error="handleMediaError"></video>
            </template>
          </div>



        
          <!-- 展示照片元数据 -->
          <div v-if="fileMetaData&&fileMetaData.fileInfo" class="picture-info-showDrawer" :style="{ width: showDrawer ? '326px' : '0' }">
            <div @click="toggleDrawer()"
              style="cursor: pointer;color:#ffffff;font-size: 25px;position: absolute;left: 10px;top:10px;">
              <el-icon v-if="showDrawer">
                <Expand />
              </el-icon>
              <el-icon v-else>
                <Fold />
              </el-icon>
            </div>
            <div class="picture-info-title">照片详情</div>
            <div class="picture-info-item" >

              <i-ep-picture v-if=" fileMetaData.fileInfo.category == 'image'" class="picture-info-icon" />
              <i-ep-VideoPlay v-else class="picture-info-icon" />

              <div class="picture-info-right">
                <div class="picture-info-name"> {{ fileMetaData.fileInfo.originFileName }}</div>
                <div class="picture-info-content">{{ fileMetaData.fileInfo.width }} <span>x</span>{{
                  fileMetaData.fileInfo.height
                }}<span>，</span> {{ formatFileSize(fileMetaData.fileInfo.size) }}</div>
              </div>

            </div>
            <div class="picture-info-item">

              <i-ep-Calendar class="picture-info-icon" />
              <div class="picture-info-right">
                <div class="picture-info-name"><span>拍摄时间：</span> </div>
                <div class="picture-info-content">{{ formatDate(fileMetaData.fileInfo.dateTimeOriginal) }}</div>
              </div>
            </div>
            <div class="picture-info-item">
              <i-ep-UploadFilled class="picture-info-icon" />
              <div class="picture-info-right">
                <div class="picture-info-name"><span>上传云端时间：</span> </div>
                <div class="picture-info-content">{{ formatDate(fileMetaData.fileInfo.uploadTime) }}</div>
              </div>
            </div>

            <div class="picture-info-item">
              <i-ep-edit class="picture-info-icon" />
              <div class="picture-info-right">
                <div class="picture-info-name"><span>最后修改时间：</span> </div>
                <div class="picture-info-content">{{ formatDate(fileMetaData.fileInfo.lastModifiedTime) }}</div>
              </div>
            </div>
            <div class="picture-info-item">

              <i-ep-Iphone class="picture-info-icon" v-if="fileMetaData.fileInfo.category == 'image'" />
              <i-ep-VideoCamera class="picture-info-icon" v-else />
              <div class="picture-info-right">
                <div class="picture-info-name"><span>设备：</span>
                  <span v-if="fileMetaData.fileInfo.make == ' ' || fileMetaData.fileInfo.model == ' '">
                    未知
                  </span>
                  <span v-else>
                    {{ fileMetaData.fileInfo.make + "，" +
                      fileMetaData.fileInfo.model
                    }}
                  </span>
                </div>
                <div class="picture-info-content" v-if="fileMetaData.fileInfo.category == 'image'">

                  <span v-if="fileMetaData.imageMetaData.iso == null">
                    无信息
                  </span>
                  <div v-else>
                    {{ fileMetaData.imageMetaData.exposureTime }}<span>s</span><span
                      class="picture-info-blank">,</span>
                    <span>iso-</span>{{ fileMetaData.imageMetaData.iso }}
                  </div>

                </div>
                <div class="picture-info-content" v-else><span>视频时长：</span>
                  {{ formatDuration(fileMetaData.videoMetaData.duration) }}<span class="picture-info-blank">,</span>

                  <span>视频帧率：</span>
                  {{ Math.floor(fileMetaData.videoMetaData.fps) }}<span>fps</span>
                </div>
              </div>
            </div>
            <div class="picture-info-item" v-if="fileMetaData.fileInfo.category == 'image'">
              <i-ep-Camera class="picture-info-icon" />
              <div class="picture-info-right">
                <div class="picture-info-name">

                  <span>软件：</span>
                  <span v-if="fileMetaData.imageMetaData.software == null">
                    未知
                  </span>
                  <span v-else>
                    {{ fileMetaData.imageMetaData.software }}
                  </span>
                </div>
                <div class="picture-info-content">

                  <span v-if="fileMetaData.imageMetaData.fnumber == null">
                    无信息
                  </span>
                  <div v-else>
                    <span>f/{{ fileMetaData.imageMetaData.fnumber }}</span><span class="picture-info-blank">,</span>
                    {{ fileMetaData.imageMetaData.focalLength }}<span>mm</span>
                  </div>

                </div>
              </div>
            </div>
            <div class="picture-info-item">

              <i-ep-LocationInformation class="picture-info-icon" />
              <div class="picture-info-right">
                <div class="picture-info-name" style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">
                  <span>位置：</span>
                  <!-- 显示模式 -->
                  <template v-if="!editingLocation">
                    <span>{{ fileMetaData.fileInfo.location || '未知' }}</span>
                    <el-icon style="cursor:pointer;color:#999;font-size:13px;" title="修正位置" @click="startEditLocation">
                      <Edit />
                    </el-icon>
                  </template>

                  <!-- 编辑模式：地址文字 / GPS 坐标 两种子模式 -->
                  <template v-else>
                    <!-- 子模式切换 -->
                    <el-segmented
                      v-model="locationEditMode"
                      :options="[{ label: '地址', value: 'text' }, { label: '坐标', value: 'gps' }]"
                      size="small"
                      style="margin-right:2px;"
                    />

                    <!-- 地址文字模式 -->
                    <template v-if="locationEditMode === 'text'">
                      <el-input
                        v-model="locationInput"
                        size="small"
                        style="width:130px;"
                        placeholder="输入城市名"
                        @keyup.enter="saveLocation"
                        :disabled="savingLocation"
                      />
                      <el-icon style="cursor:pointer;color:#67c23a;" title="保存地址" @click="saveLocation">
                        <Check />
                      </el-icon>
                    </template>

                    <!-- GPS 坐标模式 -->
                    <template v-else>
                      <el-input
                        v-model="latInput"
                        size="small"
                        style="width:90px;"
                        placeholder="纬度 (-90~90)"
                        :disabled="savingLocation"
                      />
                      <el-input
                        v-model="lonInput"
                        size="small"
                        style="width:100px;"
                        placeholder="经度 (-180~180)"
                        :disabled="savingLocation"
                      />
                      <el-icon style="cursor:pointer;color:#67c23a;" title="保存坐标并重新解析地址" @click="saveGpsCoordinate">
                        <Check />
                      </el-icon>
                    </template>

                    <!-- 公共取消按钮 -->
                    <el-icon style="cursor:pointer;color:#999;" title="取消" @click="cancelEditLocation">
                      <Close />
                    </el-icon>
                  </template>
                </div>
                <div class="picture-info-content"></div>
              </div>
            </div>
          </div>




        </div>
      </div>
    </transition>
  </teleport>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import {
  selectMetaDataByFileId,
  selectSharedMetaDataByFileId,
  updateLocation,
  updateGpsCoordinate,
} from "@/api/file/file";
import $bus from '@/utils/bus.ts';
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Close,
  Edit,
  Expand,
  Fold,
  FullScreen,
  RefreshLeft,
  RefreshRight,
  ScaleToOriginal,
  ZoomIn,
  ZoomOut,
} from '@element-plus/icons-vue';
// 修改后（更安全的初始化结构）：
const fileMetaData = ref({
  fileInfo: {
   
  },
  imageMetaData: {
    
  },
  videoMetaData: {
   
  }
})


const props = defineProps({
  showDrawer: {
    type: Boolean,
    default: false,
  },
  shareToken: {
    type: String,
    default: "",
  },
  fileIdList: {
    type: Array,
    default: [],
  },
  urlList: {
    type: Array,
    default: () => [],
  },
  zIndex: {
    type: Number,
    default: 9999,
  },
  initialIndex: {
    type: Number,
    default: 0,
  },
  infinite: {
    type: Boolean,
    default: true,
  },
  hideOnClickModal: {
    type: Boolean,
    default: false,
  },  // 视频格式
  videoType: {
    type: Array,
    default: ["avi", "wmv", "mpg", "mpeg", "mov", "rm", "ram", "swf", "flv", "mp4", "mp3", "wma", "avi", "rm", "rmvb", "flv", "mpg", "mkv"]
  },
  //图片格式
  imgType: {
    type: Array,
    default: ['svgz', 'pjp', 'png', 'ico', 'avif', 'tiff', 'tif', 'jfif', 'svg', 'xbm', 'pjpeg', 'webp', 'jpg', 'jpeg', 'bmp', 'gif']
  },

},)
const emits = defineEmits(['close', "switch"])
const EVENT_CODE = {
  tab: 'Tab',
  enter: 'Enter',
  space: 'Space',
  left: 'ArrowLeft', // 37
  up: 'ArrowUp', // 38
  right: 'ArrowRight', // 39
  down: 'ArrowDown', // 40
  esc: 'Escape',
  delete: 'Delete',
  backspace: 'Backspace',
}


const isFirefox = function () {
  return !!window.navigator.userAgent.match(/firefox/i)
}

const rafThrottle = function (fn) {
  let locked = false
  return function (...args) {
    if (locked) return
    locked = true
    window.requestAnimationFrame(() => {
      fn.apply(this, args)
      locked = false
    })
  }
}

const Mode = {
  CONTAIN: {
    name: 'contain',
    icon: FullScreen,
  },
  ORIGINAL: {
    name: 'original',
    icon: ScaleToOriginal,
  },
}

const mousewheelEventName = isFirefox() ? 'DOMMouseScroll' : 'mousewheel'

let _keyDownHandler = null
let _mouseWheelHandler = null
let _dragHandler = null
const loading = ref(true)
const index = ref(props.initialIndex)
const wrapper = ref(null)
const media = ref(null)
const mode = ref(Mode.CONTAIN)
const isMounted = ref(true)
const transform = ref({
  scale: 1,
  deg: 0,
  offsetX: 0,
  offsetY: 0,
  enableTransition: false,
})

const isSingle = computed(() => {
  const { urlList } = props
  return urlList.length <= 1
})

const isFirst = computed(() => {
  return index.value === 0
})

const isLast = computed(() => {
  return index.value === props.urlList.length - 1
})

const currentMedia = computed(() => {
  return props.urlList[index.value]
})

const isVideo = computed(() => {
  let currentUrl = props.urlList[index.value]
  // 新增
  currentUrl = currentUrl.split('?')[0]

  const name = currentUrl.split('.').slice(-1)[0].toLocaleLowerCase()

  const isVideo = props.videoType.find(itemVideo => itemVideo == name);
  if (isVideo) {
    return true
  } else {
    return false
  }
})

const isImage = computed(() => {
  let currentUrl = props.urlList[index.value]

  currentUrl = currentUrl.split('?')[0]

  const name = currentUrl.split('.').slice(-1)[0].toLocaleLowerCase()

  const isImg = props.imgType.find(itemVideo => itemVideo == name);

  if (isImg) {
    return true
  } else {
    return false
  }
})

const mediaStyle = computed(() => {
  const { scale, deg, offsetX, offsetY, enableTransition } =
    transform.value
  const style = {
    transform: `scale(${scale}) rotate(${deg}deg)`,
    transition: enableTransition ? 'transform .3s' : '',
    marginLeft: `${offsetX}px`,
    marginTop: `${offsetY}px`,
  }
  if (mode.value.name === Mode.CONTAIN.name) {
    style.maxWidth = '100%'; style.maxHeight = '80%';
  }
  return style
})
function hide() {
  syncDrawerState(false)
  deviceSupportUninstall()
  emits('close')
}

function deviceSupportInstall() {
  _keyDownHandler = rafThrottle((e) => {
    switch (e.code) {
      // ESC
      case EVENT_CODE.esc:
        hide()
        break
      // SPACE
      case EVENT_CODE.space:
        toggleMode()
        break
      // LEFT_ARROW
      case EVENT_CODE.left:
        prev()
        break
      // UP_ARROW
      case EVENT_CODE.up:
        handleActions('zoomIn')
        break
      // RIGHT_ARROW
      case EVENT_CODE.right:
        next()
        break
      // DOWN_ARROW
      case EVENT_CODE.down:
        handleActions('zoomOut')
        break
    }
  })

  _mouseWheelHandler = rafThrottle((e) => {
    // 关闭鼠标滑轮
    //const delta = e.wheelDelta ? e.wheelDelta : -e.detail
    // if (delta > 0) {
    //   handleActions('zoomIn', {
    //     zoomRate: 0.015,
    //     enableTransition: false,
    //   })
    // } else {
    //   handleActions('zoomOut', {
    //     zoomRate: 0.015,
    //     enableTransition: false,
    //   })
    // }
  })

  document.addEventListener('keydown', _keyDownHandler, false)
  document.addEventListener(
    mousewheelEventName,
    _mouseWheelHandler,
    false
  )
}

function deviceSupportUninstall() {
  document.removeEventListener('keydown', _keyDownHandler, false)
  document.removeEventListener(
    mousewheelEventName,
    _mouseWheelHandler,
    false
  )
  _keyDownHandler = null
  _mouseWheelHandler = null
}

function handleMediaLoad() {
  loading.value = false
}

function handleMediaError(e) {
  loading.value = false
}

function handleMouseDown(e) {
  if (loading.value || e.button !== 0) return

  const { offsetX, offsetY } = transform.value
  const startX = e.pageX
  const startY = e.pageY

  const divLeft = wrapper.value.clientLeft
  const divRight =
    wrapper.value.clientLeft + wrapper.value.clientWidth
  const divTop = wrapper.value.clientTop
  const divBottom =
    wrapper.value.clientTop + wrapper.value.clientHeight

  _dragHandler = rafThrottle((ev) => {
    transform.value = {
      ...transform.value,
      offsetX: offsetX + ev.pageX - startX,
      offsetY: offsetY + ev.pageY - startY,
    }
  })
  document.addEventListener('mousemove', _dragHandler, false)
  document.addEventListener(
    'mouseup',
    (e) => {
      const mouseX = e.pageX
      const mouseY = e.pageY
      if (
        mouseX < divLeft ||
        mouseX > divRight ||
        mouseY < divTop ||
        mouseY > divBottom
      ) {
        reset()
      }
      document.removeEventListener(
        'mousemove',
        _dragHandler,
        false
      )
    },
    false
  )

  e.preventDefault()
}

function reset() {
  transform.value = {
    scale: 1,
    deg: 0,
    offsetX: 0,
    offsetY: 0,
    enableTransition: false,
  }
}

function toggleMode() {
  if (loading.value) return

  const modeNames = Object.keys(Mode)
  const modeValues = Object.values(Mode)
  const currentMode = mode.value.name
  const index = modeValues.findIndex((i) => i.name === currentMode)
  const nextIndex = (index + 1) % modeNames.length
  mode.value = Mode[modeNames[nextIndex]]
  reset()
}

function prev() {
  if (isFirst.value && !props.infinite) return
  const len = props.urlList.length
  index.value = (index.value - 1 + len) % len;

}

function next() {
  if (isLast.value && !props.infinite) return
  const len = props.urlList.length
  index.value = (index.value + 1) % len;

}

function handleActions(action, options = {}) {
  if (loading.value) return
  const { zoomRate, rotateDeg, enableTransition } = {
    zoomRate: 0.2,
    rotateDeg: 90,
    enableTransition: true,
    ...options,
  }
  switch (action) {
    case 'zoomOut':
      if (transform.value.scale > 0.2) {
        transform.value.scale = parseFloat(
          (transform.value.scale - zoomRate).toFixed(3)
        )
      }
      break
    case 'zoomIn':
      transform.value.scale = parseFloat(
        (transform.value.scale + zoomRate).toFixed(3)
      )
      break
    case 'clocelise':
      transform.value.deg += rotateDeg
      break
    case 'anticlocelise':
      transform.value.deg -= rotateDeg
      break
  }
  transform.value.enableTransition = enableTransition
}

watch(currentMedia, () => {
  nextTick(() => {
    const $media = media.value
    if (!$media.complete) {
      loading.value = true
    }
  })
})

watch(index, (val) => {
  //修改当前文件id
  $bus.emit('setCurrentFileId', props.fileIdList[index.value]);
  // 切换图片时关闭位置编辑模式并重置子状态
  editingLocation.value  = false
  locationEditMode.value = 'text'

  if (showDrawer.value) {
    loadCurrentMetaData()
  }

  reset()
  emits('switch', val)
})
const showDrawer = ref(false);
// ── 位置手动编辑状态 ──────────────────────────────────────────────────
const editingLocation  = ref(false)    // 是否处于编辑模式
const locationEditMode = ref<'text' | 'gps'>('text')  // 子模式：地址文字 / GPS 坐标
const locationInput    = ref('')       // 地址文字模式输入值
const latInput         = ref('')       // GPS 模式：纬度输入
const lonInput         = ref('')       // GPS 模式：经度输入
const savingLocation   = ref(false)    // 保存请求进行中（防重复提交）

const createEmptyMetaData = () => ({
  fileInfo: {},
  imageMetaData: {},
  videoMetaData: {},
})

const syncDrawerState = (visible) => {
  showDrawer.value = visible
  $bus.emit('showDrawer', visible)
}

const toggleDrawer = () => {
  syncDrawerState(!showDrawer.value)
}

const loadCurrentMetaData = async () => {
  const currentFileId = props.fileIdList[index.value]
  if (!currentFileId) {
    if (isMounted.value) {
      fileMetaData.value = createEmptyMetaData()
    }
    return
  }

  try {
    const res = props.shareToken
      ? await selectSharedMetaDataByFileId({ fileId: currentFileId, shareToken: props.shareToken })
      : await selectMetaDataByFileId({ fileId: currentFileId })
    if (isMounted.value) {
      fileMetaData.value = res?.data ?? createEmptyMetaData()
    }
  } catch (error) {
    if (isMounted.value) {
      fileMetaData.value = createEmptyMetaData()
    }
  }
}

/** 打开编辑模式，预填当前位置及 GPS 坐标 */
function startEditLocation() {
  locationInput.value    = fileMetaData.value?.fileInfo?.location || ''
  latInput.value         = fileMetaData.value?.fileInfo?.latitude  != null
                             ? String(fileMetaData.value.fileInfo.latitude)  : ''
  lonInput.value         = fileMetaData.value?.fileInfo?.longitude != null
                             ? String(fileMetaData.value.fileInfo.longitude) : ''
  locationEditMode.value = 'text'
  editingLocation.value  = true
}

/** 取消编辑 */
function cancelEditLocation() {
  editingLocation.value = false
}

/** 模式1：保存地址文字到后端 */
async function saveLocation() {
  const val = locationInput.value.trim()
  if (!val) { editingLocation.value = false; return }

  const currentFileId = props.fileIdList[index.value]
  if (!currentFileId) { editingLocation.value = false; return }

  savingLocation.value = true
  try {
    const res = await updateLocation({ fileId: currentFileId, locationValue: val })
    if (isMounted.value) {
      if (res.code === 200) {
        if (fileMetaData.value?.fileInfo) fileMetaData.value.fileInfo.location = val
        ElMessage.success('位置已更新')
        editingLocation.value = false
      } else {
        ElMessage.error(res.message || '更新失败')
      }
    }
  } catch {
    if (isMounted.value) {
      ElMessage.error('请求失败，请稍后重试')
    }
  } finally {
    if (isMounted.value) {
      savingLocation.value = false
    }
  }
}

/** 模式2：保存 GPS 坐标到后端，后端自动触发逆地理编码 */
async function saveGpsCoordinate() {
  const lat = parseFloat(latInput.value)
  const lon = parseFloat(lonInput.value)

  if (isNaN(lat) || lat < -90  || lat > 90) {
    ElMessage.warning('纬度须在 -90 ~ 90 之间'); return
  }
  if (isNaN(lon) || lon < -180 || lon > 180) {
    ElMessage.warning('经度须在 -180 ~ 180 之间'); return
  }

  const currentFileId = props.fileIdList[index.value]
  if (!currentFileId) { editingLocation.value = false; return }

  savingLocation.value = true
  try {
    const res = await updateGpsCoordinate({ fileId: currentFileId, latitude: lat, longitude: lon })
    if (isMounted.value) {
      if (res.code === 200) {
        // 先显示"解析中"，后端逆地理编码完成后下次打开会看到真实地址
        if (fileMetaData.value?.fileInfo) {
          fileMetaData.value.fileInfo.latitude  = lat
          fileMetaData.value.fileInfo.longitude = lon
          fileMetaData.value.fileInfo.location  = '解析中...'
        }
        ElMessage.success('GPS 坐标已保存，正在解析地址...')
        editingLocation.value = false
      } else {
        ElMessage.error(res.message || '更新失败')
      }
    }
  } catch {
    if (isMounted.value) {
      ElMessage.error('请求失败，请稍后重试')
    }
  } finally {
    if (isMounted.value) {
      savingLocation.value = false
    }
  }
}
// ✅ 正确写法：使用 getter 函数包装
// 正确写法：监听多个 props 属性
watch(
  [() => props.showDrawer, () => props.fileIdList, () => index.value],
  async ([newShowDrawer]) => {
    showDrawer.value = newShowDrawer;
    if (newShowDrawer) {
      await loadCurrentMetaData()
    } else if (isMounted.value) {
      fileMetaData.value = createEmptyMetaData()
    }
  },
  { immediate: true }
)

onMounted(() => {
  deviceSupportInstall()
  // add tabindex then wrapper can be focusable via Javascript
  // focus wrapper so arrow key can't cause inner scroll behavior underneath
  wrapper.value?.focus?.()
})

onUnmounted(() => {
  isMounted.value = false
  deviceSupportUninstall()
  // 清理其他可能的资源
  _dragHandler = null
})


const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B'

  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = Math.abs(bytes)
  let unitIndex = 0

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }

  // 保留最多1位小数，并移除无用的末尾零
  const formattedSize = size.toFixed(1).replace(/\.0$/, '')

  return `${formattedSize} ${units[unitIndex]}`
}

const formatDate = (isoString) => {
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
  return `${year}年${month}月${day}日 ${period} ${String(hours).padStart(2, '0')}:${minutes}`;

  // 方案2：12小时制带时段（输出：2025年03月04日 下午02:02）
  //const formattedHours = String(hours % 12 || 12).padStart(2, '0'); // 02[6,8](@ref)
  //return `${year}年${month}月${day}日${formattedHours}:${minutes}`;
};


// 时长格式化
const formatDuration = (seconds) => {
  if (!seconds) return '00:00'
  seconds = Math.floor(seconds)
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`
}
</script>
<style scoped>
/* 自定义遮罩 */
.el-image-viewer-main {
  top: 0;
  left: 0;
  position: fixed;
  width: 100%;
  height: 100%;
  z-index: 100;
  background-color: rgba(0, 0, 0, 0.8);
}

/* 自定义操作按钮容器 */
.el-image-viewer__custom-actions {
  pointer-events: auto;
  position: relative;
  z-index: 4;
}

/* 图片预览遮罩层 */
.el-image-viewer__mask {
  opacity: 0;
  background-color: rgba(0, 0, 0, 0);
  z-index: 0;
  pointer-events: none;
}

.el-image-viewer__wrapper {
  transition: 0.5s ease;
}

.el-image-viewer__canvas {
  position: relative;
  z-index: 1;
  pointer-events: auto;
}

.el-image-viewer__img {
  pointer-events: auto;
}

.el-image-viewer__btn {
  z-index: 3;
}

.picture-info-showDrawer {
  position: fixed;
  right: 0;
  top: 0;
  width: 0;
  height: 100%;
  background-color: #212221;
  transition: 0.5s ease;
  user-select: none;
}

.picture-info-title {
  white-space: nowrap;
  padding: 50px 0px 30px 30px;
  font-weight: 700;
  width: 100%;
  height: 30px;
  line-height: 30px;
  color: #fff;

  font-size: 16px;
}

.picture-info-item {
  padding: 0px 20px;
  width: 100%;
  color: #fff;
  display: flex;
  flex-direction: row;
  margin-bottom: 30px;
  font-size: 13px;
  align-items: center;
}

.picture-info-icon {
  width: 24px;
  height: 24px;
  font-size: 20px;
  font-weight: 400;
  color: #999;
}

.picture-info-right {
  padding-left: 15px;
  display: flex;
  justify-content: center;
  flex-direction: column;
}

.picture-info-name {
  width: 250px;
  text-overflow: ellipsis;
}

.picture-info-content {

  display: flex;
  width: 250px;
  flex-direction: row;
  white-space: nowrap;
  color: #999;
  padding-top: 5px;

}

.picture-info-blank {
  margin-left: 3px;
  margin-right: 5px;
}



/* 元数据信息 */
</style>
