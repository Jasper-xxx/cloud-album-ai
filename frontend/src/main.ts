import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import uploader from 'vue-simple-uploader';
import 'vue-simple-uploader/dist/style.css';
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 全局移动端适配样式（viewport 修正 / rem 基准 / Element Plus 组件移动端修正）
import '@/styles/mobile.css'
import '@/styles/desktop-theme.css'
const app = createApp(App)
app.use(router)
app.use(uploader)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
