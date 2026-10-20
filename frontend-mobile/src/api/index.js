/**
 * API 封装（axios）：
 *   - 统一解开 ApiResult 包装（成功返回 data，失败抛 Error）
 *   - 管理接口自动携带 X-Admin-Token 令牌
 *   - 401 统一抛出带 status 的错误，由页面处理重新登录
 *   - 站点配置与腾讯地图脚本加载（带缓存）
 */
import axios from 'axios'

const ADMIN_TOKEN_KEY = 'jqgg53_admin_token'

const http = axios.create({ timeout: 15000 })

/** 响应拦截：解开统一响应体，业务失败 / HTTP 错误统一转为 Error */
http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && body.success === false) {
      throw new Error(body.message || '请求失败')
    }
    return body ? body.data : null
  },
  (err) => {
    const message = err.response?.data?.message || err.message || '网络异常，请稍后重试'
    const e = new Error(message)
    e.status = err.response?.status
    throw e
  },
)

/* ---------------- 管理令牌 ---------------- */

export function getToken() {
  return localStorage.getItem(ADMIN_TOKEN_KEY) || ''
}

export function setToken(token) {
  localStorage.setItem(ADMIN_TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(ADMIN_TOKEN_KEY)
}

function authHeader() {
  const t = getToken()
  return t ? { 'X-Admin-Token': t } : {}
}

/**
 * 消费 SSE 响应：服务端事件 meta → delta → done → error。
 * fetch + ReadableStream 逐段解析，避免等待整段回答生成完才显示。
 */
async function consumeSse(url, options, handlers = {}) {
  const { onMeta, onDelta, onDone, onError } = handlers
  const res = await fetch(url, options)

  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`
    try {
      const body = await res.json()
      message = body?.message || message
    } catch (e) {
      // 非 JSON 错误响应，保留默认提示
    }
    throw new Error(message)
  }
  if (!res.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let sawDone = false
  let streamError = null

  const handleBlock = (block) => {
    let event = 'message'
    const dataLines = []
    for (const line of block.split('\n')) {
      if (!line || line.startsWith(':')) continue
      const idx = line.indexOf(':')
      const field = idx === -1 ? line : line.slice(0, idx)
      let value = idx === -1 ? '' : line.slice(idx + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') event = value
      else if (field === 'data') dataLines.push(value)
    }
    if (!dataLines.length) return

    let payload
    try {
      payload = JSON.parse(dataLines.join('\n'))
    } catch (e) {
      return
    }

    if (event === 'meta') {
      onMeta?.(payload)
    } else if (event === 'delta') {
      onDelta?.(payload.text || '')
    } else if (event === 'done') {
      sawDone = true
      onDone?.(payload)
    } else if (event === 'error') {
      streamError = new Error(payload.message || '流式问答失败')
      onError?.(streamError)
    }
  }

  try {
    while (!streamError && !sawDone) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      let idx
      while (!streamError && !sawDone && (idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        handleBlock(block)
      }
    }
    if (!streamError && !sawDone) {
      buffer += decoder.decode()
      if (buffer.trim()) handleBlock(buffer)
    }
    if (!streamError && !sawDone) {
      onError?.(new Error('流式连接中断，请重试'))
    }
  } finally {
    try {
      await reader.cancel()
    } catch (e) {
      // 已经结束或已取消
    }
  }
}

/** 文本选牌流式问答。handlers 可额外包含 model / history / signal。 */
async function askKnowledgeStream(question, topK = 5, handlers = {}) {
  const { model, history, signal } = handlers
  return consumeSse('/api/knowledge/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({ question, topK, model, history }),
    signal,
  }, handlers)
}

/** 图片选牌流式问答：multipart 上传图片，服务端返回 visualFeatures + 模型分析。 */
async function askImageStream(file, question, topK = 5, handlers = {}) {
  const { model, history, signal } = handlers
  const form = new FormData()
  form.append('file', file)
  if (question) form.append('question', question)
  form.append('topK', String(topK))
  if (model) form.append('model', model)
  if (history?.length) form.append('history', JSON.stringify(history))
  return consumeSse('/api/knowledge/ask/image/stream', {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    body: form,
    signal,
  }, handlers)
}

/* ---------------- API 定义 ---------------- */

export const api = {
  // 站点配置
  siteConfig: () => http.get('/api/site-config'),

  // 前台公开接口
  listBillboards: (params) => http.get('/api/billboards', { params }),
  roads: () => http.get('/api/billboards/roads'),
  publicBillboardFields: () => http.get('/api/billboards/custom-fields'),
  detail: (id) => http.get(`/api/billboards/${id}`),

  // 知识库问答（公开；大模型生成可能较慢，单独放宽超时）
  askKnowledge: (question, topK = 5, model) =>
    http.post('/api/knowledge/ask', { question, topK, model }, { timeout: 90000 }),
  // 知识库问答（SSE 流式；onMeta / onDelta / onDone / onError + model / signal）
  askKnowledgeStream: (question, topK, handlers) =>
    askKnowledgeStream(question, topK, handlers),
  // 图片选牌（SSE 流式，multipart）
  askImageStream: (file, question, topK, handlers) =>
    askImageStream(file, question, topK, handlers),
  // 可切换模型列表
  knowledgeModels: () => http.get('/api/knowledge/models'),
  // 首次进入的短提问建议
  knowledgeSuggestions: () => http.get('/api/knowledge/suggestions'),
  // 多轮对话后的猜你喜欢
  guessYouLike: (history, excludeIds) =>
    http.post('/api/knowledge/guess-you-like', { history, excludeIds }, { timeout: 30000 }),

  // 后台管理接口
  login: (password) => http.post('/api/admin/login', { password }),
  logout: () => http.post('/api/admin/logout', null, { headers: authHeader() }),
  adminList: (params) => http.get('/api/admin/billboards', { params, headers: authHeader() }),
  adminCreate: (formData) => http.post('/api/admin/billboards', formData, { headers: authHeader() }),
  adminUpdate: (id, formData) => http.put(`/api/admin/billboards/${id}`, formData, { headers: authHeader() }),
  adminDelete: (id) => http.delete(`/api/admin/billboards/${id}`, { headers: authHeader() }),
  adminRestore: (id) => http.post(`/api/admin/billboards/${id}/restore`, null, { headers: authHeader() }),
  knowledgeStatus: () => http.get('/api/admin/knowledge/status', { headers: authHeader(), timeout: 30000 }),
  knowledgeRebuild: () => http.post('/api/admin/knowledge/rebuild', null, { headers: authHeader(), timeout: 180000 }),

  // 数据库维护（后台）
  dbOverview: () => http.get('/api/admin/db/overview', { headers: authHeader() }),
  dbTables: (keyword) => http.get('/api/admin/db/tables', { params: keyword ? { keyword } : {}, headers: authHeader() }),
  dbSearch: (keyword, includeData = false) =>
    http.get('/api/admin/db/search', { params: { keyword, includeData }, headers: authHeader(), timeout: 30000 }),
  dbModules: () => http.get('/api/admin/db/modules', { headers: authHeader() }),
  dbCreateModule: (form) => http.post('/api/admin/db/modules', form, { headers: authHeader() }),
  dbUpdateModule: (id, form) => http.put(`/api/admin/db/modules/${id}`, form, { headers: authHeader() }),
  dbDeleteModule: (id) => http.delete(`/api/admin/db/modules/${id}`, { headers: authHeader() }),
  dbFields: (moduleId) => http.get(`/api/admin/db/modules/${moduleId}/fields`, { headers: authHeader() }),
  dbAddField: (moduleId, form) => http.post(`/api/admin/db/modules/${moduleId}/fields`, form, { headers: authHeader() }),
  dbUpdateField: (id, form) => http.put(`/api/admin/db/fields/${id}`, form, { headers: authHeader() }),
  dbDeleteField: (id) => http.delete(`/api/admin/db/fields/${id}`, { headers: authHeader() }),
  dbSyncField: (id) => http.post(`/api/admin/db/fields/${id}/sync`, null, { headers: authHeader() }),
  dbBillboardColumns: () => http.get('/api/admin/db/billboard-columns', { headers: authHeader() }),
  dbUpdateColumnComment: (name, comment) =>
    http.put(`/api/admin/db/billboard-columns/${encodeURIComponent(name)}`, { comment }, { headers: authHeader() }),
  billboardFieldGroups: () => http.get('/api/admin/db/billboard-fields', { headers: authHeader() }),

  // API 管理（向量模型 / 检索生成模型 / 地图 / 其他接口）
  adminApis: (params) => http.get('/api/admin/apis', { params, headers: authHeader() }),
  adminApiCreate: (body) => http.post('/api/admin/apis', body, { headers: authHeader() }),
  adminApiUpdate: (id, body) => http.put(`/api/admin/apis/${id}`, body, { headers: authHeader() }),
  adminApiDelete: (id) => http.delete(`/api/admin/apis/${id}`, { headers: authHeader() }),
  adminApiSetDefault: (id) => http.post(`/api/admin/apis/${id}/default`, null, { headers: authHeader() }),
  adminApiTestSaved: (id) => http.post(`/api/admin/apis/${id}/test`, null, { headers: authHeader(), timeout: 60000 }),
  adminApiTest: (body) => http.post('/api/admin/apis/test', body, { headers: authHeader(), timeout: 60000 }),
}

/* ---------------- 站点配置缓存 ---------------- */

let siteConfigCache = null

/** 获取站点配置（页面内多次调用只请求一次）。 */
export function getSiteConfig() {
  if (!siteConfigCache) {
    siteConfigCache = api.siteConfig().catch((e) => {
      siteConfigCache = null // 失败后允许重试
      throw e
    })
  }
  return siteConfigCache
}

/* ---------------- 腾讯地图脚本按需加载 ---------------- */

let mapLoading = null

/** 动态加载腾讯地图 GL JS API；已加载过则直接返回。 */
export function loadTMap(ak) {
  if (window.TMap) return Promise.resolve()
  if (mapLoading) return mapLoading
  mapLoading = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://map.qq.com/api/gljs?v=1.exp&key=' + encodeURIComponent(ak || '')
    script.onload = () => resolve()
    script.onerror = () => {
      mapLoading = null
      reject(new Error('地图脚本加载失败，请检查网络连接'))
    }
    document.head.appendChild(script)
  })
  return mapLoading
}
