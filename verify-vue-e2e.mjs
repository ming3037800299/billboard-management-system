/**
 * Vue 3 版前端 + Spring Boot 后端 端到端联通性验证脚本。
 * 用法：node verify-vue-e2e.mjs [baseUrl]    默认 http://localhost:8080
 * 说明：Windows 下 curl.exe 会把中文参数转成 GBK 导致乱码，故统一用 Node fetch 验证。
 */
import fs from 'node:fs'

const BASE = process.argv[2] || 'http://localhost:8080'
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

async function json(path, init) {
  const res = await fetch(BASE + path, init)
  const text = await res.text()
  let body = null
  try {
    body = JSON.parse(text)
  } catch (e) {
    /* 非 JSON 响应 */
  }
  return { res, body, text }
}

/** 生成一张最小的合法 PNG（1x1 像素）。 */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

console.log(`\n=== 目标：${BASE} ===\n`)

/* ---------- 1. 静态资源（Vue 构建产物） ---------- */
console.log('[1] 前端静态资源')
const home = await fetch(BASE + '/')
const homeHtml = await home.text()
check('GET / 返回 200', home.status === 200, `实际 ${home.status}`)
check('首页含 Vue 挂载点 <div id="app">', homeHtml.includes('id="app"'))

const assetMatch = homeHtml.match(/\/assets\/index-[A-Za-z0-9_-]+\.js/)
check('首页引用构建后的 JS 产物', !!assetMatch, assetMatch ? '' : homeHtml.slice(0, 200))
if (assetMatch) {
  const asset = await fetch(BASE + assetMatch[0])
  check(`GET ${assetMatch[0]} 返回 200`, asset.status === 200, `实际 ${asset.status}`)
}

const cssMatch = homeHtml.match(/\/assets\/index-[A-Za-z0-9_-]+\.css/)
if (cssMatch) {
  const css = await fetch(BASE + cssMatch[0])
  check(`GET ${cssMatch[0]} 返回 200`, css.status === 200, `实际 ${css.status}`)
}

/* ---------- 2. SPA history 路由 ---------- */
console.log('\n[2] SPA 路由（history 模式）')
const adminPage = await fetch(BASE + '/admin')
const adminHtml = await adminPage.text()
check('GET /admin 返回 200（转发到 index.html）', adminPage.status === 200, `实际 ${adminPage.status}`)
check('/admin 页面同样是 SPA 入口', adminHtml.includes('id="app"'))

/* ---------- 3. 示例图片 ---------- */
console.log('\n[3] 示例图片')
const svg = await fetch(BASE + '/img/samples/bb-01.svg')
check('GET /img/samples/bb-01.svg 返回 200', svg.status === 200, `实际 ${svg.status}`)

/* ---------- 4. 公开接口 ---------- */
console.log('\n[4] 前台公开接口')
const cfg = await json('/api/site-config')
check('GET /api/site-config 成功', cfg.body?.success === true, JSON.stringify(cfg.body))
check('站点配置含 mapAk（腾讯地图 Key）', !!cfg.body?.data?.mapAk)

const list0 = await json('/api/billboards')
const before = list0.body?.data?.length ?? -1
check('GET /api/billboards 成功', list0.body?.success === true)
check(`列表返回 ${before} 条种子数据`, before > 0, `实际 ${before}`)

const roads = await json('/api/billboards/roads')
check('GET /api/billboards/roads 成功', roads.body?.success === true)
check('路段筛选项非空', (roads.body?.data?.length ?? 0) > 0)

const filtered = await json('/api/billboards?status=AVAILABLE')
check(
  '状态筛选 status=AVAILABLE 生效',
  filtered.body?.data?.every((it) => it.status === 'AVAILABLE'),
)

/* ---------- 5. 管理端闭环 ---------- */
console.log('\n[5] 后台管理闭环')
const CODE = 'YS-VUE-' + Date.now().toString().slice(-6)

const login = await json('/api/admin/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ password: 'admin123' }),
})
check('密码登录成功', login.body?.success === true, JSON.stringify(login.body))
const token = login.body?.data?.token
check('返回访问令牌', !!token)

const badLogin = await json('/api/admin/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ password: 'wrong-password' }),
})
check('错误密码被拒绝', badLogin.body?.success === false)

const auth = { 'X-Admin-Token': token }
const noAuth = await json('/api/admin/billboards')
check('未带令牌访问管理接口返回 401', noAuth.res.status === 401, `实际 ${noAuth.res.status}`)

// 新增：multipart 表单 + 两张图片
function buildForm(fields, files) {
  const fd = new FormData()
  Object.entries(fields).forEach(([k, v]) => fd.append(k, v))
  files.forEach((f, i) => fd.append('images', new Blob([f.buf], { type: f.type }), f.name))
  return fd
}

const createFields = {
  name: '端到端验证广告牌',
  code: CODE,
  road: '验证高速',
  section: 'K1+000 双向',
  address: '用于验证 Vue 前端与后端联通的测试数据',
  longitude: '115.464600',
  latitude: '38.748900',
  price: '12800',
  priceUnit: '元/月',
  priceNote: '端到端测试报价',
  size: '18m × 6m',
  material: '高清喷绘布',
  visibleArea: '108㎡',
  audience: '验证车流',
  status: 'AVAILABLE',
  description: '该记录由 verify-vue-e2e.mjs 自动创建，验证结束后会自动清理。',
}

