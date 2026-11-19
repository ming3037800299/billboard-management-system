/**
 * 数据库维护页面 UI 验证（CDP + headless Edge）：
 *   1. 通过接口取得管理员令牌并写入 localStorage
 *   2. 打开 /admin → 数据库维护
 *   3. 检查数据库选择栏、数据库概览、当前库检索、跨库检索、表结构浏览与任意表新增字段表单
 *   4. 检查 billboard 已有字段列表，通过页面修改系统字段注释并确认数据库回显（结束后恢复）
 *   5. 通过页面新增临时字段，确认广告牌编辑表单自动出现该字段（结束后自动 DROP COLUMN 清理）
 *   6. 截图输出到 target/db-fields-existing.png / db-maintenance.png / billboard-dynamic-field.png
 *
 * 用法：node verify-db-maintenance.mjs
 * 默认验证 http://localhost:8080，可用 BASE_URL 环境变量覆盖（如 http://localhost:8081）
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.BASE_URL || 'http://localhost:8080'
const EDGE = process.env.EDGE_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const PROFILE = 'C:/Users/Ming_16/AppData/Local/Temp/edge-cdp-db-maintenance'
const PORT = 9223
const PASSWORD = process.env.ADMIN_PASSWORD || 'admin123'

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
  body: JSON.stringify({ password: PASSWORD }),
})
const loginBody = await loginRes.json()
if (!loginBody.success) throw new Error('管理员登录失败：' + loginBody.message)
const token = loginBody.data.token
console.log(`已取得管理令牌：${token.slice(0, 8)}…`)

/** 调用后台接口（用于准备 / 清理 UI 测试数据）。 */
async function adminFetch(path, options = {}) {
  const res = await fetch(BASE + path, {
    ...options,
    headers: {
      'X-Admin-Token': token,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
  })
  return res.json()
}

/** 删除 UI 测试可能残留的扩展字段（会同步 DROP COLUMN）。 */
async function cleanupUiTestField() {
  try {
    const modules = await adminFetch('/api/admin/db/modules')
    const module = modules.data?.find((m) => m.code === 'billboard_fields')
    if (!module) return
    const fields = await adminFetch(`/api/admin/db/modules/${module.id}/fields`)
    const uiTestFields = new Set(['ext_ui_verify', 'ext_ui_designer'])
    for (const field of fields.data || []) {
      if (uiTestFields.has(field.fieldName)) {
        await adminFetch(`/api/admin/db/fields/${field.id}`, { method: 'DELETE' })
        console.log(`已清理 UI 测试字段 ${field.fieldName}`)
      }
    }
  } catch (e) {
    console.log('清理 UI 测试字段失败：' + e.message)
  }
}

await cleanupUiTestField()

/* ---------- 记录 billboard.name 原始注释，测试结束后恢复 ---------- */
let originalNameComment = null

async function fetchNameComment() {
  try {
    const result = await adminFetch('/api/admin/db/billboard-columns')
    const nameColumn = result.data?.find((c) => c.name === 'name')
    originalNameComment = nameColumn?.comment ?? ''
  } catch (e) {
    console.log('读取 billboard.name 注释失败：' + e.message)
    originalNameComment = ''
  }
}

async function restoreNameComment() {
  if (originalNameComment === null) return
  try {
    await adminFetch('/api/admin/db/billboard-columns/name', {
      method: 'PUT',
      body: JSON.stringify({ comment: originalNameComment }),
    })
    console.log('已恢复 billboard.name 数据库注释')
  } catch (e) {
    console.log('恢复 billboard.name 注释失败：' + e.message)
  }
}

await fetchNameComment()

/* ---------- 读取数据库列表，用于校验顶部选择器 ---------- */
const databaseList = await adminFetch('/api/admin/db/databases')
const defaultDb = databaseList.data?.defaultDatabase || ''
const databaseNames = (databaseList.data?.databases || []).map((db) => db.name)

/* ---------- 启动 headless Edge ---------- */
fs.rmSync(PROFILE, { recursive: true, force: true })
const edge = spawn(EDGE, [
  '--headless=new',
  '--disable-gpu',
  '--no-first-run',
  '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${PROFILE}`,
  '--window-size=1600,1100',
  'about:blank',
], { stdio: 'ignore' })

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
const exceptions = []

ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id != null && pending.has(m.id)) {
    pending.get(m.id)(m)
    pending.delete(m.id)
  } else if (m.method === 'Runtime.exceptionThrown') {
    exceptions.push(m.params?.exceptionDetails?.text || 'unknown exception')
  } else if (m.method === 'Page.javascriptDialogOpening') {
    // 自动化测试统一接受 confirm（字段类型变更、删除等二次确认）
    send('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
  }
}

