# 手机端前端（frontend-mobile）

独立 Vue 3 + Vite 工程，生产环境服务在 `/m/`，后台管理为 `/m/admin`。

- 按手机 App 逻辑设计：底部固定 dock、筛选底部弹层、全屏地图、全屏对话、后台抽屉菜单；
- 业务接口统一请求 `/api`，图片统一请求 `/uploads`、`/img`；
- 与电脑端 `frontend-pc` 共用同一个 Spring Boot 后端和 MySQL 数据库；
- 后台管理点击底部“管理”后直接进入 `/m/admin` 页面内登录，不再弹窗。

## 开发

```bash
cd frontend-mobile
npm install
npm run dev
```

打开 `http://localhost:5174/m/`。开发服务器默认代理 `/api`、`/uploads` 到
`http://localhost:8080`。

手机真机预览：

```bash
npm run dev:mobile
```

手机和电脑连同一 Wi-Fi，打开终端显示的 `http://电脑IP:5174/m/`。
如 Windows 防火墙拦截，请允许 Node.js 的入站访问。

## 构建

```bash
npm run build
```

输出到项目根目录 `.build/mobile`，Vite base 为 `/m/`。部署时由
`deploy/build-local.sh` 复制到 `src/main/resources/static/m/`。

## 设备分流与后台

后端根据 User-Agent / `Sec-CH-UA-Mobile` 把手机请求 `/`、`/admin`
重定向到 `/m/`、`/m/admin`。两套前端通过同一个 API 完成登录、广告牌 CRUD、
知识库问答和地图数据读取，数据完全同步。
