/*
 * 本地前端直连线上后端做联调：
 *   npm run dev:remote   仅本机访问 http://localhost:5173
 *   npm run dev:mobile   监听 0.0.0.0，手机同一局域网访问 http://电脑IP:5173
 *
 * 默认代理到 http://47.109.85.179:8080，可用 VITE_PROXY_TARGET 覆盖。
 * 注意：这是连接生产 API，调试 CRUD 时不要误删/误改线上数据。
 */
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const frontendDir = dirname(dirname(fileURLToPath(import.meta.url)));
const target = process.env.VITE_PROXY_TARGET || "http://47.109.85.179:8080";
const viteBin = join(frontendDir, "node_modules", "vite", "bin", "vite.js");

const child = spawn(process.execPath, [viteBin, ...process.argv.slice(2)], {
  cwd: frontendDir,
  stdio: "inherit",
  env: { ...process.env, VITE_PROXY_TARGET: target },
});

child.on("exit", (code) => process.exit(code ?? 0));
