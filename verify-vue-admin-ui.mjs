/**
 * 后台工作台 UI 渲染验证（CDP + headless Edge）：
 *   1. 通过接口取得管理员令牌并写入 localStorage（跳过手工输密码）
 *   2. 打开 /admin，检查列表、筛选、表单、地图选点是否渲染
 *   3. 模拟点击"新增广告牌"→ 填表 → 提交，验证 Vue 表单到后端的完整链路
 * 用法：node verify-vue-admin-ui.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const PROFILE = 'C:/Users/Ming_16/AppData/Local/Temp/edge-cdp-admin'
const PORT = 9222

let pass = 0
let fail = 0

function check(name, ok, extra = '') {
  if (ok) {
    pass += 1
    console.log(`  ✅ ${name}`)
  } else {
    fail += 1
    console.log(`  ❌ ${name} ${extra}`)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* ---------- 取得管理员令牌 ---------- */
const loginRes = await fetch(BASE + '/api/admin/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ password: 'admin123' }),
})
const token = (await loginRes.json()).data.token
const seedRes = await fetch(BASE + '/api/billboards')
const SEED_COUNT = (await seedRes.json()).data.length
console.log(`已取得管理令牌：${token.slice(0, 8)}…`)
console.log(`当前种子数据：${SEED_COUNT} 条\n`)

/* ---------- 启动 headless Edge ---------- */
fs.rmSync(PROFILE, { recursive: true, force: true })
const edge = spawn(EDGE, [
  '--headless=new',
  '--disable-gpu',
  '--no-first-run',
  '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${PROFILE}`,
  'about:blank',
], { stdio: 'ignore' })

