// Vue Router 路由配置

import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/auth'

// ================================================================
// 路由白名单：在此列表中的路由名称无需登录即可访问
// 守卫遇到这些路由时直接放行，不检查 Token
// ================================================================
const WHITE_LIST = new Set<string>([
  'login',      // 登录页
  'register',   // 注册页
  'SharePage',  // 分享页（分享链接访问，无需账号）
])

// ================================================================
// 路由定义
// ================================================================
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [

    // ── 公开路由（无需登录，不在主布局内）────────────────────────
    {
      path: '/share/:shareToken',
      name: 'SharePage',
      component: () => import('@/views/share/SharePage.vue'),
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('@/views/search/Search.vue'),
    },
    {
      path: '/searchPicture',
      name: 'searchPicture',
      component: () => import('@/views/search/SearchPicture.vue'),
    },

    // ── 主布局（需要登录）────────────────────────────────────────
    {
      path: '/',
      name: 'Main',
      redirect: '/login',
      component: () => import('../layouts/BasicLayout.vue'),
      meta: { requiresAuth: true },
      children: [

        // 图片
        {
          path: '/home',
          name: 'home',
          component: () => import('../views/picture/AllPicture.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/recent',
          name: 'recent',
          component: () => import('../views/picture/RecentUpload.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/brush',
          name: 'brush',
          component: () => import('../views/picture/Brush.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/ai',
          name: 'ai',
          component: () => import('../views/ai/AI.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/imageSearch',
          name: 'imageSearch',
          component: () => import('@/views/imageSearch/ImageSearch.vue'),
          meta: { requiresAuth: true },
        },

        // 相册
        {
          path: '/album',
          name: 'album',
          component: () => import('@/views/album/Album.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/album/:albumId',
          name: 'AlbumPicture',
          component: () => import('@/views/album/AlbumPicture.vue'),
          meta: { requiresAuth: true },
        },

        // 地点
        {
          path: '/location',
          name: 'location',
          component: () => import('@/views/location/Location.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/location/:locationLevel/:locationValue',
          name: 'LocationAlbum',
          component: () => import('@/views/location/LocationAlbum.vue'),
          meta: { requiresAuth: true },
        },

        // 设备
        {
          path: '/model',
          name: 'model',
          component: () => import('@/views/model/Model.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/model/:makeName/:modelName',
          name: 'ModelAlbum',
          component: () => import('@/views/model/ModelAlbum.vue'),
          meta: { requiresAuth: true },
        },

        // 人物
        {
          path: '/person',
          name: 'person',
          component: () => import('@/views/person/Person.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/personAlbum/:personId',
          name: 'personAlbum',
          component: () => import('@/views/person/PersonAlbum.vue'),
          meta: { requiresAuth: true },
        },

        // 用户中心
        {
          path: '/userMid',
          name: 'userMid',
          component: () => import('@/views/user/UserMid.vue'),
          meta: { requiresAuth: true },
          children: [
            {
              path: '/userInfo',
              name: 'userInfo',
              component: () => import('@/views/user/UserInfo.vue'),
              meta: { requiresAuth: true },
            },
            {
              path: '/storageSet',
              name: 'storageSet',
              component: () => import('@/views/user/StorageSet.vue'),
              meta: { requiresAuth: true },
            },
            {
              path: '/securitySet',
              name: 'securitySet',
              component: () => import('@/views/user/SecuritySet.vue'),
              meta: { requiresAuth: true },
            },
            {
              path: '/memberCenter',
              name: 'memberCenter',
              component: () => import('@/views/user/MemberCenter.vue'),
              meta: { requiresAuth: true },
            },
          ],
        },

        // 可视化
        {
          path: '/fileVisual',
          name: 'fileVisual',
          component: () => import('@/views/visual/FileVisual.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'tagVisual',   // 相对路径（无 /），拼接父路径为 /tagVisual
          name: 'tagVisual',
          component: () => import('@/views/visual/TagVisual.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'locationVisual',
          name: 'locationVisual',
          component: () => import('@/views/visual/LocationVisual.vue'),
          meta: { requiresAuth: true },
        },

        // 其他
        {
          path: '/recycle',
          name: 'recycle',
          component: () => import('@/views/recycle/Recycle.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: '/record',
          name: 'record',
          component: () => import('@/views/record/Record.vue'),
          meta: { requiresAuth: true },
        },
      ],
    },

    // ── 登录 / 注册（公开页）────────────────────────────────────
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/auth/Login.vue'),
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/auth/Register.vue'),
    },

    // ── 404 兜底重定向 ────────────────────────────────────
    // 访问任何未匹配的路径时，统一重定向到登录页，避免白屏
    {
      path: '/:pathMatch(.*)*',
      redirect: '/login',
    },
  ],
})

// ================================================================
// 全局路由守卫
// ================================================================
router.beforeEach((to, from, next) => {

  // 读取 Token（从 localStorage 或 sessionStorage，见 auth.ts）
  const token = getToken()
  const isLoggedIn = !!token

  // 判断目标路由是否需要登录（自身或任意父路由含 requiresAuth: true）
  const requiresAuth = to.matched.some(record => record.meta?.requiresAuth)

  // 判断目标路由是否在白名单中（直接放行，无需检查 Token）
  const isPublicRoute = WHITE_LIST.has(to.name as string)

  // ── 场景A：白名单页，直接放行 ───────────────────────────────
  // 登录页、注册页、分享页无需登录态检查
  if (isPublicRoute) {
    // ── 场景C（可选）：已登录时访问登录/注册页，自动跳到首页 ──────
    // 作用：已登录的用户手动输入 /login 时，不显示登录页，直接进入主界面
    //
    // ★ 如需关闭此行为（即允许已登录用户访问 /login 页），注释下方 3 行即可
    if (isLoggedIn && (to.name === 'login' || to.name === 'register')) {
      return next({ name: 'home' })
    }
    return next()
  }

  // ── 场景B：需要登录但无 Token ─────────────────────────────────
  // 静默重定向到登录页，不弹任何提示（UI 提示由登录页自己处理）
  if (requiresAuth && !isLoggedIn) {
    return next({ name: 'login' })
  }

  // ── 场景D：正常放行 ───────────────────────────────────────────
  return next()
})

export default router
