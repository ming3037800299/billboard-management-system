# 电脑端前端（frontend-pc）

独立 Vue 3 + Vite 工程，生产环境服务在根路径 `/`，后台管理为 `/admin`。

- 与手机端 `frontend-mobile` 完全分开构建；
- 业务接口统一请求 `/api`，图片统一请求 `/uploads`、`/img`；
- 与手机端共用同一个 Spring Boot 后端和 MySQL 数据库。

## 开发

```bash
cd frontend-pc
npm install
npm run dev
```

打开 `http://localhost:5173`。开发服务器默认把 `/api`、`/uploads` 代理到
`http://localhost:8080`。也可以使用 `npm run dev:remote` 直连线上后端做界面联调。

## 构建

```bash
npm run build
```

输出到项目根目录 `.build/pc`。部署时由 `deploy/build-local.sh` 统一复制到
`src/main/resources/static/`，不需要手动拷贝。

## 设备分流

后端 `MobileDeviceRedirectFilter` 根据 User-Agent / `Sec-CH-UA-Mobile`
把手机访问 `/` 和 `/admin` 自动转到手机端 `/m/`、`/m/admin`；电脑端访问
`/`、`/admin` 始终使用本工程。
