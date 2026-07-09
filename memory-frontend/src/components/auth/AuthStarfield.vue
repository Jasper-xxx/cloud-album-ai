<template>
  <main
    ref="pageRef"
    class="auth-wrapper"
    @pointermove="handlePointerMove"
    @pointerleave="handlePointerLeave"
  >
    <canvas ref="starCanvas" class="star-canvas" aria-hidden="true"></canvas>
    <div class="nebula nebula-left" aria-hidden="true"></div>
    <div class="nebula nebula-right" aria-hidden="true"></div>
    <div class="mouse-glow" aria-hidden="true"></div>

    <section class="form-panel" :aria-label="label">
      <div class="form-container">
        <slot />
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

interface Props {
  label: string
}

interface Star {
  x: number
  y: number
  radius: number
  alpha: number
  phase: number
  speed: number
  depth: number
}

interface Firefly extends Star {
  vx: number
  vy: number
}

defineProps<Props>()

const pageRef = ref<HTMLElement>()
const starCanvas = ref<HTMLCanvasElement>()

const stars: Star[] = []
const fireflies: Firefly[] = []
const pointer = {
  x: 0,
  y: 0,
  smoothX: 0,
  smoothY: 0,
  active: false
}

let animationFrame = 0
let canvasWidth = 0
let canvasHeight = 0
let reduceMotion = false

const createStar = (): Star => ({
  x: Math.random() * canvasWidth,
  y: Math.random() * canvasHeight,
  radius: Math.random() * 1.25 + 0.25,
  alpha: Math.random() * 0.65 + 0.2,
  phase: Math.random() * Math.PI * 2,
  speed: Math.random() * 0.0014 + 0.0005,
  depth: Math.random() * 0.8 + 0.2
})

const createFirefly = (): Firefly => ({
  ...createStar(),
  radius: Math.random() * 1.7 + 1,
  alpha: Math.random() * 0.5 + 0.5,
  speed: Math.random() * 0.0018 + 0.0008,
  vx: (Math.random() - 0.5) * 0.12,
  vy: (Math.random() - 0.5) * 0.12
})

const resetParticles = () => {
  stars.length = 0
  fireflies.length = 0

  const area = canvasWidth * canvasHeight
  const starCount = Math.min(190, Math.max(80, Math.round(area / 8500)))
  const fireflyCount = Math.min(34, Math.max(16, Math.round(area / 42000)))

  for (let index = 0; index < starCount; index++) stars.push(createStar())
  for (let index = 0; index < fireflyCount; index++) fireflies.push(createFirefly())
}

const resizeCanvas = () => {
  const canvas = starCanvas.value
  const page = pageRef.value
  if (!canvas || !page) return

  const ratio = Math.min(window.devicePixelRatio || 1, 2)
  canvasWidth = page.clientWidth
  canvasHeight = page.clientHeight
  canvas.width = Math.round(canvasWidth * ratio)
  canvas.height = Math.round(canvasHeight * ratio)
  canvas.style.width = `${canvasWidth}px`
  canvas.style.height = `${canvasHeight}px`

  const context = canvas.getContext('2d')
  context?.setTransform(ratio, 0, 0, ratio, 0, 0)
  pointer.x = pointer.smoothX = canvasWidth / 2
  pointer.y = pointer.smoothY = canvasHeight / 2
  resetParticles()

  if (reduceMotion) drawScene(performance.now())
}

const drawStar = (
  context: CanvasRenderingContext2D,
  star: Star,
  time: number,
  color: string
) => {
  const twinkle = 0.62 + Math.sin(time * star.speed + star.phase) * 0.38
  const offsetX = pointer.active
    ? (pointer.smoothX - canvasWidth / 2) * star.depth * -0.012
    : 0
  const offsetY = pointer.active
    ? (pointer.smoothY - canvasHeight / 2) * star.depth * -0.012
    : 0

  context.beginPath()
  context.arc(star.x + offsetX, star.y + offsetY, star.radius, 0, Math.PI * 2)
  context.fillStyle = color.replace('ALPHA', String(star.alpha * twinkle))
  context.fill()
}

