import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址：开发模式下通过 Vite 代理转发（默认 8080，被占用时可用环境变量覆盖）
const BACKEND = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'

export default defineConfig({
  base: '/m/',
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      // 开发模式：API 与上传图片均代理到 Spring Boot 后端
      '/api': { target: BACKEND, changeOrigin: true },
      '/uploads': { target: BACKEND, changeOrigin: true },
      '/img': { target: BACKEND, changeOrigin: true },
    },
  },
  build: {
    // 手机端构建产物；build-local.sh 会复制到静态目录 /m/
    outDir: '../.build/mobile',
    emptyOutDir: true,
  },
})
