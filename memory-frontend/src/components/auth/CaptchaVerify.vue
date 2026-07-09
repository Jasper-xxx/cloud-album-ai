<template>
  <div class="captcha-box">
    <canvas
      ref="canvasRef"
      class="captcha-canvas"
      width="120"
      height="44"
      @click="refreshCaptcha"
    />
    <el-button text class="captcha-refresh" @click="refreshCaptcha">
      看不清？换一张
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

const emit = defineEmits<{
  (e: 'change', value: string): void
}>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
const currentCode = ref('')
const chars = '23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz'

const randomInt = (min: number, max: number) =>
  Math.floor(Math.random() * (max - min + 1)) + min

const randomColor = (min = 60, max = 180) =>
  `rgb(${randomInt(min, max)}, ${randomInt(min, max)}, ${randomInt(min, max)})`

const generateCode = () => {
  let code = ''
  for (let i = 0; i < 4; i++) {
    code += chars[randomInt(0, chars.length - 1)]
  }
  return code
}

const drawCaptcha = () => {
  const canvas = canvasRef.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const { width, height } = canvas
  ctx.clearRect(0, 0, width, height)

  ctx.fillStyle = 'rgba(8, 12, 39, 0.58)'
  ctx.fillRect(0, 0, width, height)

  ctx.strokeStyle = 'rgba(151, 165, 231, 0.24)'
  ctx.strokeRect(0, 0, width, height)

  currentCode.value = generateCode()

  for (let i = 0; i < currentCode.value.length; i++) {
    const char = currentCode.value[i]
    const x = 18 + i * 24
    const y = randomInt(26, 34)
    const rotate = (Math.random() - 0.5) * 0.7

    ctx.save()
    ctx.translate(x, y)
    ctx.rotate(rotate)
    ctx.font = `${randomInt(20, 26)}px Arial`
    ctx.fillStyle = randomColor(170, 245)
    ctx.shadowColor = randomColor(135, 220)
    ctx.shadowBlur = 5
    ctx.fillText(char, 0, 0)
    ctx.restore()
  }

  for (let i = 0; i < 4; i++) {
    ctx.beginPath()
    ctx.moveTo(randomInt(0, width), randomInt(0, height))
    ctx.lineTo(randomInt(0, width), randomInt(0, height))
    ctx.strokeStyle = randomColor(95, 185)
    ctx.lineWidth = 1
    ctx.stroke()
  }

  for (let i = 0; i < 24; i++) {
    ctx.beginPath()
    ctx.arc(randomInt(0, width), randomInt(0, height), 1, 0, Math.PI * 2)
    ctx.fillStyle = randomColor(110, 205)
    ctx.fill()
  }

  emit('change', currentCode.value)
}

const refreshCaptcha = () => {
  drawCaptcha()
}

defineExpose({
  refreshCaptcha,
})

onMounted(() => {
  drawCaptcha()
})
</script>

<style scoped>
/* 仅占据画布+换一张所需宽度，禁止 width:100%（否则会与同行输入框抢空间，把输入框挤到无法输入） */
.captcha-box {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  width: auto;
  max-width: 100%;
}

.captcha-canvas {
  width: 120px;
  height: 44px;
  border-radius: 10px;
  cursor: pointer;
  flex-shrink: 0;
}

.captcha-refresh {
  font-size: 13px;
  color: #6366f1 !important;
  padding: 0 4px;
}
</style>