const drawScene = (time: number) => {
  const canvas = starCanvas.value
  const context = canvas?.getContext('2d')
  if (!canvas || !context) return

  context.clearRect(0, 0, canvasWidth, canvasHeight)
  pointer.smoothX += (pointer.x - pointer.smoothX) * 0.075
  pointer.smoothY += (pointer.y - pointer.smoothY) * 0.075

  for (const star of stars) {
    drawStar(context, star, time, 'rgba(215, 225, 255, ALPHA)')
  }

  for (const firefly of fireflies) {
    if (!reduceMotion) {
      firefly.phase += 0.006
      firefly.vx += Math.cos(firefly.phase) * 0.002
      firefly.vy += Math.sin(firefly.phase * 0.85) * 0.002

      if (pointer.active) {
        const dx = pointer.smoothX - firefly.x
        const dy = pointer.smoothY - firefly.y
        const distance = Math.hypot(dx, dy)

        if (distance < 190 && distance > 1) {
          const pull = (1 - distance / 190) * 0.012
          firefly.vx += (dx / distance) * pull
          firefly.vy += (dy / distance) * pull

          context.beginPath()
          context.moveTo(firefly.x, firefly.y)
          context.lineTo(pointer.smoothX, pointer.smoothY)
          context.strokeStyle = `rgba(255, 215, 96, ${(1 - distance / 190) * 0.18})`
          context.lineWidth = 0.6
          context.stroke()
        }
      }

      firefly.vx *= 0.985
      firefly.vy *= 0.985
      firefly.x += firefly.vx
      firefly.y += firefly.vy

      if (firefly.x < -20) firefly.x = canvasWidth + 20
      if (firefly.x > canvasWidth + 20) firefly.x = -20
      if (firefly.y < -20) firefly.y = canvasHeight + 20
      if (firefly.y > canvasHeight + 20) firefly.y = -20
    }

    context.save()
    context.shadowColor = 'rgba(255, 203, 62, 0.95)'
    context.shadowBlur = 12
    drawStar(context, firefly, time, 'rgba(255, 220, 112, ALPHA)')
    context.restore()
  }

  if (!reduceMotion) animationFrame = requestAnimationFrame(drawScene)
}

const handlePointerMove = (event: PointerEvent) => {
  const page = pageRef.value
  if (!page) return

  const bounds = page.getBoundingClientRect()
  pointer.x = event.clientX - bounds.left
  pointer.y = event.clientY - bounds.top
  pointer.active = true
  page.style.setProperty('--mouse-x', `${pointer.x}px`)
  page.style.setProperty('--mouse-y', `${pointer.y}px`)
  page.style.setProperty('--mouse-opacity', '1')
}

const handlePointerLeave = () => {
  pointer.active = false
  pageRef.value?.style.setProperty('--mouse-opacity', '0')
}

onMounted(() => {
  reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  resizeCanvas()
  window.addEventListener('resize', resizeCanvas)
  drawScene(performance.now())
})

onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame)
  window.removeEventListener('resize', resizeCanvas)
})
</script>

