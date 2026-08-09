# Frontend Guidelines

本目录是当前 Vue 单页应用，不是旧单体遗留前端。修改时同时遵守仓库根 `AGENTS.md`。

## 目录与编码

- Vue 组件使用 PascalCase；普通 JavaScript 模块使用小写短名称，并保持现有 Composition API 风格。
- 全局状态放在 `src/stores/`，复用逻辑放在 `src/composables/` 或 `src/utils/`，避免在页面间复制业务逻辑。
- 页面按角色维护在 `src/views/admin`、`src/views/auth`、`src/views/student`、`src/views/teacher`。
- 保持 Element Plus、ECharts 和现有全局样式的一致性；新增交互应覆盖加载、空数据、错误和禁用状态。

## 接口与状态边界

- API 请求集中在 `src/api/http.js` 和 `src/api/index.js`；不要在视图或组件中新增独立 Axios 实例或分散配置。
- 路由、认证状态和统一错误处理优先复用现有 `router`、`stores` 与 `api` 边界，不创建重复的全局状态源。
- 前端只通过网关公开接口访问后端，不绕过网关绑定具体服务地址。

## 验证

- 前端改动至少在本目录运行 `npm run build`。
- 当前未配置 lint 或自动化测试，不得声称执行了不存在的验证。
- 对受影响角色和关键流程执行手工冒烟；无法运行时，在最终结果中说明原因和待验证场景。
