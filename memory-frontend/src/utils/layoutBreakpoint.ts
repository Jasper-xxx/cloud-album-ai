/**
 * 与 GlobalHeader / GlobalSider / mobile.css 的紧凑布局断点一致。
 * 使用 992px 以覆盖大屏手机横屏、分屏及小平板，避免仅按 768px 判断时「看得到汉堡键却点不动」。
 */
export const LAYOUT_COMPACT_MAX_WIDTH = 992

export function isLayoutCompact(): boolean {
  if (typeof window === 'undefined') return false
  return window.innerWidth <= LAYOUT_COMPACT_MAX_WIDTH
}
