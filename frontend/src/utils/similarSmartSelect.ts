export type SmartSelectStrategy =
  | 'largestSize'
  | 'highestResolution'
  | 'latestUpload'
  | 'earliestShot'
  | 'aiQuality'

export interface SmartSelectConfig {
  strategy: SmartSelectStrategy
  retainCount: number
}

export interface SmartSelectStrategyOption {
  value: SmartSelectStrategy
  label: string
  description: string
}

type SimilarFile = API.FileInfo & {
  uploadTime?: string
  dateTimeOriginal?: string
  lastModifiedTime?: string
}

const FORMAT_WEIGHT: Record<string, number> = {
  'image/heic': 1,
  'image/heif': 1,
  'image/png': 0.94,
  'image/tiff': 0.92,
  'image/jpeg': 0.88,
  'image/jpg': 0.88,
  'image/webp': 0.84,
  'image/bmp': 0.78,
  'image/gif': 0.7,
}

export const SMART_SELECT_STRATEGY_OPTIONS: SmartSelectStrategyOption[] = [
  { value: 'largestSize', label: '最大文件', description: '优先保留文件体积最大的图片' },
  { value: 'highestResolution', label: '最高分辨率', description: '优先保留像素数量最多的图片' },
  { value: 'latestUpload', label: '最新上传', description: '优先保留最近上传到云相册的图片' },
  { value: 'earliestShot', label: '最早拍摄', description: '优先保留拍摄时间最早的图片' },
  { value: 'aiQuality', label: 'AI质量评估', description: '综合分辨率、文件大小和格式评估图片质量' },
]

export function normalizeRetainCount(value: number): number {
  if (!Number.isFinite(value)) {
    return 1
  }
  return Math.min(5, Math.max(1, Math.floor(value)))
}

export function getSmartSelectStrategyLabel(strategy: SmartSelectStrategy): string {
  return SMART_SELECT_STRATEGY_OPTIONS.find((item) => item.value === strategy)?.label ?? '最大文件'
}

export function getSmartSelectionPreviewCount(fileInfoList: API.FileInfoList[], retainCount: number): number {
  const normalizedRetainCount = normalizeRetainCount(retainCount)
  return (fileInfoList ?? []).reduce((total, group) => {
    const groupSize = group?.fileList?.length ?? 0
    return total + Math.max(groupSize - Math.min(groupSize, normalizedRetainCount), 0)
  }, 0)
}

export function buildSmartSelection(
  fileInfoList: API.FileInfoList[],
  config: SmartSelectConfig,
): { selectedIdsByGroup: string[][]; selectedCount: number } {
  const normalizedRetainCount = normalizeRetainCount(config.retainCount)
  const selectedIdsByGroup = (fileInfoList ?? []).map((group) => {
    const files = [...(group?.fileList ?? [])]
    if (!files.length) {
      return []
    }

    const keepCount = Math.min(files.length, normalizedRetainCount)
    const retainedIds = new Set(
      files
        .sort((left, right) => compareFiles(left as SimilarFile, right as SimilarFile, config.strategy))
        .slice(0, keepCount)
        .map((file) => file.fileId),
    )

    return files.filter((file) => !retainedIds.has(file.fileId)).map((file) => file.fileId)
  })

  return {
    selectedIdsByGroup,
    selectedCount: selectedIdsByGroup.reduce((total, group) => total + group.length, 0),
  }
}

function compareFiles(left: SimilarFile, right: SimilarFile, strategy: SmartSelectStrategy): number {
  switch (strategy) {
    case 'largestSize':
      return compareNumber(right.size, left.size, left, right)
    case 'highestResolution':
      return compareNumber(getPixelCount(right), getPixelCount(left), left, right)
    case 'latestUpload':
      return compareNumber(getLatestUploadTimestamp(right), getLatestUploadTimestamp(left), left, right)
    case 'earliestShot':
      return compareNumber(getEarliestShotTimestamp(left), getEarliestShotTimestamp(right), left, right)
    case 'aiQuality':
      return compareNumber(getAiQualityScore(right), getAiQualityScore(left), left, right)
    default:
      return compareNumber(right.size, left.size, left, right)
  }
}

function compareNumber(primaryLeft: number, primaryRight: number, left: SimilarFile, right: SimilarFile): number {
  if (primaryLeft !== primaryRight) {
    return primaryLeft - primaryRight
  }

  const pixelDiff = getPixelCount(right) - getPixelCount(left)
  if (pixelDiff !== 0) {
    return pixelDiff
  }

  const sizeDiff = (right.size ?? 0) - (left.size ?? 0)
  if (sizeDiff !== 0) {
    return sizeDiff
  }

  const uploadDiff = getLatestUploadTimestamp(right) - getLatestUploadTimestamp(left)
  if (uploadDiff !== 0) {
    return uploadDiff
  }

  return (left.fileId ?? '').localeCompare(right.fileId ?? '')
}

function getPixelCount(file: SimilarFile): number {
  return Math.max(0, Number(file.width) || 0) * Math.max(0, Number(file.height) || 0)
}

function getLatestUploadTimestamp(file: SimilarFile): number {
  return parseTime(file.uploadTime) ?? parseTime(file.lastModifiedTime) ?? 0
}

function getEarliestShotTimestamp(file: SimilarFile): number {
  return parseTime(file.dateTimeOriginal) ?? parseTime(file.lastModifiedTime) ?? parseTime(file.uploadTime) ?? Number.MAX_SAFE_INTEGER
}

function getAiQualityScore(file: SimilarFile): number {
  const pixels = getPixelCount(file)
  const megapixels = pixels / 1_000_000
  const sizeMb = Math.max((Number(file.size) || 0) / (1024 * 1024), 0)
  const formatWeight = FORMAT_WEIGHT[String(file.contentType || '').toLowerCase()] ?? 0.8

  const resolutionScore = megapixels * 12
  const sizeScore = Math.log2(sizeMb + 1) * 8
  const formatScore = formatWeight * 25

  return resolutionScore + sizeScore + formatScore
}

function parseTime(value?: string): number | null {
  if (!value) {
    return null
  }
  const timestamp = new Date(value).getTime()
  return Number.isNaN(timestamp) ? null : timestamp
}

