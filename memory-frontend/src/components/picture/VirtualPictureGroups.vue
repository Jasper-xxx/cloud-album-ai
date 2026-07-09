<template>
  <div ref="rootRef" class="virtual-picture-groups">
    <div v-if="topPadding > 0" class="virtual-picture-spacer" :style="{ height: `${topPadding}px` }"></div>
    <template v-for="group in visibleGroups" :key="group.key">
      <slot :item="group.item" :index="group.index"></slot>
    </template>
    <div v-if="bottomPadding > 0" class="virtual-picture-spacer" :style="{ height: `${bottomPadding}px` }"></div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'

interface ScaleOption {
  value: string
  label: string
}

interface VirtualGroup {
  key: string
  index: number
  item: API.FileInfoList
}

const props = withDefaults(defineProps<{
  items: API.FileInfoList[]
  itemSize: number
  scale?: ScaleOption
  buffer?: number
}>(), {
  items: () => [],
  itemSize: 150,
  scale: () => ({ value: 'original', label: 'original' }),
  buffer: 900,
})

const rootRef = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const viewportHeight = ref(0)
const containerWidth = ref(0)

const estimateFileWidth = (file: API.FileInfo) => {
  if (props.scale?.value !== 'original') {
    return props.itemSize + 10
  }
  const ratio = file.width && file.height ? file.width / file.height : 1
  const baseWidth = ratio * props.itemSize
  return Math.max(72, Math.min(baseWidth, props.itemSize + 80)) + 10
}

const estimateGroupHeight = (group: API.FileInfoList) => {
  const files = group.fileList || []
  if (files.length === 0) {
    return 40
  }
  const width = Math.max(containerWidth.value || 0, props.itemSize + 10)
  let rowWidth = 0
  let rows = 1
  files.forEach((file) => {
    const itemWidth = estimateFileWidth(file)
    if (rowWidth > 0 && rowWidth + itemWidth > width) {
      rows += 1
      rowWidth = itemWidth
      return
    }
    rowWidth += itemWidth
  })
  return 40 + rows * (props.itemSize + 10)
}

const groupHeights = computed(() => props.items.map(estimateGroupHeight))

const offsets = computed(() => {
  const values: number[] = []
  let offset = 0
  groupHeights.value.forEach((height) => {
    values.push(offset)
    offset += height
  })
  return values
})

const totalHeight = computed(() =>
  groupHeights.value.reduce((sum, height) => sum + height, 0)
)

const visibleRange = computed(() => {
  if (props.items.length === 0) {
    return { start: 0, end: -1 }
  }
  const startOffset = Math.max(0, scrollTop.value - props.buffer)
  const endOffset = scrollTop.value + viewportHeight.value + props.buffer
  let start = 0
  let end = props.items.length - 1

  for (let index = 0; index < props.items.length; index += 1) {
    if (offsets.value[index] + groupHeights.value[index] >= startOffset) {
      start = index
      break
    }
  }

  for (let index = start; index < props.items.length; index += 1) {
    if (offsets.value[index] > endOffset) {
      end = Math.max(start, index)
      break
    }
  }

  return { start, end }
})

const visibleGroups = computed<VirtualGroup[]>(() => {
  const { start, end } = visibleRange.value
  if (end < start) {
    return []
  }
  return props.items.slice(start, end + 1).map((item, offset) => {
    const index = start + offset
    return {
      key: `${item.time || 'group'}-${index}`,
      index,
      item,
    }
  })
})

const topPadding = computed(() => {
  const { start } = visibleRange.value
  return offsets.value[start] || 0
})

const bottomPadding = computed(() => {
  const { end } = visibleRange.value
  if (end < 0) {
    return 0
  }
  const renderedBottom = (offsets.value[end] || 0) + (groupHeights.value[end] || 0)
  return Math.max(0, totalHeight.value - renderedBottom)
})

const sync = (container?: HTMLElement | null) => {
  const scrollContainer = container || rootRef.value?.parentElement
  if (!scrollContainer) {
    return
  }
  scrollTop.value = scrollContainer.scrollTop
  viewportHeight.value = scrollContainer.clientHeight
  containerWidth.value = scrollContainer.clientWidth
}

const syncSoon = () => {
  nextTick(() => {
    requestAnimationFrame(() => sync())
  })
}

watch(
  () => [props.items, props.itemSize, props.scale?.value],
  syncSoon,
  { deep: true }
)

onMounted(syncSoon)

defineExpose({ sync })
</script>

<style scoped>
.virtual-picture-groups {
  min-height: 100%;
}

.virtual-picture-spacer {
  flex: none;
  pointer-events: none;
}
</style>
