export const TOKEN_KEY = 'token'

const USER_INFO_KEY = 'USER_INFO'
const PAGE_STATE_KEYS = [
  'CURRENT_PAGE_SET1',
  'CURRENT_PAGE_SET2',
  'CURRENT_PAGE_SET3',
  'CURRENT_PAGE_SET4',
  'CURRENT_PAGE_SET5',
  'CURRENT_PAGE_SET6',
]
const USER_SCOPED_PREFIXES = [
  'ASYNC_TASK_STATUS_SNAPSHOT:',
  'ASYNC_TASK_SEEN_TERMINAL:',
  'ASYNC_TASK_RECOVERY_PROMPTED:',
]

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY)
}

export function clearAllAuthData(): void {
  const stores = [localStorage, sessionStorage]
  const allKeys = [TOKEN_KEY, USER_INFO_KEY, ...PAGE_STATE_KEYS]

  stores.forEach(store => {
    allKeys.forEach(key => store.removeItem(key))
    Object.keys(store)
      .filter(key => USER_SCOPED_PREFIXES.some(prefix => key.startsWith(prefix)))
      .forEach(key => store.removeItem(key))
  })
}