function send(method, params = {}) {
  msgId += 1
  const id = msgId
  ws.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve) => pending.set(id, resolve))
}

async function evaluate(expression) {
  const res = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  })
  if (res.result?.exceptionDetails) {
    throw new Error(res.result.exceptionDetails.text + ' '
      + (res.result.exceptionDetails.exception?.description || ''))
  }
  return res.result?.result?.value
}

async function goto(url) {
  await send('Page.navigate', { url })
  await sleep(2000)
}

async function waitFor(expression, timeout = 10000) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    if (await evaluate(expression)) return true
    await sleep(300)
  }
  return false
}

const outDir = path.resolve('target')
fs.mkdirSync(outDir, { recursive: true })

/** 截图并保存到 target 目录。 */
async function capture(name) {
  await evaluate('window.scrollTo(0, 0)')
  await sleep(300)
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  const file = path.join(outDir, name)
  fs.writeFileSync(file, Buffer.from(shot.result.data, 'base64'))
  console.log(`截图已保存：${file}`)
}

/** 按文本点击左侧 dock 菜单。 */
async function clickMenu(text) {
  return evaluate(`(() => {
    const item = [...document.querySelectorAll('.oa-menu-item')]
      .find((el) => (el.textContent || '').replace(/\\s+/g, '').includes(${JSON.stringify(text)}))
    if (!item) return false
    item.click()
    return true
  })()`)
}

/** 按文本点击数据库维护子模块。 */
async function clickModule(text) {
  return evaluate(`(() => {
    const item = [...document.querySelectorAll('.db-module-item')]
      .find((el) => (el.textContent || '').replace(/\\s+/g, '').includes(${JSON.stringify(text)}))
    if (!item) return false
    item.click()
    return true
  })()`)
}

await send('Page.enable')
await send('Runtime.enable')

