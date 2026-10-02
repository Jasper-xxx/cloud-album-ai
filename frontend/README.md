# Cloud-Album 前端

Vue 3 + TypeScript + Vite 前端，使用 Element Plus。包含相册、照片管理、回收站、任务中心，以及嵌入 Dify WebApp 的个人版助手。

本文于 2026-10-02 对照当前源码更新；开发环境与服务配置见[项目 README](../README.md)，Dify 配置见[个人版导入说明](../docs/dify-personal-import-2026-09-14.md)。

## 本地开发

建议使用 Node.js 22 与 npm，依赖以 `package-lock.json` 为准。在本目录执行：

```powershell
npm ci
npm run dev
```

默认地址为 `http://127.0.0.1:8080`。Vite 使用 `strictPort: true`，端口占用时会退出，不会自动切换到 8081。请先处理端口占用，再启动前端。

配置从**仓库根目录**的 `.env` 读取，不是 `frontend/.env`。修改环境变量后需要重新启动 Vite：

```dotenv
VITE_BACKEND_API=/devApi
VITE_DIFY_AGENT_URL=http://localhost/chat/应用标识
```

- 开发服务仅配置 `/devApi` 代理，去掉前缀后转发至 `http://127.0.0.1:8088`；后端由使用者单独启动。
- `VITE_BACKEND_API` 未设置时，开发模式使用 `/devApi`，构建模式使用 `/api`。根目录 `.env` 中的显式设置会覆盖这一默认值。
- 当前前端不读取 `VITE_AI_API`，Vite 也没有 `/mockApi` 代理；AI 能力通过后端调用，不要把 AI 服务密钥放到前端。
- 所有 `VITE_*` 变量都可能进入浏览器产物，只能填写公开地址等非敏感配置。`AGENT_SERVICE_KEY`、`AI_SERVICE_KEY` 和模型 API Key 均属于服务端配置。

登录请求由 Axios 封装处理，普通请求从本地认证状态读取 token 并放入 `Authorization`。如果改为直接跨域请求后端，前端来源必须与后端 CORS 配置一致；开发时优先使用上述代理。

## 小助手入口

右下角助手的设置按钮可以保存当前浏览器的 Dify 地址；浏览器保存值优先于 `VITE_DIFY_AGENT_URL`。请使用已发布应用的 `/chat/应用标识` 页面，组件会把 `/chatbot/应用标识` 规范化为 `/chat/应用标识`。

历史会话和输入框附件按钮由 Dify WebApp 提供。当前工作流允许一次一张图片，Dify 中须启用附件并发布对应工作流。收起助手会保留已经加载的 iframe；“操作记录与照片”页签是前端自有活动面板。详情及尚未完成的在线验证见[恢复与助手界面修复记录](../docs/agent-restore-ui-fix-2026-09-15.md)。

浏览器登录身份不会自动替换 Dify 工具的服务端账号绑定；当前是个人版单一主人配置，不应把同一已发布 Dify 应用作为多个用户各自图库的入口。

## 构建与检查命令

以下为维护者按需执行的命令，本次文档更新未运行这些检查：

| 命令 | 用途 |
|---|---|
| `npm run build` | 并行执行 TypeScript 类型检查和 Vite 构建，产物在 `dist/` |
| `npm run type-check` | 仅执行 `vue-tsc --build` |
| `npm run build-only` | 仅生成 Vite 构建产物 |
| `npm run preview` | 本地预览构建产物，不是生产部署服务 |
| `npm run test:unit` | Vitest 单元测试 |
| `npm run test:unit:watch` | Vitest 监听模式 |
| `npm run test:e2e` | Playwright Chromium 用例 |
| `npm run test:e2e:ci` | 显式选择 Chromium 的 Playwright 用例 |
| `npm run format:check` | 检查源码和根配置文件格式 |

Playwright 配置会启动 `127.0.0.1:4173` 的 Vite 开发服务；首次使用需要安装匹配的 Chromium 浏览器。生产部署时需为构建中使用的 API 前缀配置反向代理，并为 Vue Router 的页面路径提供 SPA 回退。Vite 开发代理不会随 `dist/` 一起部署。