const created = await json('/api/admin/billboards', {
  method: 'POST',
  headers: auth,
  body: buildForm(createFields, [
    { buf: PNG, type: 'image/png', name: 'e2e-1.png' },
    { buf: PNG, type: 'image/png', name: 'e2e-2.png' },
  ]),
})
check('新增广告牌成功', created.body?.success === true, JSON.stringify(created.body).slice(0, 300))
const newId = created.body?.data?.id
const newImages = created.body?.data?.images ?? []
check('新增后图片存有 2 张', newImages.length === 2, `实际 ${newImages.length}`)
check('图片路径为站内相对路径', newImages.every((p) => p.startsWith('/uploads/')))

if (newImages[0]) {
  const img = await fetch(BASE + newImages[0])
  check(`上传图片可访问 ${newImages[0]}`, img.status === 200, `实际 ${img.status}`)
}

// 编号唯一性
const dup = await json('/api/admin/billboards', {
  method: 'POST',
  headers: auth,
  body: buildForm({ ...createFields, name: '重复编号测试' }, []),
})
check('重复编号被拒绝', dup.body?.success === false)

// 前台同步
const list1 = await json('/api/billboards')
check(`新增后前台列表增至 ${before + 1} 条（实时同步）`, list1.body?.data?.length === before + 1, `实际 ${list1.body?.data?.length}`)
const detail = await json(`/api/billboards/${newId}`)
check('前台按 id 查询详情成功', detail.body?.data?.code === CODE)
check('详情返回状态中文标签', detail.body?.data?.statusLabel === '可租赁')

// 编辑：保留 1 张旧图 + 新增 1 张
const editFields = {
  ...createFields,
  name: '端到端验证广告牌（已编辑）',
  price: '15800',
  status: 'RENTED',
  keepImages: JSON.stringify([newImages[0]]),
}
const updated = await json(`/api/admin/billboards/${newId}`, {
  method: 'PUT',
  headers: auth,
  body: buildForm(editFields, [{ buf: PNG, type: 'image/png', name: 'e2e-3.png' }]),
})
check('编辑广告牌成功', updated.body?.success === true, JSON.stringify(updated.body).slice(0, 300))
check('编辑后价格更新为 15800', Number(updated.body?.data?.price) === 15800)
check('编辑后状态更新为 RENTED', updated.body?.data?.status === 'RENTED')
check(
  '图片合并为 2 张（保留 1 + 新增 1）',
  updated.body?.data?.images?.length === 2,
  `实际 ${updated.body?.data?.images?.length}`,
)

// 非法坐标校验
const badCoord = await json('/api/admin/billboards', {
  method: 'POST',
  headers: auth,
  body: buildForm({ ...createFields, code: CODE + '-X', longitude: '999' }, []),
})
check('非法经度被拒绝（400）', badCoord.res.status === 400, `实际 ${badCoord.res.status}`)

// 软删除 → 前台隐藏
const del = await json(`/api/admin/billboards/${newId}`, { method: 'DELETE', headers: auth })
check('软删除成功', del.body?.success === true)
const list2 = await json('/api/billboards')
check(`删除后前台恢复 ${before} 条（同步隐藏）`, list2.body?.data?.length === before, `实际 ${list2.body?.data?.length}`)
const gone = await fetch(`${BASE}/api/billboards/${newId}`)
check('删除后前台详情不可见', gone.status === 404 || gone.status === 400, `实际 ${gone.status}`)

const adminList = await json('/api/admin/billboards?includeDeleted=true', { headers: auth })
check(
  '管理端含已删除列表可查到该记录',
  adminList.body?.data?.some((it) => it.id === newId && it.deleted === true),
)

// 恢复
const restore = await json(`/api/admin/billboards/${newId}/restore`, { method: 'POST', headers: auth })
check('恢复软删除成功', restore.body?.success === true)
const list3 = await json('/api/billboards')
check(`恢复后前台回到 ${before + 1} 条`, list3.body?.data?.length === before + 1, `实际 ${list3.body?.data?.length}`)

/* ---------- 6. 清理 ---------- */
console.log('\n[6] 清理测试数据')
const purge = await json(`/api/admin/billboards/${newId}`, { method: 'DELETE', headers: auth })
check('测试记录已软删除', purge.body?.success === true)
if (newImages.length) {
  // 只删除本次测试上传的文件，避免误删同月份目录中的其他图片
  for (const url of newImages) {
    const file = decodeURIComponent(url).replace(/^\//, '')
    fs.rmSync(file, { force: true })
  }
  console.log(`  🧹 已清理测试图片 ${newImages.length} 张`)
}

const logout = await json('/api/admin/logout', { method: 'POST', headers: auth })
check('退出登录成功', logout.body?.success === true)
const afterLogout = await json('/api/admin/billboards', { headers: auth })
check('退出后旧令牌失效（401）', afterLogout.res.status === 401, `实际 ${afterLogout.res.status}`)

const listFinal = await json('/api/billboards')
check(`清理后前台恢复 ${before} 条种子数据`, listFinal.body?.data?.length === before, `实际 ${listFinal.body?.data?.length}`)

console.log(`\n=== 结果：通过 ${pass} 项，失败 ${fail} 项 ===\n`)
process.exit(fail === 0 ? 0 : 1)
