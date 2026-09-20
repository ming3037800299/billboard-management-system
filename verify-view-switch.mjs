/**
 * 验证首页顶部"广告牌 / 地图定位"视图切换：
 *   默认进列表 → 地图独占整幅 → 切回列表 → 卡片"地图"按钮自动跳到地图视图。
 * 用法：node verify-view-switch.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const PROFILE = 'C:/Users/Ming_16/AppData/Local/Temp/edge-view'
const PORT = 9338
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

/** 当前选中的切换按钮文案（未选中返回 null）。 */
const activeTab = () => evaluate(
  'document.querySelector(".view-switch-btn.active")?.textContent.trim() || null',
)
/** 按文案点击视图切换按钮。 */
const switchTo = (text) => evaluate(
  `[...document.querySelectorAll('.view-switch-btn')]
     .find((b) => b.textContent.includes(${JSON.stringify(text)})).click()`,
)
const hasCards = () => evaluate('!!document.querySelector(".bb-card")')
const hasMap = () => evaluate('!!document.querySelector(".map-panel .tmap-container")')

await send('Page.enable')
await send('Runtime.enable')
await send('Page.navigate', { url: 'http://localhost:8080/' })
await sleep(4500)

console.log('\n[视图切换] 首页默认状态')
const tabs = await evaluate(
  '[...document.querySelectorAll(".view-switch-btn")].map((b) => b.textContent.trim())',
)
check('顶栏有"广告牌 / 地图定位"两个切换按钮',
  Array.isArray(tabs) && tabs.length === 2
  && tabs.some((t) => t.includes('广告牌')) && tabs.some((t) => t.includes('地图定位')),
  JSON.stringify(tabs))
check('默认选中"广告牌"视图', (await activeTab() || '').includes('广告牌'))
check('默认视图显示广告牌卡片', await hasCards())
check('默认视图不渲染地图', !(await hasMap()))

console.log('\n[视图切换] 切到"地图定位"')
await switchTo('地图定位')
await sleep(3500)
check('切换后选中态变为"地图定位"', (await activeTab() || '').includes('地图定位'))
check('地图容器已挂载', await hasMap())
check('广告牌卡片列表已隐藏', !(await hasCards()))

// 地图铺满整行（与筛选栏同宽），而不是被挤在侧栏里；且高度吃满首屏剩余空间
const geo = JSON.parse(await evaluate(`(() => {
  const w = (s) => document.querySelector(s)?.getBoundingClientRect().width ?? -1
  return JSON.stringify({
    map: w('.map-panel'),
    filter: w('.filters'),
    h: Math.round(document.querySelector('.map-panel').getBoundingClientRect().height),
    scroll: document.documentElement.scrollHeight,
    viewport: window.innerHeight,
  })
})()`))
check('地图面板独占整行宽度',
  geo.map > 0 && Math.abs(geo.map - geo.filter) < 2,
  `地图 ${geo.map}px / 筛选栏 ${geo.filter}px`)
check('地图高度吃满首屏剩余空间（>=400px）', geo.h >= 400, `实际 ${geo.h}px`)
check('地图视图整页恰好一屏，无需滚动',
  geo.scroll <= geo.viewport + 2,
  `页面 ${geo.scroll}px / 视口 ${geo.viewport}px`)

console.log('\n[视图切换] 切回"广告牌"')
await switchTo('广告牌')
await sleep(1200)
check('切回后选中态变为"广告牌"', (await activeTab() || '').includes('广告牌'))
check('卡片列表重新出现', await hasCards())
check('地图容器已卸载', !(await hasMap()))

console.log('\n[视图切换] 卡片"地图"按钮联动')
await evaluate('[...document.querySelectorAll(".bb-card-actions .btn-ghost")].find((b) => !b.disabled).click()')
await sleep(3500)
check('点击卡片"地图"按钮自动跳到地图视图', await hasMap())
check('该视图下选中态同步为"地图定位"', (await activeTab() || '').includes('地图定位'))

console.log('\n[地图稳定性] 15 秒轮询不得把地图拉回初始视野')
// 点击第一个图钉：地图会居中到该点并弹出信息窗
const box = JSON.parse(await evaluate(`(() => {
  const r = document.querySelector('.map-panel').getBoundingClientRect()
  return JSON.stringify({ x: r.x, y: r.y, w: r.width, h: r.height })
})()`))
let opened = false
for (let y = box.y + 60; y < Math.min(box.y + box.h, 900) && !opened; y += 12) {
  for (let x = box.x + 12; x < box.x + box.w - 12; x += 12) {
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
    if (await evaluate('!!document.querySelector(".map-info")')) { opened = true; break }
    await sleep(80)
  }
}
check('点击图钉打开信息窗', opened)

if (opened) {
  await sleep(2200) // 等 easeTo 动画结束再取基准位置
  // 信息窗按地图投影定位，它的屏幕坐标等价于地图当前的视野
  const before = JSON.parse(await evaluate(`(() => {
    const r = document.querySelector('.map-info').getBoundingClientRect()
    return JSON.stringify({ x: Math.round(r.x), y: Math.round(r.y) })
  })()`))

  console.log('     等待 17 秒，跨过一次 15 秒轮询…')
  await sleep(17000)

  // InfoWindow.close() 只是隐藏节点、并不移除，所以用尺寸判断是否真的还开着
  const after = await evaluate(`(() => {
    const el = document.querySelector('.map-info')
    if (!el) return null
    const r = el.getBoundingClientRect()
    if (!r.width || !r.height) return null
    return JSON.stringify({ x: Math.round(r.x), y: Math.round(r.y) })
  })()`)
  check('轮询后信息窗仍然打开（未被刷新冲掉）', !!after)
  check('轮询后地图视野未回滚（信息窗位置不变）',
    !!after && Math.abs(JSON.parse(after).x - before.x) <= 1 && Math.abs(JSON.parse(after).y - before.y) <= 1,
    after ? `轮询前 (${before.x}, ${before.y}) → 轮询后 (${JSON.parse(after).x}, ${JSON.parse(after).y})` : '信息窗已消失')
}

ws.close()
edge.kill()
for (let i = 0; i < 10; i += 1) {
  try { fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 3 }); break } catch (e) { await sleep(500) }
}

console.log(`\n=== 结果：通过 ${pass} 项，失败 ${fail} 项 ===\n`)
process.exit(fail === 0 ? 0 : 1)