try {
  console.log('\n[1] 打开数据库维护页')
  await goto(BASE + '/')
  await evaluate(`localStorage.setItem('jqgg53_admin_token', ${JSON.stringify(token)})`)
  await goto(BASE + '/admin')
  const loggedIn = await waitFor('!!document.querySelector(".oa-menu")')
  check('后台工作台已渲染', loggedIn)
  check('左侧出现“数据库维护”入口', await clickMenu('数据库维护'))
  check('数据库维护面板已渲染', await waitFor('!!document.querySelector(".db-panel")'))
  await waitFor('document.querySelectorAll(".db-module-item").length >= 4', 15000)
  const moduleCount = await evaluate('document.querySelectorAll(".db-module-item").length')
  check(`子模块列表渲染 ${moduleCount} 个（>= 4）`, moduleCount >= 4, `实际 ${moduleCount}`)
  await waitFor('document.querySelectorAll(".db-overview-card").length === 3', 15000)
  const overviewCards = await evaluate('document.querySelectorAll(".db-overview-card").length')
  check('数据库概览卡片渲染', overviewCards === 3, `实际 ${overviewCards}`)
  const ddlText = await evaluate('document.querySelector(".db-overview")?.textContent || ""')
  check('展示 DDL 权限状态', /ALTER/.test(ddlText))
  check('数据库选择栏渲染', await waitFor('!!document.querySelector("#db-database-select")'))
  const options = await evaluate('[...document.querySelectorAll("#db-database-select option")].map((o) => o.value)')
  check(`数据库列表渲染 ${options.length} 个`, options.length >= 1)
  check(`默认选中项目库 ${defaultDb}`,
    (await evaluate('document.querySelector("#db-database-select")?.value')) === defaultDb,
    `实际 ${await evaluate('document.querySelector("#db-database-select")?.value')}`)
  check('数据库列表包含后端返回的库',
    databaseNames.every((name) => options.includes(name)), `期望 ${databaseNames.join(',')}`)

  console.log('\n[2] 字段检索子模块')
  check('检索输入框渲染', await waitFor('!!document.querySelector(".db-search-bar input")'))
  await evaluate(`(() => {
    const input = document.querySelector('.db-search-bar input')
    input.value = '报价'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await evaluate('document.querySelector(".db-search-bar .btn").click()')
  check('检索结果已返回', await waitFor('!!document.querySelector(".db-search-result")', 15000))
  const resultRows = await evaluate('document.querySelectorAll(".db-result-table tbody tr").length')
  check(`检索结果包含数据行（${resultRows} 行）`, resultRows > 0)
  const resultText = await evaluate('document.querySelector(".db-search-result")?.textContent || ""')
  check('检索结果含 billboard 表与 price_note 字段', resultText.includes('billboard') && resultText.includes('price_note'))
  check('检索结果显示所属数据库', resultText.includes(defaultDb))

  console.log('\n[2.1] 跨库检索')
  await evaluate(`(() => {
    const sel = document.querySelector('.db-scope select')
    sel.value = 'all'
    sel.dispatchEvent(new Event('change', { bubbles: true }))
    const input = document.querySelector('.db-search-bar input')
    input.value = 'name'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(300)
  await evaluate('document.querySelector(".db-search-bar .btn").click()')
  check('跨库检索结果已返回', await waitFor('!!document.querySelector(".db-search-result")', 15000))
  const crossText = await evaluate('document.querySelector(".db-search-result")?.textContent || ""')
  check('跨库结果包含数据库列', crossText.includes('数据库'))
  check('跨库结果包含可见数据库',
    databaseNames.some((name) => crossText.includes(name)),
    `数据库 ${databaseNames.join(',')}`)
  await evaluate(`(() => {
    const sel = document.querySelector('.db-scope select')
    sel.value = 'current'
    sel.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)

  console.log('\n[3] 数据表结构子模块')
  check('切换到“数据表结构”', await clickModule('数据表结构'))
  check('表结构列表已渲染', await waitFor('!!document.querySelector(".db-schema-table")', 10000))
  const tableNames = await evaluate('[...document.querySelectorAll(".db-schema-name")].map((e) => e.textContent).join(",")')
  check('表结构包含 billboard / billboard_field', tableNames.includes('billboard') && tableNames.includes('billboard_field'))
  await evaluate(`(() => {
    const head = [...document.querySelectorAll('.db-schema-head')]
      .find((el) => (el.textContent || '').includes('billboard'))
    if (!head) return false
    head.click()
    return true
  })()`)
  check('展开表后显示字段明细', await waitFor('!!document.querySelector(".db-column-table")'))
  const columnRows = await evaluate('document.querySelectorAll(".db-column-table tbody tr").length')
  check(`billboard 字段明细渲染（${columnRows} 行）`, columnRows >= 20)
  check('表结构行存在“新增字段”入口', await evaluate(`[...document.querySelectorAll('.db-schema-actions .link-btn')]
    .some((b) => (b.textContent || '').includes('新增字段'))`))
  const billboardGuard = await evaluate(`(() => {
    const row = [...document.querySelectorAll('.db-schema-table')]
      .find((r) => (r.querySelector('.db-schema-name')?.textContent || '').trim() === 'billboard')
    return (row?.textContent || '').includes('去广告牌字段子模块')
  })()`)
  check('billboard 行引导使用专用字段子模块', billboardGuard)

  const openedTableForm = await evaluate(`(() => {
    const row = [...document.querySelectorAll('.db-schema-table')]
      .find((r) => (r.querySelector('.db-schema-name')?.textContent || '').trim() === 'api_config')
    const btn = row && [...row.querySelectorAll('.db-schema-actions .link-btn')]
      .find((b) => (b.textContent || '').includes('新增字段'))
    if (!btn) return false
    btn.click()
    return true
  })()`)
  check('任意表新增字段表单打开',
    openedTableForm && await waitFor('!!document.querySelector("#db-table-column-name")'))
  const tableFormText = await evaluate('document.querySelector(".db-inline-form")?.textContent || ""')
  check('新增字段表单包含 ALTER TABLE 提示', tableFormText.includes('ADD COLUMN'))
  const tableTypeOptions = await evaluate('document.querySelectorAll("#db-table-column-type option").length')
  check(`字段类型选项渲染（${tableTypeOptions} 项）`, tableTypeOptions >= 8)
  await evaluate(`(() => {
    const close = document.querySelector('.db-inline-form .modal-close')
    if (close) close.click()
    return true
  })()`)

  console.log('\n[4] 广告牌字段子模块（已有字段）')
  check('切换到“广告牌字段”', await clickModule('广告牌字段'))
  check('字段面板渲染', await waitFor('!!document.querySelector(".db-field-table")'))
  await waitFor('document.querySelectorAll(".db-field-table tbody tr").length >= 20', 15000)
  const existingRows = await evaluate('document.querySelectorAll(".db-field-table tbody tr").length')
  check(`已列出 billboard 表已有字段（${existingRows} 行）`, existingRows >= 20)
  const fieldTableText = await evaluate('document.querySelector(".db-field-table")?.textContent || ""')
  check('已有系统字段显示（广告牌名称 / price_note）',
    fieldTableText.includes('广告牌名称') && fieldTableText.includes('price_note'))
  check('字段来源标记为“系统字段”', fieldTableText.includes('系统字段'))
  check('存在字段编辑入口', await evaluate(`[...document.querySelectorAll('.db-field-table button')]
    .some((b) => (b.textContent || '').trim() === '编辑')`))
  await capture('db-fields-existing.png')

  console.log('\n[4.1] 新增字段表单')
  const addBtnExists = await evaluate(`[...document.querySelectorAll('.db-toolbar .btn')]
    .some((b) => (b.textContent || '').includes('新增字段'))`)
  check('存在“新增字段”按钮', addBtnExists)
  await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.db-toolbar .btn')]
      .find((b) => (b.textContent || '').includes('新增字段'))
    if (!btn) return false
    btn.click()
    return true
  })()`)
  check('新增字段表单渲染', await waitFor('!!document.querySelector(".db-inline-form")'))
  const formText = await evaluate('document.querySelector(".db-inline-form")?.textContent || ""')
  check('表单包含 ALTER TABLE 同步提示', formText.includes('ALTER TABLE billboard ADD COLUMN'))
  const formFields = await evaluate('document.querySelectorAll(".db-inline-form .form-field").length')
  check(`表单字段渲染（${formFields} 项）`, formFields >= 6)
  await capture('db-maintenance.png')

  console.log('\n[4.2] 修改系统字段注释并同步数据库')
  const openedComment = await evaluate(`(() => {
    const row = [...document.querySelectorAll('.db-field-table tbody tr')]
      .find((r) => (r.textContent || '').includes('广告牌名称') && (r.textContent || '').includes('name'))
    if (!row) return false
    const btn = [...row.querySelectorAll('button')]
      .find((b) => (b.textContent || '').trim() === '编辑')
    if (!btn) return false
    btn.click()
    return true
  })()`)
  check('系统字段编辑抽屉打开',
    openedComment && await waitFor('!!document.querySelector("#db-column-comment")'))
  const initialComment = await evaluate('document.querySelector("#db-column-comment")?.value || ""')
  check('系统字段编辑抽屉回填当前数据库注释', initialComment === originalNameComment, `实际 ${initialComment}`)
  check('系统字段类型可编辑', !(await evaluate('!!document.querySelector("#db-system-type")?.disabled')))
  check('系统字段长度可编辑', !(await evaluate('!!document.querySelector("#db-system-length")?.disabled')))
  check('系统字段可空性可编辑', !(await evaluate('!!document.querySelector("#db-system-nullable")?.disabled')))
  check('系统字段抽屉展示数据概况', await waitFor('!!document.querySelector(".db-stats-card")', 10000))
  const commentSqlText = await evaluate('document.querySelector(".db-sql-preview")?.textContent || ""')
  check('系统字段抽屉展示 MODIFY COLUMN 预览', commentSqlText.includes('MODIFY COLUMN'))
  await evaluate(`(() => {
    const el = document.querySelector('#db-column-comment')
    el.value = 'UI测试数据库注释'
    el.dispatchEvent(new Event('input', { bubbles: true }))
    const form = document.querySelector('.db-inline-form')
    const btn = [...form.querySelectorAll('.db-inline-actions .btn')]
      .find((b) => (b.textContent || '').includes('保存并同步数据库'))
    if (!btn) return false
    btn.click()
    return true
  })()`)
  check('数据库注释更新并回显', await waitFor(
    `[...document.querySelectorAll('.db-field-table tbody tr')]
      .some((r) => (r.textContent || '').includes('UI测试数据库注释'))`,
    15000,
  ))

  console.log('\n[5] 通过页面新增字段并验证广告牌表单联动')
  await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.db-toolbar .btn')]
      .find((b) => (b.textContent || '').includes('新增字段'))
    if (!btn) return false
    btn.click()
    return true
  })()`)
  check('重新打开新增字段表单', await waitFor('!!document.querySelector("#db-field-label")'))
  await evaluate(`(() => {
    const setValue = (selector, value) => {
      const el = document.querySelector(selector)
      if (!el) return false
      el.value = value
      el.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    }
    setValue('#db-field-label', 'UI测试字段')
    setValue('#db-field-name', 'ext_ui_verify')
    setValue('#db-field-length', '100')
    const form = document.querySelector('.db-inline-form')
    const submit = [...form.querySelectorAll('.db-inline-actions .btn')]
      .find((b) => (b.textContent || '').includes('新增并同步数据库'))
    if (!submit) return false
    submit.click()
    return true
  })()`)
  const fieldCreated = await waitFor(
    `[...document.querySelectorAll('.db-field-table tbody tr')]
      .some((r) => (r.textContent || '').includes('UI测试字段'))`,
    20000,
  )
  check('新增字段已同步数据库并出现在字段列表', fieldCreated)

  console.log('\n[5.1] 字段编辑抽屉')
  const openedEdit = await evaluate(`(() => {
    const row = [...document.querySelectorAll('.db-field-table tbody tr')]
      .find((r) => (r.textContent || '').includes('UI测试字段'))
    const btn = row && [...row.querySelectorAll('button')]
      .find((b) => (b.textContent || '').trim() === '编辑')
    if (!btn) return false
    btn.click()
    return true
  })()`)
  check('字段编辑抽屉打开', openedEdit && await waitFor('!!document.querySelector(".db-drawer-form")'))
  check('字段名可编辑（非只读）', !(await evaluate('!!document.querySelector("#db-field-name")?.disabled')))
  check('字段类型可编辑（非只读）', !(await evaluate('!!document.querySelector("#db-field-type")?.disabled')))
  check('字段可空性控件渲染', await evaluate('!!document.querySelector("#db-field-nullable")'))
  check('字段默认允许数据库 NULL',
    await evaluate('document.querySelector("#db-field-nullable")?.checked === true'))
  check('字段抽屉展示数据概况', await waitFor('!!document.querySelector(".db-stats-card")', 10000))
  const fieldSqlText = await evaluate('document.querySelector(".db-sql-preview")?.textContent || ""')
  check('字段抽屉展示 CHANGE COLUMN 预览', fieldSqlText.includes('CHANGE COLUMN'))
  const drawerText = await evaluate('document.querySelector(".db-drawer-form")?.textContent || ""')
  check('抽屉包含结构修改预警', drawerText.includes('CHANGE COLUMN'))
  await evaluate(`(() => {
    const el = document.querySelector('#db-field-label')
    el.value = 'UI测试字段-已编辑'
    el.dispatchEvent(new Event('input', { bubbles: true }))
    const btn = [...document.querySelectorAll('.db-drawer-form .db-inline-actions .btn')]
      .find((b) => (b.textContent || '').includes('保存修改'))
    if (btn) btn.click()
    return true
  })()`)
  check('编辑保存后字段列表更新', await waitFor(
    `[...document.querySelectorAll('.db-field-table tbody tr')]
      .some((r) => (r.textContent || '').includes('UI测试字段-已编辑'))`,
    15000,
  ))

  console.log('\n[5.2] 字段管理：启用 / 停用')
  check('切换到“字段管理”', await clickModule('字段管理'))
  check('字段管理表格渲染', await waitFor('!!document.querySelector(".db-field-table")'))
  const managerRow = `[...document.querySelectorAll('.db-field-table tbody tr')]
    .find((r) => (r.textContent || '').includes('UI测试字段-已编辑'))`
  check('字段管理显示新增字段', await evaluate(`!!${managerRow}`))
  check('字段管理包含表单分区 / 栏宽控件',
    await evaluate(`(() => { const r = ${managerRow}; return !!r && r.querySelectorAll('select').length >= 2 })()`))
  await evaluate(`(() => { const r = ${managerRow}; const box = r?.querySelector('input[type="checkbox"]'); if (!box) return false; box.click(); return true })()`)
  check('停用后字段列表显示停用', await waitFor(
    `[...document.querySelectorAll('.db-field-table tbody tr')]
      .some((r) => (r.textContent || '').includes('UI测试字段-已编辑') && (r.textContent || '').includes('停用'))`,
    15000,
  ))
  const publicDisabled = await adminFetch('/api/billboards/custom-fields')
  check('停用后前台字段立即隐藏',
    !(publicDisabled.data || []).some((f) => f.fieldName === 'ext_ui_verify'))
  await evaluate(`(() => { const r = ${managerRow}; const box = r?.querySelector('input[type="checkbox"]'); if (!box) return false; box.click(); return true })()`)
  check('重新启用成功', await waitFor(
    `[...document.querySelectorAll('.db-field-table tbody tr')]
      .some((r) => (r.textContent || '').includes('UI测试字段-已编辑') && (r.textContent || '').includes('启用'))`,
    15000,
  ))
  const publicEnabled = await adminFetch('/api/billboards/custom-fields')
  check('重新启用后前台字段恢复',
    (publicEnabled.data || []).some((f) => f.fieldName === 'ext_ui_verify'))

  console.log('\n[5.3] 表单设计：编辑模式 / 字段配置 / 拖拽 / 数据库联动')
  check('切换到“表单设计”', await clickModule('表单设计'))
  check('表单设计器默认进入编辑模式', await waitFor(
    '!!document.querySelector(".db-fdesigner-workspace:not(.mode-preview)")',
  ))
  check('字段库渲染', await waitFor('!!document.querySelector(".db-fdesigner-library")'))
  const sectionCount = await evaluate('document.querySelectorAll(".db-designer-card").length')
  check(`表单分区渲染 ${sectionCount} 个`, sectionCount === 5, `实际 ${sectionCount}`)
  check('设计器画布显示新增字段', await evaluate(
    `!!document.querySelector('[data-field="ext_ui_verify"]')`,
  ))
  check('实时预览显示新增字段', await evaluate(
    `!!document.querySelector('[data-preview-field="ext_ui_verify"]')`,
  ))
  await capture('form-designer.png')

  await evaluate(`(() => {
    const box = document.querySelector('[data-field="ext_ui_verify"] input[data-role="required"]')
    if (!box) return false
    box.click()
    return true
  })()`)
  check('设置必填后预览出现星号', await waitFor(
    `!!document.querySelector('[data-preview-field="ext_ui_verify"] label em')`,
  ))

  await evaluate(`(() => {
    const type = document.querySelector('[data-field="ext_ui_verify"] select[data-role="type"]')
    if (!type) return false
    type.value = 'SELECT'
    type.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)
  check('切换到下拉类型后打开选项编辑器', await waitFor(
    '!!document.querySelector("#db-designer-options-modal")',
  ))
  await evaluate(`(() => {
    const input = document.querySelector('#db-designer-options-modal .db-fdesigner-option-input')
    if (!input) return false
    input.value = '单立柱'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await evaluate('document.querySelector("#db-option-add")?.click()')
  check('新增下拉选项输入框', await waitFor(
    'document.querySelectorAll("#db-designer-options-modal .db-fdesigner-option-input").length === 2',
  ))
  await evaluate(`(() => {
    const inputs = [...document.querySelectorAll('#db-designer-options-modal .db-fdesigner-option-input')]
    if (inputs.length < 2) return false
    inputs[1].value = '高炮'
    inputs[1].dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await evaluate('document.querySelector("#db-option-save")?.click()')
  check('选项编辑器关闭', await waitFor('!document.querySelector("#db-designer-options-modal")'))
  check('字段显示两个下拉选项', await waitFor(
    `(() => {
      const btn = [...document.querySelectorAll('[data-field="ext_ui_verify"] button')]
        .find((b) => (b.textContent || '').includes('选项'))
      return !!btn && (btn.textContent || '').includes('2')
    })()`,
  ))

  await evaluate(`(() => {
    const section = document.querySelector('[data-field="ext_ui_verify"] select[data-role="section"]')
    if (!section) return false
    section.value = 'basic'
    section.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)
  check('字段移动到基本信息分区', await waitFor(
    `!!document.querySelector('[data-section="basic"] [data-field="ext_ui_verify"]')`,
  ))
  await evaluate(`(() => {
    const width = document.querySelector('[data-field="ext_ui_verify"] select[data-role="width"]')
    if (!width) return false
    width.value = 'full'
    width.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)
  check('字段栏宽更新为整行', await waitFor(
    `document.querySelector('[data-field="ext_ui_verify"] select[data-role="width"]')?.value === 'full'`,
  ))
  check('预览按设计显示必填和下拉选项', await waitFor(
    `(() => {
      const box = document.querySelector('[data-preview-field="ext_ui_verify"]')
      if (!box) return false
      const text = box.textContent || ''
      return text.includes('*') && text.includes('单立柱') && text.includes('高炮')
    })()`,
  ))

  await evaluate('document.querySelector("#db-designer-save")?.click()')
  check('设计保存完成', await waitFor(
    `!document.querySelector('.db-fdesigner-dirty') && !!document.querySelector('#db-designer-save')`,
    20000,
  ))
  let savedField = null
  for (let i = 0; i < 40; i += 1) {
    const result = await adminFetch('/api/admin/db/billboard-columns')
    savedField = (result.data || []).find((c) => c.name === 'ext_ui_verify')
    if (savedField?.section === 'basic'
        && savedField.width === 'full'
        && savedField.required === true
        && savedField.inputType === 'SELECT'
        && (savedField.options || []).length === 2) break
    await sleep(300)
  }
  check('后端字段分区 / 栏宽 / 必填 / 类型 / 选项已落库',
    savedField?.section === 'basic'
      && savedField.width === 'full'
      && savedField.required === true
      && savedField.inputType === 'SELECT'
      && (savedField.options || []).join(',') === '单立柱,高炮',
    JSON.stringify(savedField ? {
      section: savedField.section,
      width: savedField.width,
      required: savedField.required,
      inputType: savedField.inputType,
      options: savedField.options,
    } : null))

  await evaluate('document.querySelector("#db-designer-preview-mode")?.click()')
  check('预览模式隐藏编辑区', await waitFor(
    `!!document.querySelector('.db-fdesigner-workspace.mode-preview')
      && !document.querySelector('.db-fdesigner-library')`,
  ))
  check('预览模式保留完整下拉选项', await waitFor(
    `(document.querySelector('.db-designer-preview')?.textContent || '').includes('单立柱')
      && (document.querySelector('.db-designer-preview')?.textContent || '').includes('高炮')`,
  ))
  await evaluate('document.querySelector("#db-designer-edit-mode")?.click()')
  check('返回编辑设计模式', await waitFor('!!document.querySelector(".db-fdesigner-library")'))

  await evaluate('document.querySelector("#db-designer-new-field")?.click()')
  check('设计器内新增字段弹窗打开', await waitFor(
    '!!document.querySelector("#db-designer-new-field-modal")',
  ))
  await evaluate(`(() => {
    const setValue = (selector, value) => {
      const el = document.querySelector(selector)
      if (!el) return false
      el.value = value
      el.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    }
    setValue('#db-new-field-label', 'UI设计器字段')
    setValue('#db-new-field-name', 'ext_ui_designer')
    const type = document.querySelector('#db-new-field-type')
    if (!type) return false
    type.value = 'BOOLEAN'
    type.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)
  await evaluate('document.querySelector("#db-designer-create-field")?.click()')
  check('设计器内新增字段弹窗关闭', await waitFor(
    '!document.querySelector("#db-designer-new-field-modal")',
    20000,
  ))
  let designerCreated = null
  for (let i = 0; i < 40; i += 1) {
    const result = await adminFetch('/api/admin/db/billboard-columns')
    designerCreated = (result.data || []).find((c) => c.name === 'ext_ui_designer')
    if (designerCreated) break
    await sleep(300)
  }
  check('设计器新增字段同步数据库', !!designerCreated,
    designerCreated ? '' : '未在 billboard 表找到 ext_ui_designer')
  check('新增字段出现在设计器画布', await waitFor(
    `!!document.querySelector('[data-field="ext_ui_designer"]')`,
    15000,
  ))

  const dragged = await evaluate(`(() => {
    const source = document.querySelector('[data-field="ext_ui_designer"] .db-fdesigner-drag')
    const target = document.querySelector('[data-section="basic"]')
    if (!source || !target) return false
    const dataTransfer = new DataTransfer()
    source.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer }))
    target.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer }))
    target.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer }))
    source.dispatchEvent(new DragEvent('dragend', { bubbles: true, cancelable: true, dataTransfer }))
    return true
  })()`)
  check('拖拽字段到基本信息分区', dragged && await waitFor(
    `!!document.querySelector('[data-section="basic"] [data-field="ext_ui_designer"]')`,
    5000,
  ))
  await evaluate('document.querySelector("#db-designer-save")?.click()')
  check('拖拽布局保存完成', await waitFor(
    `!document.querySelector('.db-fdesigner-dirty')`,
    20000,
  ))
  let draggedField = null
  for (let i = 0; i < 40; i += 1) {
    const result = await adminFetch('/api/admin/db/billboard-columns')
    draggedField = (result.data || []).find((c) => c.name === 'ext_ui_designer')
    if (draggedField?.section === 'basic') break
    await sleep(300)
  }
  check('拖拽后的分区已写入数据库', draggedField?.section === 'basic',
    `实际 ${draggedField?.section}`)

  check('切换到“广告牌管理”', await clickMenu('广告牌管理'))
  check('广告牌列表渲染', await waitFor('!!document.querySelector(".admin-item")', 10000))
  await evaluate('document.querySelector(".admin-item").click()')
  check('广告牌编辑表单自动出现新字段', await waitFor('!!document.querySelector("#cf-ext_ui_verify")', 10000))
  const newFieldLabel = await evaluate(`(() => {
    const input = document.querySelector('#cf-ext_ui_verify')
    return input?.closest('.form-field')?.querySelector('label')?.textContent || ''
  })()`)
  check('新字段显示名称为“UI测试字段”', newFieldLabel.includes('UI测试字段'), `实际 ${newFieldLabel}`)
  const fieldPlacement = await evaluate(`(() => {
    const input = document.querySelector('#cf-ext_ui_verify')
    const fieldset = input?.closest('fieldset')
    return {
      legend: fieldset?.querySelector('legend')?.textContent || '',
      full: !!input?.closest('.form-field')?.classList.contains('span-2'),
    }
  })()`)
  check('新字段渲染在基本信息分区', fieldPlacement.legend.includes('基本信息'), `实际 ${fieldPlacement.legend}`)
  check('新字段使用整行栏宽', await waitFor(
    `!!document.querySelector('#cf-ext_ui_verify')?.closest('.form-field')?.classList.contains('span-2')`,
    15000,
  ))
  const adminControl = await evaluate(`(() => {
    const el = document.querySelector('#cf-ext_ui_verify')
    return {
      tag: el?.tagName || '',
      options: [...(el?.options || [])].map((o) => o.value).filter(Boolean),
    }
  })()`)
  check('后台表单按设计渲染下拉控件',
    adminControl.tag === 'SELECT'
      && adminControl.options.includes('单立柱')
      && adminControl.options.includes('高炮'),
    JSON.stringify(adminControl))
  check('后台表单下拉字段可操作', await evaluate(`(() => {
    const el = document.querySelector('#cf-ext_ui_verify')
    if (!el || el.disabled || el.tagName !== 'SELECT') return false
    el.value = '单立柱'
    el.dispatchEvent(new Event('change', { bubbles: true }))
    return el.value === '单立柱'
  })()`))
  check('设计器新增的布尔字段出现在后台表单', await waitFor(
    '!!document.querySelector("#cf-ext_ui_designer")',
    10000,
  ))
  const designerAdminControl = await evaluate(`(() => {
    const el = document.querySelector('#cf-ext_ui_designer')
    return {
      tag: el?.tagName || '',
      options: [...(el?.options || [])].map((o) => o.textContent || ''),
    }
  })()`)
  check('设计器新增字段按布尔控件渲染',
    designerAdminControl.tag === 'SELECT'
      && designerAdminControl.options.some((text) => text.includes('是'))
      && designerAdminControl.options.some((text) => text.includes('否')),
    JSON.stringify(designerAdminControl))

  // 截图：广告牌编辑表单中的动态字段
  await capture('billboard-dynamic-field.png')

  check('页面无未捕获脚本异常', exceptions.length === 0, exceptions.slice(0, 3).join('; '))
} finally {
  await restoreNameComment()
  await cleanupUiTestField()
  ws.close()
  edge.kill()
}

console.log(`\n结果：${pass} 项通过，${fail} 项失败`)
if (fail > 0) process.exitCode = 1
