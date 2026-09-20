/**
 * 验证"点击图钉 → 弹出信息窗 → 点'查看完整详情'→ 打开详情弹窗"的前台联动。
 * 用法：node verify-infowindow.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const PROFILE = 'C:/Users/Ming_16/AppData/Local/Temp/edge-info'
const PORT = 9337
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

async function click(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none', clickCount: 0 })
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
  await sleep(900)
}

await send('Page.enable')
await send('Runtime.enable')
await send('Page.navigate', { url: 'http://localhost:8080/' })
await sleep(5000)

// 前台第一个卡片的名称，用于核对信息窗内容是否对应
const firstCard = await evaluate('document.querySelector(".bb-card-title").textContent.trim()')
console.log(`\n[信息窗联动] 页面上第一块广告牌：${firstCard}`)

// 首页默认是"广告牌"列表视图，先切到"地图定位"才能看到图钉
await evaluate(
  `[...document.querySelectorAll('.view-switch-btn')].find((b) => b.textContent.includes('地图定位')).click()`,
)
await sleep(3000)
check('视图切换按钮可切换到"地图定位"', (await evaluate('!!document.querySelector(".map-panel .tmap-container")')))

// 地图面板在页面中的位置
const box = JSON.parse(await evaluate(`(() => {
  const r = document.querySelector('.map-panel').getBoundingClientRect()
  return JSON.stringify({ x: r.x, y: r.y, width: r.width, height: r.height })
})()`))

// 在面板内以 12px 网格搜索图钉（图钉约 22×30px，必然能命中），命中即停
const found = await (async () => {
  for (let y = box.y + 60; y < Math.min(box.y + box.height, 900); y += 12) {
    for (let x = box.x + 12; x < box.x + box.width - 12; x += 12) {
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
      await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
      const info = await evaluate(`(() => {
        const el = document.querySelector('.map-info')
        if (!el) return null
        return JSON.stringify({
          text: el.textContent.replace(/\\s+/g, ' ').trim(),
          hasBtn: !!el.querySelector('.map-info-btn'),
        })
      })()`)
      if (info) return { x, y, ...JSON.parse(info) }
      await sleep(120)
    }
  }
  return null
})()

check('点击图钉弹出信息窗（含"查看完整详情"按钮）', !!found && found.hasBtn, found ? found.text : '未命中任何图钉')
if (found) {
  console.log(`     命中坐标 (${found.x}, ${found.y})，信息窗内容：${found.text}`)
  check('信息窗展示了名称与报价', found.text.length > 4)

  // 点击信息窗内的"查看完整详情"
  await evaluate(`document.querySelector('.map-info-btn').click()`)
  await sleep(1200)
  const modal = JSON.parse(await evaluate(`(() => {
    const card = document.querySelector('.modal-card')
    if (!card) return JSON.stringify({ open: false })
    return JSON.stringify({
      open: true,
      title: document.querySelector('.detail-title')?.textContent.trim(),
      price: document.querySelector('.detail-price .price-num')?.textContent.trim(),
      rows: document.querySelectorAll('.detail-info .info-item').length,
    })
  })()`))
  check('点击"查看完整详情"打开详情弹窗', modal.open)
  // 信息窗是地图上的气泡，详情弹窗盖在其上；气泡不收起就会在弹窗下留一层
  check('打开详情后地图信息窗已收起', await evaluate(`(() => {
    const el = document.querySelector('.map-info')
    if (!el) return true
    const r = el.getBoundingClientRect()
    return r.width === 0 && r.height === 0
  })()`))
  check('详情弹窗渲染了完整参数表', modal.rows >= 10, `实际 ${modal.rows} 行`)
  console.log(`     弹窗标题：${modal.title}，报价：${modal.price}`)

  // 关闭弹窗
  await evaluate(`document.querySelector('.detail-actions .btn-ghost').click()`)
  await sleep(600)
  check('关闭按钮可关闭弹窗', (await evaluate('document.querySelectorAll(".modal-card").length')) === 0)
}

// 详情弹窗的"在地图中查看位置"按钮（先切回列表视图取卡片）
await evaluate(
  `[...document.querySelectorAll('.view-switch-btn')].find((b) => b.textContent.includes('广告牌')).click()`,
)
await sleep(1200)
await evaluate('document.querySelector(".bb-card-title").click()')
await sleep(1500)
await evaluate('document.querySelector(".detail-actions .btn-primary").click()')
await sleep(1800)
check('详情弹窗"在地图中查看位置"关闭弹窗并定位', (await evaluate('document.querySelectorAll(".modal-card").length')) === 0)
check('定位后自动切换到"地图定位"视图', (await evaluate('!!document.querySelector(".map-panel .tmap-container")')))

ws.close()
edge.kill()
for (let i = 0; i < 10; i += 1) {
  try { fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 3 }); break } catch (e) { await sleep(500) }
}

console.log(`\n=== 结果：通过 ${pass} 项，失败 ${fail} 项 ===\n`)
process.exit(fail === 0 ? 0 : 1)
