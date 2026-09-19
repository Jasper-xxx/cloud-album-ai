import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import IconsResolver from 'unplugin-icons/resolver'
import Icons from 'unplugin-icons/vite'

export default defineConfig({

  // 前后端统一使用仓库根目录的 .env；Vite 默认只读取 frontend 目录。
  envDir: fileURLToPath(new URL('..', import.meta.url)),

  // 配置本地服务
  server: {
    host: '127.0.0.1',
    port: 8080, //端口号
    strictPort: true, // 与后端 CORS 来源一致；占用时明确报错，不自动切到 8081。
    open: false, //是否自动打开浏览器
    hmr: true,//热更新
    //反向代理
    proxy: {
      "/devApi": {
        target: "http://127.0.0.1:8088", //代理地址 目标后端服务地址
        changeOrigin: true, //开启跨域
        rewrite: (path) => path.replace(/^\/devApi/, ""), //重写路径，将/devApi替换为""
      },
    },
  },

  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver(),
      ],
    }),
    Components({
      resolvers: [ElementPlusResolver(),
      IconsResolver({
        // icon的前缀 组件使用{prefix}-{collection}-{icon} eg:i-ep-search
        prefix: 'i'
        // enabledCollections:['ep']  这是可选的，默认启用 Iconify 支持的所有集合，ep指的是element_ui的图标库
        // alias: { park: 'icon-park' } 集合的别名
      }),
      ],
    }),
    Icons({
      scale: 1, // 缩放比 相对1em
      autoInstall: true, // 自动安装
      compiler: 'vue3' // 编译方式
    })
  ],

  build: {
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return
          }
          if (id.includes('element-plus') || id.includes('@element-plus')) {
            return 'vendor-element-plus'
          }
          if (id.includes('echarts') || id.includes('zrender')) {
            return 'vendor-charts'
          }
          if (id.includes('html2canvas')) {
            return 'vendor-export'
          }
          if (id.includes('vue-simple-uploader') || id.includes('spark-md5')) {
            return 'vendor-upload'
          }
          if (id.includes('vue') || id.includes('vue-router') || id.includes('pinia')) {
            return 'vendor-vue'
          }
          return 'vendor'
        },
      },
    },
  },
  
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
})