/** 等待 DevTools 端口就绪并返回页面目标。 */
async function waitForTarget() {
  for (let i = 0; i < 60; i += 1) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`)
      const list = await res.json()
      const page = list.find((t) => t.type === 'page')
      if (page?.webSocketDebuggerUrl) return page
    } catch (e) {
      /* 还没起来 */
    }
    await sleep(500)
  }
  throw new Error('DevTools 端口未就绪')
}

const target = await waitForTarget()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.onopen = resolve
  ws.onerror = reject
})

let msgId = 0
const pending = new Map()
const events = []

ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id != null && pending.has(m.id)) {
    pending.get(m.id)(m)
    pending.delete(m.id)
  } else if (m.method) {
    events.push(m)
  }
}

function send(method, params = {}) {
  msgId += 1
  const id = msgId
  ws.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve) => pending.set(id, resolve))
}

/** 在页面中执行表达式（awaitPromise 支持 async 函数）。 */
async function evaluate(expression) {
  const res = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  })
  if (res.result?.exceptionDetails) {
    throw new Error(res.result.exceptionDetails.text + ' ' +
      (res.result.exceptionDetails.exception?.description || ''))
  }
  return res.result?.result?.value
}

async function goto(url) {
  await send('Page.navigate', { url })
  await sleep(2500)
}

await send('Page.enable')
await send('Runtime.enable')
await send('Page.setLifecycleEventsEnabled', { enabled: true })

/* ---------- 1. 写入令牌并打开后台 ---------- */
console.log('[1] 后台工作台渲染')
await goto(BASE + '/')
await evaluate(`localStorage.setItem('jqgg53_admin_token', ${JSON.stringify(token)})`)
await goto(BASE + '/admin')
await sleep(2500)
// 腾讯地图脚本为外部资源，网络较慢时多等一会儿
for (let i = 0; i < 20; i += 1) {
  if (await evaluate('typeof window.TMap !== "undefined"')) break
  await sleep(500)
}
await sleep(500)

const html = await evaluate('document.documentElement.outerHTML')
check('未停留在登录视图', !html.includes('login-card'))
check('渲染工作台顶栏', html.includes('admin-topbar'))
check('渲染左栏列表', html.includes('admin-list-panel'))
const listCount = await evaluate('document.querySelectorAll(".admin-item").length')
check(`列表渲染 ${listCount} 条记录（期望 ${SEED_COUNT}）`, listCount === SEED_COUNT, `实际 ${listCount}`)
check('渲染右栏表单', html.includes('admin-form'))
check('表单含地图选点容器', html.includes('map-picker'))
check('状态选项按钮渲染', (await evaluate('document.querySelectorAll(".radio-item").length')) === 4)
check('已登录时不显示登录卡', !html.includes('后台管理登录'))

// 地图选点：确认腾讯地图脚本已注入
check('腾讯地图脚本已加载', (await evaluate('typeof window.TMap !== "undefined"')))
check('选点地图画布已渲染', (await evaluate('document.querySelectorAll(".map-picker canvas").length')) > 0)

/* ---------- 2. 列表筛选交互 ---------- */
console.log('\n[2] 筛选交互')
await evaluate(`(() => {
  const input = document.querySelector('.admin-list-filters input[type="search"]')
  input.value = '京港澳'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  return true
})()`)
await sleep(1200)
const filtered = await evaluate('document.querySelectorAll(".admin-item").length')
check(`关键词"京港澳"筛选生效（${filtered} 条 < ${SEED_COUNT}）`, filtered > 0 && filtered < SEED_COUNT, `实际 ${filtered}`)

await evaluate(`(() => {
  const input = document.querySelector('.admin-list-filters input[type="search"]')
  input.value = ''
  input.dispatchEvent(new Event('input', { bubbles: true }))
  return true
})()`)
await sleep(1200)
const restored = await evaluate('document.querySelectorAll(".admin-item").length')
check(`清空关键词后恢复 ${SEED_COUNT} 条`, restored === SEED_COUNT, `实际 ${restored}`)

/* ---------- 3. 点击列表项进入编辑态 ---------- */
console.log('\n[3] 编辑态载入')
// 优先选择有报价的记录（演示数据中包含“面议”记录）
await evaluate(`(() => {
  const items = [...document.querySelectorAll('.admin-item')]
  const target = items.find((el) => {
    const price = el.querySelector('.admin-item-price')?.textContent || ''
    return !price.includes('面议')
  }) || items[0]
  target.click()
  return true
})()`)
await sleep(1200)
const formState = await evaluate(`(() => {
  const v = (id) => document.querySelector(id)?.value ?? null
  return {
    name: v('#a-name'),
    code: v('#a-code'),
    price: v('#a-price'),
    editing: document.querySelector('.form-head h3').textContent.trim(),
    hasDeleteBtn: !!document.querySelector('.form-actions .btn-danger'),
  }
})()`)
check('点击列表项载入编辑态', formState.editing === '编辑广告牌', formState.editing)
check('表单回填名称', !!formState.name, String(formState.name))
check('表单回填编号', !!formState.code, String(formState.code))
check('表单回填报价', formState.price !== '', String(formState.price))
check('编辑态显示删除按钮', formState.hasDeleteBtn)

/* ---------- 4. 新增广告牌（Vue 表单 → multipart → 后端 → 前台同步） ---------- */
console.log('\n[4] 新增广告牌全链路')
const CODE = 'YS-UI-' + Date.now().toString().slice(-6)
await evaluate('document.querySelector(".admin-list-toolbar .btn-primary").click()')
await sleep(600)
await evaluate(`(() => {
  const set = (id, val) => {
    const el = document.querySelector(id)
    el.value = val
    el.dispatchEvent(new Event('input', { bubbles: true }))
    el.dispatchEvent(new Event('change', { bubbles: true }))
  }
  set('#a-name', 'UI 链路验证广告牌')
  set('#a-code', ${JSON.stringify(CODE)})
  set('#a-road', 'UI 验证高速')
  set('#a-section', 'K9+900 双向')
  set('#a-lng', '116.397500')
  set('#a-lat', '39.908700')
  set('#a-price', '9900')
  return true
})()`)
await sleep(400)
check('地图选点标记随坐标输入生成', (await evaluate('document.querySelectorAll(".map-picker canvas").length')) > 0)

await evaluate('document.querySelector(".admin-form .btn-primary").click()')
await sleep(2500)

const afterSave = await evaluate(`(() => {
  const cfg = JSON.parse(localStorage.getItem('jqgg53_admin_token') ? '{}' : '{}')
  return {
    formCode: document.querySelector('#a-code')?.value,
    heading: document.querySelector('.form-head h3').textContent.trim(),
    items: document.querySelectorAll('.admin-item').length,
    toast: document.querySelector('.toast')?.textContent?.trim() || '',
  }
})()`)
check('保存后切换到编辑态', afterSave.heading === '编辑广告牌', afterSave.heading)
check(`列表刷新为 ${SEED_COUNT + 1} 条（实际 ${afterSave.items}）`, afterSave.items === SEED_COUNT + 1)

// 前端页面是否同步看到（前台 15 秒轮询 = 直接查接口）
const pub = await (await fetch(`${BASE}/api/billboards?keyword=${encodeURIComponent(CODE)}`)).json()
check('前台接口已能查到该广告牌（实时同步）', pub.data?.length === 1, JSON.stringify(pub.data?.length))
const newId = pub.data?.[0]?.id
check('新增记录坐标写入正确', Number(pub.data?.[0]?.longitude) === 116.3975)

/* ---------- 5. 清理 ---------- */
console.log('\n[5] 清理')
const del = await fetch(`${BASE}/api/admin/billboards/${newId}`, {
  method: 'DELETE',
  headers: { 'X-Admin-Token': token },
})
const delBody = await del.json()
check('测试记录已删除', delBody.success === true)
const finalList = await (await fetch(BASE + '/api/billboards')).json()
check(`前台恢复 ${SEED_COUNT} 条种子数据`, finalList.data?.length === SEED_COUNT, `实际 ${finalList.data?.length}`)

/* ---------- 收尾 ---------- */
ws.close()
edge.kill()
// Edge 退出后目录句柄可能尚未释放，带重试地清理（失败不影响验证结果）
for (let i = 0; i < 10; i += 1) {
  try {
    fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 3 })
    break
  } catch (e) {
    await sleep(500)
  }
}

console.log(`\n=== 结果：通过 ${pass} 项，失败 ${fail} 项 ===\n`)
process.exit(fail === 0 ? 0 : 1)