<style scoped>
.auth-wrapper {
  --mouse-x: 50%;
  --mouse-y: 50%;
  --mouse-opacity: 0;
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  width: 100%;
  height: 100%;
  min-height: 100vh;
  min-height: 100dvh;
  overflow: hidden;
  isolation: isolate;
  background:
    radial-gradient(circle at 50% 115%, rgba(45, 42, 122, 0.5), transparent 42%),
    linear-gradient(145deg, #050714 0%, #090c25 46%, #11103a 100%);
}

.auth-wrapper::before,
.auth-wrapper::after {
  position: absolute;
  z-index: -2;
  content: '';
  pointer-events: none;
}

.auth-wrapper::before {
  inset: 0;
  opacity: 0.36;
  background-image:
    radial-gradient(circle at 18% 26%, rgba(255, 255, 255, 0.85) 0 1px, transparent 1.5px),
    radial-gradient(circle at 74% 18%, rgba(255, 232, 156, 0.8) 0 1px, transparent 1.4px),
    radial-gradient(circle at 82% 72%, rgba(177, 196, 255, 0.72) 0 1px, transparent 1.4px);
  background-size: 93px 93px, 137px 137px, 171px 171px;
}

.auth-wrapper::after {
  inset: auto 0 0;
  height: 32%;
  background: linear-gradient(to top, rgba(4, 5, 18, 0.58), transparent);
}

.star-canvas,
.nebula,
.mouse-glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.star-canvas {
  z-index: -1;
}

.nebula {
  z-index: -2;
  filter: blur(22px);
  opacity: 0.55;
  animation: nebula-drift 16s ease-in-out infinite alternate;
}

.nebula-left {
  background: radial-gradient(ellipse at 14% 68%, rgba(84, 55, 180, 0.27), transparent 32%);
}

.nebula-right {
  background: radial-gradient(ellipse at 88% 24%, rgba(35, 93, 170, 0.2), transparent 30%);
  animation-delay: -8s;
}

.mouse-glow {
  z-index: -1;
  opacity: var(--mouse-opacity);
  background: radial-gradient(
    circle 150px at var(--mouse-x) var(--mouse-y),
    rgba(255, 207, 80, 0.1),
    rgba(94, 111, 255, 0.045) 38%,
    transparent 72%
  );
  transition: opacity 0.35s ease;
}

.form-panel {
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: 100%;
  max-width: 100vw;
  min-width: 0;
  padding: 32px 20px;
  overflow-y: auto;
}

.form-container {
  --auth-accent: #c4ae70;
  --auth-accent-hover: #d3bf86;
  --auth-input-fill: #0f1438;
  position: relative;
  width: min(100%, 420px);
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 38px 36px 32px;
  border: 1px solid rgba(153, 169, 255, 0.22);
  border-radius: 24px;
  background:
    linear-gradient(145deg, rgba(29, 34, 82, 0.68), rgba(9, 12, 40, 0.56));
  box-shadow:
    0 30px 90px rgba(0, 0, 0, 0.44),
    0 0 0 1px rgba(255, 224, 138, 0.035) inset,
    0 1px 0 rgba(255, 255, 255, 0.08) inset;
  backdrop-filter: blur(24px) saturate(125%);
  -webkit-backdrop-filter: blur(24px) saturate(125%);
  animation: card-arrive 0.7s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.form-container::before {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  content: '';
  pointer-events: none;
  background:
    linear-gradient(135deg, rgba(188, 199, 255, 0.11), transparent 40%),
    radial-gradient(circle at 85% 8%, rgba(255, 212, 91, 0.055), transparent 26%);
}

.form-container :deep(.auth-form) {
  position: relative;
  z-index: 1;
  background: transparent;
}

.form-container :deep(.auth-title) {
  color: #f7f6ff;
  text-shadow: 0 2px 18px rgba(130, 139, 255, 0.18);
}

.form-container :deep(.auth-subtitle) {
  color: rgba(211, 215, 239, 0.68);
}

.form-container :deep(.switch-button) {
  color: var(--auth-accent) !important;
}

.form-container :deep(.switch-button:hover) {
  color: var(--auth-accent-hover) !important;
  background: rgba(196, 174, 112, 0.08) !important;
}

.form-container :deep(.el-input__wrapper) {
  border-color: rgba(151, 165, 231, 0.18);
  background: rgba(8, 12, 39, 0.46) !important;
  box-shadow: 0 8px 20px rgba(2, 4, 18, 0.12) inset !important;
}

.form-container :deep(.el-input__wrapper:hover) {
  border-color: rgba(167, 178, 255, 0.42);
  background: rgba(14, 19, 53, 0.58) !important;
}

.form-container :deep(.el-input__wrapper.is-focus) {
  border-color: rgba(244, 206, 104, 0.72);
  background: rgba(15, 20, 56, 0.7) !important;
  box-shadow:
    0 0 0 3px rgba(244, 206, 104, 0.1),
    0 8px 22px rgba(2, 4, 18, 0.16) inset !important;
}

.form-container :deep(.el-input__inner) {
  color: rgba(248, 248, 255, 0.94);
  caret-color: var(--auth-accent);
}

.form-container :deep(.el-input__inner::placeholder) {
  color: rgba(190, 196, 224, 0.48);
}

/* Keep browser-saved credentials visually consistent with the dark auth fields. */
.form-container :deep(.el-input__inner:-webkit-autofill),
.form-container :deep(.el-input__inner:-webkit-autofill:hover),
.form-container :deep(.el-input__inner:-webkit-autofill:focus),
.form-container :deep(.el-input__inner:-webkit-autofill:active) {
  border-radius: 0;
  -webkit-text-fill-color: rgba(248, 248, 255, 0.94) !important;
  caret-color: var(--auth-accent);
  -webkit-box-shadow: 0 0 0 1000px var(--auth-input-fill) inset !important;
  box-shadow: 0 0 0 1000px var(--auth-input-fill) inset !important;
  transition: background-color 9999s ease-out 0s;
}

.form-container :deep(.el-input__inner:autofill) {
  color: rgba(248, 248, 255, 0.94);
  caret-color: var(--auth-accent);
  background: var(--auth-input-fill) !important;
  box-shadow: 0 0 0 1000px var(--auth-input-fill) inset !important;
}

.form-container :deep(.el-input__prefix),
.form-container :deep(.el-input__suffix) {
  color: rgba(190, 199, 236, 0.56);
}

.form-container :deep(.el-form-item__error) {
  color: #ffb7b7;
}

.form-container :deep(.code-input .el-input) {
  min-width: 0;
}

.form-container :deep(.submit-btn),
.form-container :deep(.code-button) {
  color: rgba(255, 255, 255, 0.94);
  border-color: rgba(151, 145, 255, 0.54);
  background: linear-gradient(135deg, rgba(103, 96, 218, 0.82), rgba(65, 60, 157, 0.76));
  box-shadow:
    0 8px 24px rgba(37, 31, 119, 0.25),
    0 1px 0 rgba(255, 255, 255, 0.14) inset;
  backdrop-filter: blur(8px);
}

.form-container :deep(.submit-btn:hover),
.form-container :deep(.code-button:hover:not(:disabled)) {
  border-color: rgba(255, 219, 117, 0.58);
  background: linear-gradient(135deg, rgba(116, 108, 230, 0.9), rgba(76, 69, 177, 0.84));
  box-shadow:
    0 10px 28px rgba(48, 42, 139, 0.34),
    0 0 18px rgba(255, 216, 109, 0.08);
}

.form-container :deep(.code-button.is-disabled) {
  color: rgba(214, 217, 236, 0.42);
  border-color: rgba(135, 143, 190, 0.16);
  background: rgba(61, 66, 108, 0.34);
}

.form-container :deep(.captcha-canvas) {
  border: 1px solid rgba(151, 165, 231, 0.2);
  box-shadow: 0 8px 20px rgba(2, 4, 18, 0.16);
}

.form-container :deep(.captcha-refresh) {
  color: var(--auth-accent) !important;
}

.form-container :deep(.captcha-refresh:hover) {
  color: var(--auth-accent-hover) !important;
  background: rgba(196, 174, 112, 0.075) !important;
}

.form-container :deep(.auth-footer) {
  color: rgba(203, 207, 231, 0.58);
}

.form-container :deep(.auth-footer a) {
  color: var(--auth-accent);
}

.form-container :deep(.auth-footer a:hover) {
  color: var(--auth-accent-hover);
}

@keyframes card-arrive {
  from {
    opacity: 0;
    transform: translateY(18px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes nebula-drift {
  from {
    transform: translate3d(-1.5%, 1%, 0) scale(1);
  }
  to {
    transform: translate3d(1.5%, -1%, 0) scale(1.08);
  }
}

@media (max-width: 560px) {
  .form-panel {
    padding: 20px 14px;
  }

  .form-container {
    width: 100%;
    max-width: calc(100vw - 28px);
    padding: 32px 22px 28px;
    border-radius: 20px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .nebula,
  .form-container {
    animation: none;
  }
}
</style>
