/**
 * 验证管理入口的登录会话：
 *   首次进入需输密码 → 前后台来回切换不再要求 → 刷新仍保持 → 退出登录后才重新要求。
 * 用法：node verify-admin-session.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const PROFILE = 'C:/Users/Ming_16/AppData/Local/Temp/edge-session'
const PORT = 9342
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let pass = 0
let fail = 0
const check = (name, ok, extra = '') => {
  if (ok) { pass += 1; console.log(`  ✅ ${name}`) } else { fail += 1; console.log(`  ❌ ${name} ${extra}`) }
}

fs.rmSync(PROFILE, { recursive: true, force: true })
const edge = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-first-run',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--window-size=1600,1000', 'about:blank',
], { stdio: 'ignore' })

let page = null
for (let i = 0; i < 60 && !page; i += 1) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
    page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
  } catch (e) { /* 等待 */ }
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
const evaluate = async (expression) => (await send('Runtime.evaluate', {
  expression, returnByValue: true, awaitPromise: true,
})).result?.result?.value

/** 按可见文案点击元素。 */
const clickText = (selector, text) => evaluate(
  `[...document.querySelectorAll(${JSON.stringify(selector)})]
     .find((b) => b.textContent.includes(${JSON.stringify(text)})).click()`,
)
const goto = async (path) => {
  await send('Page.navigate', { url: BASE + path })
  await sleep(2500)
}
const path = () => evaluate('location.pathname')
const passwordModalShown = () => evaluate('!!document.querySelector(".admin-login")')
const workbenchShown = () => evaluate('!!document.querySelector(".admin-layout, .admin-topbar")')

await send('Page.enable')
await send('Runtime.enable')
await goto('/')
// 从零开始：清掉可能残留的令牌
await evaluate('localStorage.clear()')
await goto('/')

console.log('\n[登录会话] 首次进入：必须验证密码')
await clickText('.oa-menu-item', '管理入口')
await sleep(800)
check('首次点"管理入口"弹出密码框', await passwordModalShown())

await evaluate(`(() => {
  const el = document.querySelector('.admin-login input')
  const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
  set.call(el, 'admin123')
  el.dispatchEvent(new Event('input', { bubbles: true }))
})()`)
await clickText('.admin-login-actions .btn-primary', '进入后台')
await sleep(2500)
check('验证通过后进入后台工作台', (await path()) === '/admin' && await workbenchShown())
check('令牌已写入 localStorage',
  await evaluate('!!localStorage.getItem("jqgg53_admin_token")'))

console.log('\n[登录会话] 前后台来回切换：不再要求密码')
await clickText('.oa-menu-item', '前台展示')
await sleep(2000)
check('可从后台回到前台首页', (await path()) === '/')

await clickText('.oa-menu-item', '管理入口')
await sleep(2500)
check('再点"管理入口"不再弹密码框', !(await passwordModalShown()))
check('直接进入后台工作台', (await path()) === '/admin' && await workbenchShown())

// 再来一轮，确认可以反复跳转
await clickText('.oa-menu-item', '前台展示')
await sleep(2000)
await clickText('.oa-menu-item', '管理入口')
await sleep(2500)
check('多轮来回切换均不再要求密码', (await path()) === '/admin' && !(await passwordModalShown()))

console.log('\n[登录会话] 刷新页面：登录状态保持')
await goto('/admin')
check('直接刷新 /admin 仍处于登录态', await workbenchShown())
await goto('/')
await clickText('.oa-menu-item', '管理入口')
await sleep(2500)
check('前台刷新后点"管理入口"仍免密进入', (await path()) === '/admin' && await workbenchShown())

console.log('\n[登录会话] 退出登录：恢复为需要密码')
await clickText('.admin-topbar-actions .btn-ghost', '退出登录')
await sleep(1500)
check('退出后回到登录页', await evaluate('!!document.querySelector(".login-view")'))
check('令牌已清除', !(await evaluate('!!localStorage.getItem("jqgg53_admin_token")')))

await goto('/')
await clickText('.oa-menu-item', '管理入口')
await sleep(1000)
check('退出后再次点"管理入口"重新要求密码', await passwordModalShown())

// 令牌存在但服务端已失效（后端重启后令牌表是空的）——应自动退回登录页而不是卡住
console.log('\n[登录会话] 本地令牌失效：自动退回登录页')
await evaluate('localStorage.setItem("jqgg53_admin_token", "stale-token-for-test")')
await goto('/')
await clickText('.oa-menu-item', '管理入口')
await sleep(3000)
check('令牌失效时自动退回登录页', await evaluate('!!document.querySelector(".login-view")'))
check('失效令牌已被清除', !(await evaluate('!!localStorage.getItem("jqgg53_admin_token")')))

ws.close()
edge.kill()
for (let i = 0; i < 10; i += 1) {
  try { fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 3 }); break } catch (e) { await sleep(500) }
}

console.log(`\n=== 结果：通过 ${pass} 项，失败 ${fail} 项 ===\n`)
process.exit(fail === 0 ? 0 : 1)
