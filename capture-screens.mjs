/**
 * 截图脚本（CDP）：抓取前台首页与后台工作台的整页截图，便于人工核对视觉效果。
 * 用法：node capture-screens.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const PROFILE = 'C:/Users/Ming_16/AppData/Local/Temp/edge-shot'
const PORT = 9333
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// 取令牌，跳过手工输密码
const token = (await (await fetch(BASE + '/api/admin/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ password: 'admin123' }),
})).json()).data.token

fs.rmSync(PROFILE, { recursive: true, force: true })
const edge = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-first-run',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--window-size=1600,1200', 'about:blank',
], { stdio: 'ignore' })

let page = null
for (let i = 0; i < 60 && !page; i += 1) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
    page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
  } catch (e) { /* 等待启动 */ }
  if (!page) await sleep(500)
}

const ws = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })

let id = 0
const pending = new Map()
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id) }
}
const send = (method, params = {}) => {
  id += 1
  ws.send(JSON.stringify({ id, method, params }))
  return new Promise((r) => pending.set(id, r))
}

await send('Page.enable')
await send('Runtime.enable')

async function shot(url, file, { fullPage = true, after = null } = {}) {
  await send('Page.navigate', { url })
  await sleep(3500)
  if (after) { await send('Runtime.evaluate', { expression: after, awaitPromise: true }); await sleep(2000) }
  const res = await send('Page.captureScreenshot', {
    format: 'png',
    captureBeyondViewport: fullPage,
  })
  fs.writeFileSync(file, Buffer.from(res.result.data, 'base64'))
  console.log(`已保存 ${file}`)
}

await shot(BASE + '/', 'shot-home.png')
// 首页"地图定位"视图（顶部视图切换按钮的第二个）
await shot(BASE + '/', 'shot-home-map.png', {
  after: `[...document.querySelectorAll('.view-switch-btn')].find((b) => b.textContent.includes('地图定位')).click();`,
})
await shot(BASE + '/admin', 'shot-admin.png', {
  fullPage: false,
  after: `localStorage.setItem('jqgg53_admin_token', ${JSON.stringify(token)}); location.reload();`,
})
// 详情弹窗
await shot(BASE + '/', 'shot-detail.png', {
  fullPage: false,
  after: `document.querySelector('.bb-card-title').click();`,
})

ws.close()
edge.kill()
// Edge 退出后目录句柄可能尚未释放，带重试清理（失败不影响截图结果）
for (let i = 0; i < 10; i += 1) {
  try { fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 3 }); break } catch (e) { await sleep(500) }
}
