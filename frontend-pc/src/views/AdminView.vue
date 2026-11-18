<script setup>
/**
 * 后台管理页（/admin）：
 *   - 未登录：密码验证视图（令牌存 localStorage，滑动过期 2 小时）
 *   - 已登录：左侧列表（关键词 / 状态 / 含已删除筛选 + 新增、编辑、删除、恢复）
 *             右侧表单（基本信息 / 地图选点 / 报价 / 参数 / 图片 / 描述）
 *   - 保存后前台 15 秒内自动同步，无需手动刷新
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, clearToken, getSiteConfig, getToken, setToken } from '../api'
import { fmtDateTime, fmtPrice, statusInfo } from '../utils/format'
import { toast } from '../utils/toast'
import TMapMap from '../components/TMapMap.vue'
import DatabaseMaintenancePanel from '../components/DatabaseMaintenancePanel.vue'
import BillboardExtraFields from '../components/BillboardExtraFields.vue'
import ContractWorkspace from '../components/ContractWorkspace.vue'
import ContractSettingsPanel from '../components/ContractSettingsPanel.vue'

const router = useRouter()

const logged = ref(!!getToken())
const siteName = ref('公路广告牌租赁定位平台')
const mapAk = ref('')

/** 后台当前视图：billboards=广告牌管理，contracts=预定与合同，contractSettings=签约设置，knowledge=知识库，apis=API 管理，database=数据库维护 */
const adminView = ref('billboards')
/** 从广告牌列表点击「预定」时带入的广告牌，ContractWorkspace 消费后清空 */
const reservationSeed = ref(null)
/** 手机端后台抽屉菜单 */
const adminMenuOpen = ref(false)
const knowledge = ref(null)
const knowledgeLoading = ref(false)
const knowledgeRebuilding = ref(false)
const knowledgeError = ref('')

const STATUS_OPTIONS = [
  { value: 'AVAILABLE', label: '可租赁' },
  { value: 'RESERVED', label: '已预定' },
  { value: 'RENTED', label: '已租赁' },
  { value: 'PAUSED', label: '暂停出租' },
]

/** 图片分类（与后端 BillboardImageCategory 对应） */
const IMAGE_CATEGORIES = [
  { value: 'PROMO', label: '宣传图' },
  { value: 'REAL_PHOTO', label: '实景照片' },
  { value: 'DESIGN_DRAWING', label: '设计图纸' },
]

/* ---------------- 登录 ---------------- */

const password = ref('')
const showPassword = ref(false)
const loginError = ref('')
const logging = ref(false)
const currentYear = new Date().getFullYear()

async function doLogin() {
  if (logging.value) return
  if (!password.value) {
    loginError.value = '请输入管理密码'
    return
  }
  logging.value = true
  loginError.value = ''
  try {
    const data = await api.login(password.value)
    setToken(data.token)
    password.value = ''
    logged.value = true
  } catch (e) {
    loginError.value = e.message || '密码错误，请重新输入'
  } finally {
    logging.value = false
  }
}

async function doLogout() {
  try {
    await api.logout()
  } catch (e) {
    /* 令牌可能已失效，忽略即可 */
  }
  clearToken()
  logged.value = false
  adminView.value = 'billboards'
  reservationSeed.value = null
  knowledge.value = null
  customFieldGroups.value = []
  resetForm()
  items.value = []
  apiItems.value = []
  apiTestResult.value = null
  apiError.value = ''
  Object.assign(apiForm, emptyApiForm())
  toast('已退出登录')
}

/** 统一错误处理：401 自动回到登录视图，其余弹出提示。 */
function handleError(e, fallback) {
  if (e && e.status === 401) {
    clearToken()
    logged.value = false
    toast('登录已过期，请重新验证密码', 'error')
    return
  }
  toast((e && e.message) || fallback, 'error')
}

/* ---------------- 列表与筛选 ---------------- */

const items = ref([])
const loadingList = ref(false)
const filters = reactive({ keyword: '', status: '', includeDeleted: false, sort: 'newest' })

let searchTimer = null
/** 列表请求序号：只接受最后一次请求的响应，避免旧关键词覆盖新筛选结果 */
let listSeq = 0

onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  cleanupPendingImports()
})

watch(
  () => filters.keyword,
  () => {
    clearTimeout(searchTimer)
    searchTimer = setTimeout(loadList, 350)
  },
)

watch(
  () => [filters.status, filters.includeDeleted, filters.sort],
  () => loadList(),
)

async function loadList() {
  if (!logged.value) return
  const seq = ++listSeq
  loadingList.value = true
  try {
    const params = { sort: filters.sort }
    const kw = filters.keyword.trim()
    if (kw) params.keyword = kw
    if (filters.status) params.status = filters.status
    if (filters.includeDeleted) params.includeDeleted = true
    const data = await api.adminList(params)
    if (seq !== listSeq) return
    items.value = data
  } catch (e) {
    if (seq === listSeq) handleError(e, '列表加载失败')
  } finally {
    if (seq === listSeq) loadingList.value = false
  }
}

/** 列表项状态徽章：已删除优先展示。 */
function badgeOf(item) {
  return item.deleted ? { label: '已删除', cls: 'st-plain' } : statusInfo(item.status)
}

/* ---------------- 知识库 ---------------- */

function knowledgeStatusLabel(status) {
  if (status === 'READY') return '已索引'
  if (status === 'ERROR') return '索引失败'
  return '待索引'
}

async function loadKnowledge() {
  if (!logged.value) return
  knowledgeLoading.value = true
  knowledgeError.value = ''
  try {
    knowledge.value = await api.knowledgeStatus()
  } catch (e) {
    knowledgeError.value = e.message || '知识库状态加载失败'
    handleError(e, '知识库状态加载失败')
  } finally {
    knowledgeLoading.value = false
  }
}

async function rebuildKnowledge() {
  if (knowledgeRebuilding.value) return
  knowledgeRebuilding.value = true
  knowledgeError.value = ''
  try {
    knowledge.value = await api.knowledgeRebuild()
    toast('知识库索引重建完成')
  } catch (e) {
    knowledgeError.value = e.message || '知识库重建失败'
    handleError(e, '知识库重建失败')
  } finally {
    knowledgeRebuilding.value = false
  }
}

watch(adminView, (v) => {
  if (v === 'knowledge') loadKnowledge()
  if (v === 'apis') loadApis()
})

/* ---------------- API 管理 ---------------- */

const VIEW_META = {
  billboards: {
    title: '广告牌管理',
    crumb: '广告牌管理',
    desc: '维护广告牌基础信息、地图位置与租赁报价，保存后前台自动同步。',
    meta: '前台数据每 15 秒自动同步',
  },
  knowledge: {
    title: '知识库管理',
    crumb: '知识库',
    desc: '查看广告牌向量索引状态、模型配置，并在需要时重建索引。',
    meta: '语义索引',
  },
  apis: {
    title: 'API 管理',
    crumb: 'API 管理',
    desc: '统一维护向量模型、检索生成模型、地图服务等外部接口，配置保存后立即生效。',
    meta: '数据库配置优先于 application.yml',
  },
  database: {
    title: '数据库维护',
    crumb: '数据库维护',
    desc: '检索数据所在的数据库表与字段，配置维护子模块，并为广告牌新增扩展字段/属性（数据库同步变更）。',
    meta: '表结构检索 · 字段同步 · 子模块配置',
  },
  contracts: {
    title: '预定与合同',
    crumb: '预定与合同',
    desc: '选中广告牌发起预定，按 Word 模板生成合同，把签署链接或二维码发给乙方完成电子签署。',
    meta: '预定锁定 · 模板生成 · 电子签署',
  },
  contractSettings: {
    title: '签约设置',
    crumb: '签约设置',
    desc: '维护甲方签约主体与电子签章，上传并管理 Word 合同模板。',
    meta: '甲方签章 · Word 模板',
  },
}

/** 从广告牌行直接发起预定：切到预定与合同视图，并把广告牌交给工作台预填。 */
function startReservation(billboard) {
  reservationSeed.value = { ...billboard, __seedAt: Date.now() }
  adminView.value = 'contracts'
}

/** 签约模块遇到 401 时回到登录视图。 */
function onContractUnauthorized() {
  clearToken()
  logged.value = false
  adminView.value = 'billboards'
  toast('登录已过期，请重新验证密码', 'error')
}

const viewMeta = computed(() => VIEW_META[adminView.value] || VIEW_META.billboards)
const pageMetaText = computed(() => adminView.value === 'knowledge'
  ? (knowledge.value?.embeddingModel ? `${knowledge.value.embeddingModel} 语义索引` : '语义索引')
  : viewMeta.value.meta)

const API_TYPES = [
  { value: 'EMBEDDING', label: '向量模型' },
  { value: 'LLM', label: '检索生成模型' },
  { value: 'MAP', label: '地图服务' },
  { value: 'OTHER', label: '其他接口' },
]

const API_PROVIDERS = {
  EMBEDDING: [
    { value: 'OLLAMA', label: 'Ollama 原生' },
    { value: 'OPENAI', label: 'OpenAI 兼容' },
  ],
  LLM: [{ value: 'OPENAI', label: 'OpenAI 兼容' }],
  MAP: [{ value: 'TENCENT', label: '腾讯位置服务' }],
  OTHER: [{ value: 'HTTP', label: 'HTTP 接口' }],
}

const API_TYPE_DESC = {
  EMBEDDING: '用于知识库文档与问题的向量化。修改地址或模型后会自动重建全部索引，重建期间智能选牌可能短暂使用旧向量。',
  LLM: '用于 RAG 问答与图片选牌的自然语言生成，每条配置代表一个可切换模型；默认配置用于普通问答。',
  MAP: '前台地图加载所需的腾讯位置服务 AK。每个 Key 可按域名 / 调用量分别管理，设为默认后前台刷新生效。',
  OTHER: '用于托管其他外部接口的地址与 Key，并提供 GET 连通性测试；系统不主动调用。',
}

function emptyApiForm() {
  return {
    id: null,
    name: '',
    type: 'EMBEDDING',
    provider: 'OLLAMA',
    baseUrl: 'http://127.0.0.1:11434',
    apiKey: '',
    clearApiKey: false,
    model: 'bge-m3',
    maxTokens: 2000,
    temperature: 0.2,
    timeoutSeconds: 60,
    enabled: true,
    isDefault: false,
    remark: '',
    hasStoredKey: false,
    storedKeyMasked: '',
  }
}

const apiItems = ref([])
const apiLoading = ref(false)
const apiTypeFilter = ref('')
const apiSaving = ref(false)
const apiTesting = ref(false)
const apiError = ref('')
const apiTestResult = ref(null)
const apiForm = reactive(emptyApiForm())

const editingApi = computed(() => apiForm.id != null)
const providerOptions = computed(() => API_PROVIDERS[apiForm.type] || [])
const apiCredentialLabel = computed(() => (apiForm.type === 'MAP' ? '地图 Key（AK）' : 'API Key'))
const apiModelPlaceholder = computed(() => {
  if (apiForm.type === 'EMBEDDING') return '如：bge-m3 / text-embedding-3-small'
  if (apiForm.type === 'LLM') return '如：deepseek-chat / qwen-plus'
  if (apiForm.type === 'MAP') return '地图服务无需填写模型'
  return '可选，如接口版本号或模型名称'
})

watch(apiTypeFilter, () => loadApis())

// 输入了新 Key 时自动取消“清空已保存 Key”，避免两个操作互相覆盖
watch(
  () => apiForm.apiKey,
  (value) => {
    if (value) apiForm.clearApiKey = false
  },
)

async function loadApis() {
  if (!logged.value) return
  apiLoading.value = true
  apiError.value = ''
  try {
    const params = {}
    if (apiTypeFilter.value) params.type = apiTypeFilter.value
    apiItems.value = await api.adminApis(params)
  } catch (e) {
    apiError.value = e.message || 'API 配置加载失败'
    handleError(e, 'API 配置加载失败')
  } finally {
    apiLoading.value = false
  }
}

function newApi(type) {
  Object.assign(apiForm, emptyApiForm())
  apiForm.type = type || apiTypeFilter.value || 'EMBEDDING'
  onApiTypeChange()
  apiTestResult.value = null
  apiError.value = ''
}

/** 切换类型时同步服务商与默认地址 / 参数；编辑已有配置时不调用。 */
function onApiTypeChange() {
  const options = API_PROVIDERS[apiForm.type] || []
  apiForm.provider = options[0]?.value || ''
  apiForm.apiKey = ''
  apiForm.clearApiKey = false
  apiForm.hasStoredKey = false
  apiForm.storedKeyMasked = ''
  apiTestResult.value = null
  if (apiForm.type === 'EMBEDDING') {
    apiForm.baseUrl = 'http://127.0.0.1:11434'
    apiForm.model = 'bge-m3'
    apiForm.timeoutSeconds = 60
    apiForm.maxTokens = 2000
    apiForm.temperature = 0.2
  } else if (apiForm.type === 'LLM') {
    apiForm.baseUrl = ''
    apiForm.model = ''
    apiForm.timeoutSeconds = 60
    apiForm.maxTokens = 2000
    apiForm.temperature = 0.2
  } else if (apiForm.type === 'MAP') {
    apiForm.baseUrl = 'https://apis.map.qq.com'
    apiForm.model = ''
    apiForm.timeoutSeconds = 10
  } else {
    apiForm.baseUrl = ''
    apiForm.model = ''
    apiForm.timeoutSeconds = 30
  }
}

function editApi(item) {
  Object.assign(apiForm, {
    id: item.id,
    name: item.name || '',
    type: item.type,
    provider: item.provider,
    baseUrl: item.baseUrl || (item.type === 'MAP' ? 'https://apis.map.qq.com' : ''),
    apiKey: '',
    clearApiKey: false,
    model: item.model || '',
    maxTokens: item.maxTokens ?? 2000,
    temperature: item.temperature ?? 0.2,
    timeoutSeconds: item.timeoutSeconds ?? 60,
    enabled: item.enabled,
    isDefault: item.isDefault,
    remark: item.remark || '',
    hasStoredKey: !!item.hasApiKey,
    storedKeyMasked: item.apiKeyMasked || '',
  })
  apiTestResult.value = null
  apiError.value = ''
}

function apiPayload() {
  return {
    name: apiForm.name.trim(),
    type: apiForm.type,
    provider: apiForm.provider,
    baseUrl: apiForm.baseUrl.trim(),
    apiKey: apiForm.apiKey.trim(),
    clearApiKey: !!apiForm.clearApiKey,
    model: apiForm.model.trim(),
    maxTokens: apiForm.type === 'LLM' ? (apiForm.maxTokens || null) : null,
    temperature: apiForm.type === 'LLM' ? apiForm.temperature : null,
    timeoutSeconds: apiForm.timeoutSeconds || 60,
    enabled: !!apiForm.enabled,
    isDefault: !!apiForm.isDefault,
    remark: apiForm.remark.trim(),
  }
}

async function saveApi() {
  if (apiSaving.value) return
  if (!apiForm.name.trim()) {
    toast('请填写配置名称', 'error')
    return
  }
  if (apiForm.type !== 'MAP' && !apiForm.baseUrl.trim()) {
    toast('请填写接口地址', 'error')
    return
  }
  if ((apiForm.type === 'EMBEDDING' || apiForm.type === 'LLM') && !apiForm.model.trim()) {
    toast('请填写模型名称', 'error')
    return
  }
  const isCreate = apiForm.id == null
  apiSaving.value = true
  apiError.value = ''
  try {
    const saved = isCreate
      ? await api.adminApiCreate(apiPayload())
      : await api.adminApiUpdate(apiForm.id, apiPayload())
    toast(isCreate ? 'API 配置已新增' : 'API 配置已保存')
    await loadApis()
    editApi(saved)
  } catch (e) {
    apiError.value = e.message || '保存失败'
    handleError(e, '保存失败')
  } finally {
    apiSaving.value = false
  }
}

async function deleteApi(item) {
  if (!item || item.id == null) return
  const ok = window.confirm(
    `确认删除 API 配置「${item.name}」？\n删除后该类型将自动使用下一条启用配置或 application.yml 默认值。`,
  )
  if (!ok) return
  try {
    await api.adminApiDelete(item.id)
    if (apiForm.id === item.id) newApi()
    await loadApis()
    toast('API 配置已删除')
  } catch (e) {
    handleError(e, '删除失败')
  }
}

async function setDefaultApi(item) {
  try {
    const saved = await api.adminApiSetDefault(item.id)
    await loadApis()
    if (apiForm.id === item.id) editApi(saved)
    toast('已设为该类型默认配置')
  } catch (e) {
    handleError(e, '设置默认配置失败')
  }
}

async function quickToggleApi(item) {
  const nextEnabled = !item.enabled
  try {
    const saved = await api.adminApiUpdate(item.id, {
      name: item.name,
      type: item.type,
      provider: item.provider,
      baseUrl: item.baseUrl || '',
      apiKey: '',
      clearApiKey: false,
      model: item.model || '',
      maxTokens: item.maxTokens,
      temperature: item.temperature,
      timeoutSeconds: item.timeoutSeconds || 60,
      enabled: nextEnabled,
      isDefault: nextEnabled ? item.isDefault : false,
      remark: item.remark || '',
    })
    await loadApis()
    if (apiForm.id === item.id) editApi(saved)
    toast(nextEnabled ? '配置已启用' : '配置已停用')
  } catch (e) {
    handleError(e, '操作失败')
  }
}

async function testApi() {
  if (apiTesting.value) return
  apiTesting.value = true
  apiTestResult.value = null
  try {
    const result = await api.adminApiTest({
      id: apiForm.id,
      type: apiForm.type,
      provider: apiForm.provider,
      baseUrl: apiForm.baseUrl.trim(),
      apiKey: apiForm.apiKey.trim(),
      model: apiForm.model.trim(),
      timeoutSeconds: apiForm.timeoutSeconds || 30,
    })
    apiTestResult.value = result
    if (result.success) {
      toast(`连接成功（${result.latencyMs} ms）`)
    } else {
      toast(result.message || '连接失败', 'error')
    }
  } catch (e) {
    handleError(e, '测试失败')
  } finally {
    apiTesting.value = false
  }
}

async function testSavedApi(item) {
  try {
    const result = await api.adminApiTestSaved(item.id)
    toast(
      result.success ? `「${item.name}」连接成功（${result.latencyMs} ms）` : (result.message || '连接失败'),
      result.success ? undefined : 'error',
    )
  } catch (e) {
    handleError(e, '测试失败')
  }
}

/* ---------------- 广告牌扩展字段（数据库维护模块） ---------------- */

/** 数据库维护模块配置的扩展字段，按子模块分组（字段/属性） */
const customFieldGroups = ref([])

/** 扁平化扩展字段，带所属子模块名称，便于按表单分区渲染 */
const customFields = computed(() => customFieldGroups.value.flatMap((group) =>
  (group.fields || []).map((field) => ({ ...field, moduleName: group.moduleName }))))

/** 某个表单分区内启用的扩展字段（按排序号升序） */
function customFieldsFor(section) {
  return customFields.value
    .filter((field) => (field.section || 'extension') === section && field.enabled !== false)
    .sort((a, b) => a.sortOrder - b.sortOrder)
}

async function loadCustomFields() {
  if (!logged.value) return
  try {
    customFieldGroups.value = await api.billboardFieldGroups()
    ensureExtraValueKeys()
  } catch (e) {
    if (e?.status === 401) {
      handleError(e, '扩展字段加载失败')
      return
    }
    // 扩展字段加载失败不阻塞广告牌核心功能，字段区域留空
  }
}

function ensureExtraValueKeys() {
  if (!form.extraValues) form.extraValues = {}
  for (const group of customFieldGroups.value) {
    for (const field of group.fields || []) {
      if (form.extraValues[field.fieldName] === undefined) {
        form.extraValues[field.fieldName] = ''
      }
    }
  }
}

/** 把接口返回值转换为表单控件可直接使用的字符串。 */
function extraValueForEdit(field, value) {
  if (value === null || value === undefined) return ''
  if (field.inputType === 'BOOLEAN') {
    const truthy = value === true || value === 1 || value === '1' || value === 'true'
    const falsy = value === false || value === 0 || value === '0' || value === 'false'
    return truthy ? 'true' : (falsy ? 'false' : '')
  }
  if (field.inputType === 'DATE') return String(value).slice(0, 10)
  if (field.inputType === 'DATETIME') return String(value).replace(' ', 'T').slice(0, 16)
  return String(value)
}

function collectExtraValues() {
  const values = {}
  for (const group of customFieldGroups.value) {
    for (const field of group.fields || []) {
      const value = form.extraValues?.[field.fieldName]
      values[field.fieldName] = value === undefined || value === null ? '' : String(value)
    }
  }
  return values
}

function validateExtraValues(values) {
  for (const group of customFieldGroups.value) {
    for (const field of group.fields || []) {
      if ((field.required || field.nullable === false)
          && String(values[field.fieldName] || '').trim() === '') {
        toast(`请填写「${field.label}」`, 'error')
        return false
      }
    }
  }
  return true
}

function onDatabaseUnauthorized() {
  handleError({ status: 401 }, '登录已过期')
}

watch(customFieldGroups, ensureExtraValueKeys)

/* ---------------- 表单 ---------------- */

function emptyForm() {
  return {
    id: null,
    name: '',
    code: '',
    road: '',
    section: '',
    address: '',
    longitude: '',
    latitude: '',
    price: '',
    priceUnit: '元/月',
    priceNote: '',
    size: '',
    material: '',
    visibleArea: '',
    audience: '',
    status: 'AVAILABLE',
    description: '',
    extraValues: {},
  }
}

const form = reactive(emptyForm())
/** 统一图片列表：已有图片、压缩包导入、手动选择都进入这里（url 已由后端暂存） */
const imageItems = ref([])
const importingImages = ref(false)
const importSummary = ref(null)
const showSkipped = ref(false)
/** 本次表单中尚未保存的导入会话，取消 / 切换表单时清理临时文件 */
const pendingImportSessions = ref(new Set())
const saving = ref(false)

const editing = computed(() => form.id != null)

/** 按分类分组展示（无图片的分类不显示）。 */
const imageGroups = computed(() => IMAGE_CATEGORIES
  .map((category) => ({
    ...category,
    items: imageItems.value.filter((item) => item.category === category.value),
  }))
  .filter((group) => group.items.length > 0))

/* 地图选点 ---------------- */

const pickerPoint = ref(null)
const mapRef = ref(null)

/** 选点标记（仅依赖 pickerPoint，避免输入表单时反复重建图层）。 */
const pickerMarkers = computed(() => {
  const p = pickerPoint.value
  if (!p) return []
  return [{
    id: 0,
    longitude: p.lng,
    latitude: p.lat,
    status: p.status,
    name: p.name,
    price: p.price,
    priceUnit: p.priceUnit,
    road: p.road,
    section: p.section,
  }]
})

/** 由当前表单的经纬度输入刷新选点标记。 */
function syncPicker() {
  const lng = form.longitude === '' ? NaN : Number(form.longitude)
  const lat = form.latitude === '' ? NaN : Number(form.latitude)
  if (Number.isNaN(lng) || Number.isNaN(lat)) {
    pickerPoint.value = null
    return
  }
  pickerPoint.value = {
    lng,
    lat,
    status: form.status,
    name: form.name || '选点位置',
    price: form.price,
    priceUnit: form.priceUnit,
    road: form.road,
    section: form.section,
  }
}

/** 地图点击选点：写入经纬度输入框并标记。 */
function onMapPick({ lng, lat }) {
  form.longitude = lng.toFixed(6)
  form.latitude = lat.toFixed(6)
  syncPicker()
}

/** 状态变化时同步图钉配色。 */
watch(
  () => form.status,
  (v) => {
    if (pickerPoint.value) pickerPoint.value = { ...pickerPoint.value, status: v }
  },
)

/* 图片 ---------------- */

/** 合并一次导入结果（压缩包 / 多图选择），按后端返回的分类写入列表。 */
function addImportedResult(result) {
  const items = (result.images || []).map((img) => ({
    url: img.url,
    name: img.name || '',
    category: IMAGE_CATEGORIES.some((c) => c.value === img.category) ? img.category : 'REAL_PHOTO',
    cover: false,
    confidence: img.confidence,
    reason: img.reason,
    width: img.width,
    height: img.height,
  }))
  for (const item of items) {
    if (!imageItems.value.some((i) => i.url === item.url)) {
      imageItems.value.push(item)
    }
  }
  if (result.sessionId) pendingImportSessions.value.add(result.sessionId)
  if (imageItems.value.length && !imageItems.value.some((i) => i.cover)) {
    imageItems.value[0].cover = true
  }
  const prev = importSummary.value
  importSummary.value = {
    archiveName: result.archiveName || '手动选择图片',
    accepted: (prev?.accepted || 0) + (result.accepted || 0),
    skippedCount: (prev?.skippedCount || 0) + (result.skippedCount || 0),
    skipped: [...(prev?.skipped || []), ...(result.skipped || [])].slice(0, 40),
  }
  showSkipped.value = !!importSummary.value.skippedCount
}

/** 直接选择图片：上传后立即校验 + 自动分类，不再等到保存时才上传。 */
async function onPickFiles(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = '' // 允许重复选择同一文件
  if (!files.length) return
  if (files.some((f) => f.size > 10 * 1024 * 1024)) {
    toast('单张图片不能超过 10MB', 'error')
    return
  }
  const fd = new FormData()
  files.forEach((f) => fd.append('files', f))
  importingImages.value = true
  try {
    const result = await api.adminClassifyImages(fd)
    addImportedResult(result)
    toast(`已上传 ${result.accepted} 张图片，自动分类完成`)
  } catch (err) {
    handleError(err, '图片上传失败')
  } finally {
    importingImages.value = false
  }
}

/** 上传压缩包：解压、过滤非图片并自动分类，结果按分类分组预览。 */
async function onPickArchive(e) {
  const file = (e.target.files || [])[0]
  e.target.value = ''
  if (!file) return
  if (!/\.zip$/i.test(file.name)) {
    toast('请选择 .zip 压缩包', 'error')
    return
  }
  if (file.size > 100 * 1024 * 1024) {
    toast('压缩包不能超过 100MB，请拆分后重试', 'error')
    return
  }
  const fd = new FormData()
  fd.append('file', file)
  importingImages.value = true
  try {
    const result = await api.adminImportArchive(fd)
    addImportedResult(result)
    const skipped = result.skippedCount ? `，已过滤 ${result.skippedCount} 个非图片/超限文件` : ''
    toast(`压缩包已导入 ${result.accepted} 张图片${skipped}`)
  } catch (err) {
    handleError(err, '压缩包导入失败')
  } finally {
    importingImages.value = false
  }
}

/** 设为封面：同一时间只保留一张封面，保存时后端会把封面排到首位。 */
function setCover(item) {
  imageItems.value.forEach((i) => {
    i.cover = i.url === item.url
  })
}

/** 单张删除：仅从表单移除，保存后生效。 */
function removeImage(item) {
  imageItems.value = imageItems.value.filter((i) => i.url !== item.url)
  if (imageItems.value.length && !imageItems.value.some((i) => i.cover)) {
    imageItems.value[0].cover = true
  }
}

/** 清理未保存的导入临时目录（保存后图片已转存，清理同样安全）。 */
function cleanupPendingImports() {
  const sessions = Array.from(pendingImportSessions.value)
  pendingImportSessions.value = new Set()
  sessions.forEach((id) => {
    api.adminCleanupImport(id).catch(() => {})
  })
}

/* 表单操作 ---------------- */

function resetForm() {
  cleanupPendingImports()
  Object.assign(form, emptyForm())
  imageItems.value = []
  importSummary.value = null
  showSkipped.value = false
  pickerPoint.value = null
  ensureExtraValueKeys()
}

function newItem() {
  resetForm()
  nextTick(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
}

/** 载入某条记录进入编辑态。 */
async function editItem(item) {
  // 先保留接口返回的全部扩展值，再用已知字段定义做格式转换（避免字段定义尚未加载时丢值）
  const extraValues = { ...(item.extraValues || {}) }
  for (const group of customFieldGroups.value) {
    for (const field of group.fields || []) {
      extraValues[field.fieldName] = extraValueForEdit(field, item.extraValues?.[field.fieldName])
    }
  }
  Object.assign(form, {
    id: item.id,
    name: item.name || '',
    code: item.code || '',
    road: item.road || '',
    section: item.section || '',
    address: item.address || '',
    longitude: item.longitude == null ? '' : String(item.longitude),
    latitude: item.latitude == null ? '' : String(item.latitude),
    price: item.price == null ? '' : String(item.price),
    priceUnit: item.priceUnit || '元/月',
    priceNote: item.priceNote || '',
    size: item.size || '',
    material: item.material || '',
    visibleArea: item.visibleArea || '',
    audience: item.audience || '',
    status: item.status || 'AVAILABLE',
    description: item.description || '',
    extraValues,
  })
  ensureExtraValueKeys()
  cleanupPendingImports()
  const meta = item.imageMeta && item.imageMeta.length
    ? item.imageMeta
    : (item.images || []).map((url, index) => ({
      url,
      category: 'REAL_PHOTO',
      cover: index === 0,
      name: '',
    }))
  imageItems.value = meta.map((m) => ({
    url: m.url,
    name: m.name || '',
    category: IMAGE_CATEGORIES.some((c) => c.value === m.category) ? m.category : 'REAL_PHOTO',
    cover: !!m.cover,
    confidence: null,
    reason: '',
    width: 0,
    height: 0,
  }))
  if (imageItems.value.length && !imageItems.value.some((i) => i.cover)) {
    imageItems.value[0].cover = true
  }
  importSummary.value = null
  showSkipped.value = false
  pickerPoint.value = item.longitude == null || item.latitude == null
    ? null
    : {
      lng: Number(item.longitude),
      lat: Number(item.latitude),
      status: item.status,
      name: item.name,
      price: item.price,
      priceUnit: item.priceUnit,
      road: item.road,
      section: item.section,
    }

  await nextTick()
  if (pickerPoint.value) {
    mapRef.value?.focusOn(pickerPoint.value.lng, pickerPoint.value.lat)
  }
}

/** 组装 multipart 表单并提交（新增 / 编辑）。 */
async function save() {
  if (saving.value) return
  if (!form.name.trim()) {
    toast('请填写广告牌名称', 'error')
    return
  }
  if (!form.code.trim()) {
    toast('请填写广告牌编号', 'error')
    return
  }
  const extraValues = collectExtraValues()
  if (!validateExtraValues(extraValues)) return

  const fd = new FormData()
  fd.append('name', form.name.trim())
  fd.append('code', form.code.trim())
  fd.append('road', form.road)
  fd.append('section', form.section)
  fd.append('address', form.address)
  fd.append('longitude', form.longitude)
  fd.append('latitude', form.latitude)
  fd.append('price', form.price)
  fd.append('priceUnit', form.priceUnit)
  fd.append('priceNote', form.priceNote)
  fd.append('size', form.size)
  fd.append('material', form.material)
  fd.append('visibleArea', form.visibleArea)
  fd.append('audience', form.audience)
  fd.append('status', form.status)
  fd.append('description', form.description)
  fd.append('keepImages', JSON.stringify(imageItems.value.map((i) => i.url)))
  fd.append('imageMeta', JSON.stringify(imageItems.value.map((i) => ({
    url: i.url,
    category: i.category,
    cover: !!i.cover,
    name: i.name || '',
  }))))
  fd.append('extraValues', JSON.stringify(extraValues))

  const isCreate = form.id == null
  saving.value = true
  try {
    const saved = isCreate
      ? await api.adminCreate(fd)
      : await api.adminUpdate(form.id, fd)
    toast(isCreate ? '新增成功，前台将自动同步' : '保存成功，前台将自动同步')
    await loadList()
    await editItem(saved)
  } catch (e) {
    handleError(e, '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeItem(item) {
  const ok = window.confirm(
    `确认删除「${item.name}」？\n删除后前台立即隐藏该广告牌，数据仍保留，可勾选"含已删除"后恢复。`,
  )
  if (!ok) return
  try {
    await api.adminDelete(item.id)
    if (form.id === item.id) resetForm()
    await loadList()
    toast('已删除，前台将自动同步')
  } catch (e) {
    handleError(e, '删除失败')
  }
}

async function restoreItem(item) {
  try {
    await api.adminRestore(item.id)
    await loadList()
    toast('已恢复，前台将自动同步')
  } catch (e) {
    handleError(e, '恢复失败')
  }
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  try {
    const cfg = await getSiteConfig()
    if (cfg?.siteName) siteName.value = cfg.siteName
    mapAk.value = cfg?.mapAk || ''
  } catch (e) {
    /* 站点配置失败不阻塞后台使用，仅地图不可用 */
  }
  if (logged.value) {
    loadList()
    loadCustomFields()
  }
})

watch(logged, (v) => {
  if (v) {
    loadList()
    loadCustomFields()
  }
})
</script>

<template>
  <!-- 未登录：企业级分屏登录 -->
  <div v-if="!logged" class="login-view">
    <!-- 左侧品牌区 -->
    <aside class="login-brand">
      <div class="login-brand-top">
        <span class="login-brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="4" width="16" height="9" rx="1" />
            <path d="M9 20l2-7M15 20l-2-7M7 20h4M13 20h4" />
          </svg>
        </span>
        <span class="login-brand-name">
          公路广告牌租赁平台
          <small>BILLBOARD LEASING PLATFORM</small>
        </span>
      </div>

      <div class="login-brand-main">
        <div class="login-brand-rule"></div>
        <div class="login-brand-eyebrow">RESOURCE MANAGEMENT</div>
        <h2>广告牌资源<br>一体化管理平台</h2>
        <p>资源、报价、位置与知识库统一管理，前后台数据实时同步。</p>
        <ul class="login-brand-points">
          <li>
            <span class="point-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4" width="18" height="11" rx="1" />
                <path d="M8 20l2-5M16 20l-2-5M6 20h4M14 20h4" />
              </svg>
            </span>
            广告牌资源与租赁报价管理
          </li>
          <li>
            <span class="point-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 21s7-6.1 7-11a7 7 0 1 0-14 0c0 4.9 7 11 7 11z" />
                <circle cx="12" cy="10" r="2.4" />
              </svg>
            </span>
            地图选址与位置标注
          </li>
          <li>
            <span class="point-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <ellipse cx="12" cy="5" rx="8" ry="3" />
                <path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
              </svg>
            </span>
            知识库语义检索与智能选牌
          </li>
        </ul>
      </div>

      <div class="login-brand-bottom">
        <span>公路广告牌租赁定位系统</span>
        <span>v1.0.0</span>
      </div>

      <svg class="login-brand-deco" viewBox="0 0 480 360" fill="none" aria-hidden="true">
        <rect x="80" y="30" width="320" height="160" rx="6" stroke="currentColor" stroke-width="1.4" />
        <path d="M150 190v120M330 190v120M120 310h60M300 310h60" stroke="currentColor" stroke-width="1.4" />
        <path d="M0 300h480M0 325h480M0 350h480" stroke="currentColor" stroke-width="1.2" opacity=".6" />
        <circle cx="80" cy="110" r="4" fill="currentColor" />
        <circle cx="400" cy="110" r="4" fill="currentColor" />
      </svg>
    </aside>

    <!-- 右侧登录表单 -->
    <section class="login-panel">
      <a class="login-back" href="/">返回前台展示页</a>

      <div class="login-form-wrap">
        <div class="login-mobile-brand">
          <span class="login-brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <rect x="4" y="4" width="16" height="9" rx="1" />
              <path d="M9 20l2-7M15 20l-2-7M7 20h4M13 20h4" />
            </svg>
          </span>
          <span class="login-mobile-name">公路广告牌租赁平台</span>
        </div>

        <div class="login-eyebrow">ADMIN CONSOLE</div>
        <h1>登录管理后台</h1>
        <p class="login-sub">请输入管理员密码，进入广告牌资源管理工作台。</p>

        <form class="login-form" @submit.prevent="doLogin">
          <div class="login-field">
            <label for="admin-password">管理密码</label>
            <div class="login-input-wrap">
              <span class="login-input-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="5" y="10" width="14" height="10" rx="1" />
                  <path d="M8 10V7a4 4 0 0 1 8 0v3" />
                </svg>
              </span>
              <input
                id="admin-password"
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入管理密码"
                autocomplete="current-password"
                :disabled="logging"
              >
              <button
                class="login-eye"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <svg v-if="!showPassword" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6S2.5 12 2.5 12z" />
                  <circle cx="12" cy="12" r="2.6" />
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M3 3l18 18" />
                  <path d="M10.6 6.2A10.8 10.8 0 0 1 12 6c6 0 9.5 6 9.5 6a16.4 16.4 0 0 1-3.2 3.8M6.2 6.8C4 8.5 2.5 12 2.5 12s3.5 6 9.5 6c1.5 0 2.8-.4 4-1" />
                  <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
                </svg>
              </button>
            </div>
          </div>

          <div v-if="loginError" class="login-error-box">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 7.5v5M12 16.5h.01" />
            </svg>
            <span>{{ loginError }}</span>
          </div>

          <button class="btn btn-primary login-submit" type="submit" :disabled="logging">
            {{ logging ? '验证中…' : '登 录' }}
          </button>
        </form>

        <div class="login-hint">
          管理密码由部署环境的 <code>ADMIN_PASSWORD</code> 配置；如已修改，请使用新密码。
        </div>

        <div class="login-meta">
          <span>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 3l7 3v5c0 4.4-3 7.5-7 9-4-1.5-7-4.6-7-9V6l7-3z" />
              <path d="M9 12l2 2 4-4" />
            </svg>
            安全登录
          </span>
          <span>令牌 2 小时有效</span>
        </div>
      </div>

      <div class="login-copyright">© {{ currentYear }} 公路广告牌租赁定位系统</div>
    </section>
  </div>

  <!-- 已登录：工作台 -->
  <div v-else class="oa-shell admin-shell" :class="{ 'admin-menu-open': adminMenuOpen }">
    <!-- 左侧导航：桌面固定，手机端为抽屉 -->
    <aside class="oa-sidebar" @click="adminMenuOpen = false">
      <div class="oa-sidebar-brand">
        <span class="oa-sidebar-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="4" width="16" height="9" rx="1" />
            <path d="M9 20l2-7M15 20l-2-7M7 20h4M13 20h4" />
          </svg>
        </span>
        <span class="oa-sidebar-brand-text">
          <strong>广告牌管理平台</strong>
          <span>Admin Console</span>
        </span>
      </div>

      <div class="oa-menu-label">资源管理</div>
      <nav class="oa-menu">
        <button
          class="oa-menu-item"
          :class="{ active: adminView === 'billboards' }"
          type="button"
          @click="adminView = 'billboards'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="4" width="18" height="11" rx="1" />
            <path d="M8 20l2-5M16 20l-2-5M6 20h4M14 20h4" />
          </svg>
          广告牌管理
        </button>
      </nav>

      <div class="oa-menu-label">租赁业务</div>
      <nav class="oa-menu">
        <button
          class="oa-menu-item"
          :class="{ active: adminView === 'contracts' }"
          type="button"
          @click="adminView = 'contracts'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M7 3h7l4 4v14H7z" />
            <path d="M14 3v4h4M9 12h6M9 16h4" />
          </svg>
          预定与合同
        </button>
      </nav>

      <div class="oa-menu-label">智能服务</div>
      <nav class="oa-menu">
        <button
          class="oa-menu-item"
          :class="{ active: adminView === 'knowledge' }"
          type="button"
          @click="adminView = 'knowledge'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <ellipse cx="12" cy="5" rx="8" ry="3" />
            <path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" />
          </svg>
          知识库
        </button>
      </nav>

      <div class="oa-menu-label">系统管理</div>
      <nav class="oa-menu">
        <button
          class="oa-menu-item"
          :class="{ active: adminView === 'contractSettings' }"
          type="button"
          @click="adminView = 'contractSettings'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 3l7 4v5c0 4.4-2.8 7.8-7 9-4.2-1.2-7-4.6-7-9V7z" />
            <path d="M9 12l2 2 4-4" />
          </svg>
          签约设置
        </button>
        <button
          class="oa-menu-item"
          :class="{ active: adminView === 'database' }"
          type="button"
          @click="adminView = 'database'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <ellipse cx="12" cy="5" rx="7" ry="3" />
            <path d="M5 5v6c0 1.7 3.1 3 7 3s7-1.3 7-3V5M5 11v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" />
          </svg>
          数据库维护
        </button>
        <button
          class="oa-menu-item"
          :class="{ active: adminView === 'apis' }"
          type="button"
          @click="adminView = 'apis'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M9 3v5M15 3v5M7 8h10v4a5 5 0 0 1-10 0V8z" />
            <path d="M12 17v4" />
          </svg>
          API 管理
        </button>
        <button class="oa-menu-item" type="button" @click="router.push('/')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="4" width="18" height="12" rx="1" />
            <path d="M8 20h8M12 16v4" />
          </svg>
          前台展示
        </button>
      </nav>

      <div class="oa-sidebar-status">
        <span class="status-dot"></span>
        <div>
          <strong>系统运行正常</strong>
          <span>前台数据每 15 秒自动同步</span>
        </div>
      </div>
      <div class="oa-sidebar-foot">{{ siteName }} · v1.0.0</div>
    </aside>

    <div
      v-if="adminMenuOpen"
      class="admin-menu-backdrop"
      aria-hidden="true"
      @click="adminMenuOpen = false"
    ></div>

    <div class="oa-main">
      <!-- 顶栏 -->
      <header class="admin-topbar">
        <div class="admin-topbar-inner">
          <button
            class="admin-menu-toggle"
            type="button"
            aria-label="打开后台菜单"
            :aria-expanded="adminMenuOpen"
            @click="adminMenuOpen = true"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
              <path d="M4 7h16M4 12h16M4 17h16" />
            </svg>
          </button>
          <div class="oa-crumb">后台管理 <span>/</span> <b>{{ viewMeta.crumb }}</b></div>
          <div class="admin-topbar-actions">
            <span class="oa-user">
              <span class="oa-avatar">管</span>
              <span>系统管理员</span>
            </span>
            <span class="oa-topbar-divider"></span>
            <button class="btn btn-ghost btn-sm" type="button" @click="doLogout">退出登录</button>
          </div>
        </div>
      </header>

      <div class="oa-content">
        <div class="oa-page-head">
          <div>
            <h1 class="oa-page-title">{{ viewMeta.title }}</h1>
            <p class="oa-page-desc">{{ viewMeta.desc }}</p>
          </div>
          <div class="oa-page-meta">
            <span class="status-dot"></span>
            {{ pageMetaText }}
          </div>
        </div>

        <div v-if="adminView === 'billboards'" class="admin-layout">
          <!-- 左栏：列表 -->
          <aside class="admin-list-panel">
            <div class="admin-list-toolbar">
              <button class="btn btn-primary" type="button" @click="newItem">新增广告牌</button>
              <button class="btn btn-ghost" type="button" :disabled="loadingList" @click="loadList">
                {{ loadingList ? '刷新中' : '刷新' }}
              </button>
            </div>

            <div class="admin-list-filters">
              <input v-model="filters.keyword" type="search" placeholder="搜索名称 / 编号 / 路段 / 地址">
              <div class="admin-filter-row">
                <select v-model="filters.status">
                  <option value="">全部状态</option>
                  <option v-for="s in STATUS_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
                </select>
                <select v-model="filters.sort">
                  <option value="newest">最新发布</option>
                  <option value="priceAsc">价格升序</option>
                  <option value="priceDesc">价格降序</option>
                </select>
              </div>
              <label class="admin-check">
                <input v-model="filters.includeDeleted" type="checkbox">
                <span>包含已删除记录</span>
              </label>
            </div>

            <div class="admin-list-meta">
              共 <strong>{{ items.length }}</strong> 条记录
            </div>

            <div class="admin-list">
              <div v-if="loadingList && !items.length" class="admin-list-empty">正在加载…</div>
              <div v-else-if="!items.length" class="admin-list-empty">暂无广告牌数据，点击"新增广告牌"开始录入</div>

              <div
                v-for="it in items"
                :key="it.id"
                class="admin-item"
                :class="{ active: form.id === it.id, 'is-deleted': it.deleted }"
                @click="editItem(it)"
              >
                <div class="admin-item-top">
                  <span class="admin-item-name">{{ it.name }}</span>
                  <span class="badge" :class="badgeOf(it).cls">{{ badgeOf(it).label }}</span>
                </div>
                <div class="admin-item-sub">
                  <span>{{ it.code }}</span>
                  <span>{{ it.road || '未填写公路' }}</span>
                  <span v-if="it.section">{{ it.section }}</span>
                </div>
                <div class="admin-item-foot">
                  <span class="admin-item-price">{{ it.price == null || it.price === '' ? '面议' : '¥' + fmtPrice(it.price) }} {{ it.priceUnit || '' }}</span>
                  <div class="admin-item-actions" @click.stop>
                    <button
                      v-if="!it.deleted && it.status === 'AVAILABLE'"
                      class="link-btn"
                      type="button"
                      @click="startReservation(it)"
                    >预定</button>
                    <button class="link-btn" type="button" @click="editItem(it)">编辑</button>
                    <button v-if="!it.deleted" class="link-btn danger" type="button" @click="removeItem(it)">删除</button>
                    <button v-else class="link-btn" type="button" @click="restoreItem(it)">恢复</button>
                  </div>
                </div>
              </div>
            </div>
          </aside>

      <!-- 右栏：表单 -->
      <section class="admin-form-panel">
        <form class="admin-form" @submit.prevent="save">
          <div class="form-head">
            <h3>{{ editing ? '编辑广告牌' : '新增广告牌' }}</h3>
            <span class="form-meta">
              {{ editing ? '编号：' + form.code + '（保存后前台自动同步）' : '带 * 为必填项，保存后前台自动同步' }}
            </span>
          </div>

          <!-- 基本信息 -->
          <fieldset class="form-section">
            <legend>基本信息</legend>
            <div class="form-grid">
              <div class="form-field">
                <label for="a-name">广告牌名称 <em>*</em></label>
                <input id="a-name" v-model="form.name" type="text" maxlength="100" placeholder="如：京港澳高速 K1200 双面立柱广告牌">
              </div>
              <div class="form-field">
                <label for="a-code">广告牌编号 <em>*</em></label>
                <input id="a-code" v-model="form.code" type="text" maxlength="50" placeholder="全局唯一，如：YS-BB-001">
              </div>
              <div class="form-field">
                <label for="a-road">所在公路</label>
                <input id="a-road" v-model="form.road" type="text" maxlength="100" placeholder="如：京港澳高速">
              </div>
              <div class="form-field">
                <label for="a-section">所属路段</label>
                <input id="a-section" v-model="form.section" type="text" maxlength="200" placeholder="如：K1200+300 北京方向">
              </div>
              <div class="form-field span-2">
                <label for="a-address">详细位置</label>
                <input id="a-address" v-model="form.address" type="text" maxlength="500" placeholder="便于承租方找到具体位置的文字描述">
              </div>
              <div class="form-field span-2">
                <label>租赁状态 <em>*</em></label>
                <div class="radio-group">
                  <button
                    v-for="s in STATUS_OPTIONS"
                    :key="s.value"
                    class="radio-item"
                    :class="{ checked: form.status === s.value }"
                    type="button"
                    :aria-pressed="form.status === s.value"
                    @click="form.status = s.value"
                  >{{ s.label }}</button>
                </div>
              </div>
              <BillboardExtraFields :fields="customFieldsFor('basic')" :values="form.extraValues" />
            </div>
          </fieldset>

          <!-- 位置坐标 -->
          <fieldset class="form-section">
            <legend>位置坐标（地图选点）</legend>
            <div class="form-grid">
              <div class="form-field">
                <label for="a-lng">经度</label>
                <input
                  id="a-lng" v-model="form.longitude" type="text"
                  placeholder="如 115.464600" @change="syncPicker"
                >
              </div>
              <div class="form-field">
                <label for="a-lat">纬度</label>
                <input
                  id="a-lat" v-model="form.latitude" type="text"
                  placeholder="如 38.748900" @change="syncPicker"
                >
              </div>
              <div class="map-picker-wrap">
                <div class="map-picker">
                  <TMapMap
                    v-if="mapAk"
                    ref="mapRef"
                    :ak="mapAk"
                    :markers="pickerMarkers"
                    :selectable="true"
                    :auto-fit="false"
                    :initial-zoom="6"
                  />
                </div>
                <div class="map-picker-tip">
                  点击地图任意位置即可自动填入经纬度；也可直接手动输入后按回车/失焦生效。
                </div>
              </div>
              <BillboardExtraFields :fields="customFieldsFor('location')" :values="form.extraValues" />
            </div>
          </fieldset>

          <!-- 报价信息 -->
          <fieldset class="form-section">
            <legend>租赁报价</legend>
            <div class="form-grid">
              <div class="form-field">
                <label for="a-price">租赁单价（元）</label>
                <input id="a-price" v-model="form.price" type="text" placeholder="留空表示面议">
              </div>
              <div class="form-field">
                <label for="a-unit">计费周期</label>
                <select id="a-unit" v-model="form.priceUnit">
                  <option value="元/月">元/月</option>
                  <option value="元/年">元/年</option>
                </select>
              </div>
              <div class="form-field span-2">
                <label for="a-note">报价说明</label>
                <input id="a-note" v-model="form.priceNote" type="text" maxlength="500" placeholder="如：含首次画面制作费，不含电费">
              </div>
              <BillboardExtraFields :fields="customFieldsFor('price')" :values="form.extraValues" />
            </div>
          </fieldset>

          <!-- 广告位参数 -->
          <fieldset class="form-section">
            <legend>广告位参数</legend>
            <div class="form-grid">
              <div class="form-field">
                <label for="a-size">尺寸规格</label>
                <input id="a-size" v-model="form.size" type="text" maxlength="100" placeholder="如：18m × 6m">
              </div>
              <div class="form-field">
                <label for="a-material">画面材质</label>
                <input id="a-material" v-model="form.material" type="text" maxlength="100" placeholder="如：高清喷绘布">
              </div>
              <div class="form-field">
                <label for="a-visible">可视面积</label>
                <input id="a-visible" v-model="form.visibleArea" type="text" maxlength="100" placeholder="如：108㎡">
              </div>
              <div class="form-field">
                <label for="a-audience">主要受众</label>
                <input id="a-audience" v-model="form.audience" type="text" maxlength="500" placeholder="如：高速往返车流，日均 3 万车次">
              </div>
              <BillboardExtraFields :fields="customFieldsFor('params')" :values="form.extraValues" />
            </div>
          </fieldset>

          <!-- 扩展字段（由表单设计决定分区与位置） -->
          <fieldset v-if="customFieldsFor('extension').length" class="form-section">
            <legend>扩展信息</legend>
            <div class="form-grid">
              <BillboardExtraFields :fields="customFieldsFor('extension')" :values="form.extraValues" />
            </div>
          </fieldset>

          <!-- 图片资料 -->
          <fieldset class="form-section">
            <legend>图片资料 <span class="img-legend-tip">支持压缩包批量上传，系统自动分类</span></legend>

            <div class="img-import-bar">
              <label class="btn btn-ghost img-pick-btn" :class="{ disabled: importingImages }">
                <input type="file" accept=".zip,application/zip" :disabled="importingImages" @change="onPickArchive">
                上传压缩包（.zip）
              </label>
              <label class="btn btn-ghost img-pick-btn" :class="{ disabled: importingImages }">
                <input type="file" accept="image/*" multiple :disabled="importingImages" @change="onPickFiles">
                选择图片（可多选）
              </label>
              <span v-if="importingImages" class="img-importing">正在校验并识别图片…</span>
              <span v-else class="img-import-hint">jpg / png / gif / bmp / webp；单张 ≤ 10MB，压缩包 ≤ 100MB</span>
            </div>

            <div v-if="importSummary" class="img-import-summary">
              <span>本次已导入 <strong>{{ importSummary.accepted }}</strong> 张（{{ importSummary.archiveName }}）</span>
              <span v-if="importSummary.skippedCount">，过滤 {{ importSummary.skippedCount }} 个文件</span>
              <button v-if="importSummary.skippedCount" class="link-btn" type="button" @click="showSkipped = !showSkipped">
                {{ showSkipped ? '收起明细' : '查看明细' }}
              </button>
              <ul v-if="showSkipped && importSummary.skipped.length" class="img-skip-list">
                <li v-for="(s, i) in importSummary.skipped" :key="i">
                  <span class="img-skip-name">{{ s.name }}</span>
                  <span class="img-skip-reason">{{ s.reason }}</span>
                </li>
              </ul>
            </div>

            <div v-if="!imageItems.length" class="img-row-empty">暂无图片，可上传压缩包批量导入或手动选择</div>

            <div v-for="group in imageGroups" :key="group.value" class="img-group">
              <div class="img-group-head">
                <span class="img-group-name">{{ group.label }}</span>
                <span class="img-group-count">{{ group.items.length }} 张</span>
              </div>
              <div class="img-row">
                <div v-for="item in group.items" :key="item.url" class="img-card" :class="{ 'is-cover': item.cover }">
                  <div class="img-thumb">
                    <img :src="item.url" :alt="item.name || group.label" :title="item.name">
                    <button class="img-remove" type="button" title="移除该图" @click="removeImage(item)">×</button>
                    <span v-if="item.cover" class="img-cover-badge">封面</span>
                  </div>
                  <div class="img-card-actions">
                    <select v-model="item.category" class="img-category-select" title="手动调整分类">
                      <option v-for="c in IMAGE_CATEGORIES" :key="c.value" :value="c.value">{{ c.label }}</option>
                    </select>
                    <button class="img-cover-btn" type="button" :disabled="item.cover" @click="setCover(item)">
                      {{ item.cover ? '当前封面' : '设为封面' }}
                    </button>
                  </div>
                  <div v-if="item.reason" class="img-reason" :title="item.reason">{{ item.reason }}</div>
                </div>
              </div>
            </div>
          </fieldset>

          <!-- 描述 -->
          <fieldset class="form-section">
            <legend>补充描述</legend>
            <div class="form-field span-2">
              <textarea v-model="form.description" rows="4" placeholder="广告牌周边环境、可见角度、照明情况等补充说明"></textarea>
            </div>
          </fieldset>

          <div class="form-actions">
            <button class="btn btn-primary" type="submit" :disabled="saving">
              {{ saving ? '保存中…' : (editing ? '保存修改' : '确认新增') }}
            </button>
            <button class="btn btn-ghost" type="button" @click="resetForm">
              {{ editing ? '取消编辑' : '清空表单' }}
            </button>
            <span class="form-actions-spacer"></span>
            <button
              v-if="editing"
              class="btn btn-danger"
              type="button"
              @click="removeItem({ id: form.id, name: form.name })"
            >删除该广告牌</button>
          </div>
        </form>
      </section>
        </div>

        <ContractWorkspace
          v-else-if="adminView === 'contracts'"
          :seed="reservationSeed"
          @unauthorized="onContractUnauthorized"
          @clear-seed="reservationSeed = null"
        />

        <ContractSettingsPanel
          v-else-if="adminView === 'contractSettings'"
          @unauthorized="onContractUnauthorized"
        />

        <div v-else-if="adminView === 'apis'" class="admin-layout api-layout">
          <!-- 左栏：API 配置列表 -->
          <aside class="admin-list-panel">
            <div class="admin-list-toolbar">
              <button class="btn btn-primary" type="button" @click="newApi()">新增 API 配置</button>
              <button class="btn btn-ghost" type="button" :disabled="apiLoading" @click="loadApis">
                {{ apiLoading ? '刷新中' : '刷新' }}
              </button>
            </div>

            <div class="admin-list-filters">
              <select v-model="apiTypeFilter">
                <option value="">全部类型</option>
                <option v-for="t in API_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
              <div class="api-filter-hint">
                数据库配置优先于 application.yml；停用或删除默认项后自动选用同类下一条启用配置。
              </div>
            </div>

            <div class="admin-list-meta">
              共 <strong>{{ apiItems.length }}</strong> 条配置
            </div>

            <div class="admin-list">
              <div v-if="apiLoading && !apiItems.length" class="admin-list-empty">正在加载…</div>
              <div v-else-if="!apiItems.length" class="admin-list-empty">
                暂无 API 配置，点击“新增 API 配置”添加向量模型、问答模型或地图服务
              </div>

              <div
                v-for="it in apiItems"
                :key="it.id"
                class="admin-item api-item"
                :class="{ active: apiForm.id === it.id, 'is-disabled': !it.enabled }"
                @click="editApi(it)"
              >
                <div class="admin-item-top">
                  <span class="admin-item-name">{{ it.name }}</span>
                  <span
                    class="badge"
                    :class="it.effective ? 'st-available' : (it.enabled ? 'st-plain' : 'st-paused')"
                  >{{ it.effective ? '生效中' : (it.enabled ? '已启用' : '已停用') }}</span>
                </div>
                <div class="admin-item-sub">
                  <span class="api-type-chip">{{ it.typeLabel }}</span>
                  <span>{{ it.providerLabel }}</span>
                  <span v-if="it.isDefault" class="api-default-mark">默认</span>
                </div>
                <div class="api-item-line">{{ it.baseUrl || '未填写接口地址' }}</div>
                <div class="api-item-line">
                  <span v-if="it.model" class="api-item-model">模型：{{ it.model }}</span>
                  <span v-if="it.hasApiKey" class="api-key-mask">{{ it.apiKeyMasked }}</span>
                  <span v-else-if="it.type === 'EMBEDDING' && it.provider === 'OLLAMA'" class="api-key-local">本地服务无需 Key</span>
                  <span v-else class="api-key-empty">未配置 Key</span>
                </div>
                <div class="admin-item-foot">
                  <span class="api-item-time">{{ it.updatedAt ? '更新于 ' + fmtDateTime(it.updatedAt) : '' }}</span>
                  <div class="admin-item-actions" @click.stop>
                    <button class="link-btn" type="button" @click="testSavedApi(it)">测试</button>
                    <button v-if="!it.isDefault" class="link-btn" type="button" @click="setDefaultApi(it)">设为默认</button>
                    <button class="link-btn" type="button" @click="quickToggleApi(it)">{{ it.enabled ? '停用' : '启用' }}</button>
                  </div>
                </div>
              </div>
            </div>
          </aside>

          <!-- 右栏：配置表单 -->
          <section class="admin-form-panel">
            <form class="admin-form" @submit.prevent="saveApi">
              <div class="form-head">
                <h3>{{ editingApi ? '编辑 API 配置' : '新增 API 配置' }}</h3>
                <span class="form-meta">
                  {{ editingApi ? 'API Key 留空表示保持原值，保存后立即生效' : '数据库配置优先于 application.yml' }}
                </span>
              </div>

              <div v-if="apiError" class="knowledge-error api-form-error">{{ apiError }}</div>

              <fieldset class="form-section">
                <legend>基本信息</legend>
                <div class="form-grid">
                  <div class="form-field">
                    <label for="api-name">配置名称 <em>*</em></label>
                    <input
                      id="api-name"
                      v-model="apiForm.name"
                      type="text"
                      maxlength="100"
                      placeholder="如：本地 bge-m3 / DeepSeek 官方 / 腾讯地图"
                    >
                  </div>
                  <div class="form-field">
                    <label for="api-type">接口类型 <em>*</em></label>
                    <select id="api-type" v-model="apiForm.type" @change="onApiTypeChange">
                      <option v-for="t in API_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
                    </select>
                  </div>
                  <div class="form-field">
                    <label for="api-provider">服务商协议 <em>*</em></label>
                    <select id="api-provider" v-model="apiForm.provider">
                      <option v-for="p in providerOptions" :key="p.value" :value="p.value">{{ p.label }}</option>
                    </select>
                  </div>
                  <div class="form-field">
                    <label for="api-timeout">读取超时（秒）</label>
                    <input id="api-timeout" v-model.number="apiForm.timeoutSeconds" type="number" min="1" max="300">
                  </div>
                  <div class="form-field span-2 api-type-desc">
                    {{ API_TYPE_DESC[apiForm.type] }}
                  </div>
                  <div class="form-field span-2">
                    <label for="api-remark">备注</label>
                    <input
                      id="api-remark"
                      v-model="apiForm.remark"
                      type="text"
                      maxlength="500"
                      placeholder="用途、额度、负责人等说明"
                    >
                  </div>
                </div>
              </fieldset>

              <fieldset class="form-section">
                <legend>连接参数</legend>
                <div class="form-grid">
                  <div class="form-field span-2">
                    <label for="api-base-url">接口地址 <em v-if="apiForm.type !== 'MAP'">*</em></label>
                    <input
                      id="api-base-url"
                      v-model="apiForm.baseUrl"
                      type="text"
                      maxlength="500"
                      placeholder="如 http://127.0.0.1:11434 或 https://api.deepseek.com/v1"
                    >
                    <span class="field-hint">
                      Ollama 填写服务根地址；OpenAI 兼容接口填写到 /v1；地图服务填写腾讯位置服务根地址。
                    </span>
                  </div>
                  <div class="form-field">
                    <label for="api-model">
                      模型名称
                      <em v-if="apiForm.type === 'EMBEDDING' || apiForm.type === 'LLM'">*</em>
                    </label>
                    <input
                      id="api-model"
                      v-model="apiForm.model"
                      type="text"
                      maxlength="100"
                      :placeholder="apiModelPlaceholder"
                    >
                  </div>
                  <div class="form-field">
                    <label for="api-key">
                      {{ apiCredentialLabel }}
                      <em v-if="apiForm.type === 'MAP'">*</em>
                    </label>
                    <input
                      id="api-key"
                      v-model="apiForm.apiKey"
                      type="password"
                      maxlength="1000"
                      autocomplete="new-password"
                      :placeholder="editingApi && apiForm.hasStoredKey ? '留空保持不变' : '粘贴 API Key / 地图 AK'"
                    >
                    <span v-if="editingApi && apiForm.hasStoredKey" class="field-hint">
                      已保存：{{ apiForm.storedKeyMasked }}
                      <label class="admin-check api-clear-key">
                        <input v-model="apiForm.clearApiKey" type="checkbox">
                        <span>清空已保存的 Key</span>
                      </label>
                    </span>
                  </div>
                  <template v-if="apiForm.type === 'LLM'">
                    <div class="form-field">
                      <label for="api-max-tokens">单次最大 token 数</label>
                      <input id="api-max-tokens" v-model.number="apiForm.maxTokens" type="number" min="1" max="32000">
                    </div>
                    <div class="form-field">
                      <label for="api-temperature">采样温度（0 ~ 2）</label>
                      <input id="api-temperature" v-model.number="apiForm.temperature" type="number" min="0" max="2" step="0.1">
                    </div>
                  </template>
                </div>
              </fieldset>

              <fieldset class="form-section">
                <legend>启用设置</legend>
                <div class="form-grid">
                  <label class="admin-check api-switch">
                    <input v-model="apiForm.enabled" type="checkbox">
                    <span>启用此配置</span>
                  </label>
                  <label class="admin-check api-switch">
                    <input v-model="apiForm.isDefault" type="checkbox">
                    <span>设为该类型默认配置</span>
                  </label>
                  <div class="form-field span-2">
                    <div class="api-callout">
                      同一类型只有一条默认配置生效；停用当前默认项后，系统会自动选择同类下一条启用配置。
                      向量模型保存后会异步重建知识库索引；检索生成模型与地图配置保存后立即生效（前台地图刷新页面后加载新 Key）。
                    </div>
                  </div>
                </div>
              </fieldset>

              <div v-if="apiTestResult" class="api-test-result" :class="apiTestResult.success ? 'ok' : 'fail'">
                <strong>{{ apiTestResult.success ? '连接成功' : '连接失败' }}</strong>
                <span>{{ apiTestResult.message }}</span>
                <span class="api-test-latency">{{ apiTestResult.latencyMs }} ms</span>
              </div>

              <div class="form-actions">
                <button class="btn btn-primary" type="submit" :disabled="apiSaving">
                  {{ apiSaving ? '保存中…' : (editingApi ? '保存修改' : '确认新增') }}
                </button>
                <button class="btn btn-ghost" type="button" :disabled="apiTesting" @click="testApi">
                  {{ apiTesting ? '测试中…' : '测试连接' }}
                </button>
                <button class="btn btn-ghost" type="button" @click="newApi()">
                  {{ editingApi ? '取消编辑' : '清空表单' }}
                </button>
                <span class="form-actions-spacer"></span>
                <button
                  v-if="editingApi"
                  class="btn btn-danger"
                  type="button"
                  @click="deleteApi(apiForm)"
                >删除该配置</button>
              </div>
            </form>
          </section>
        </div>

        <DatabaseMaintenancePanel
          v-else-if="adminView === 'database'"
          @unauthorized="onDatabaseUnauthorized"
          @fields-changed="loadCustomFields"
        />

        <section v-else-if="adminView === 'knowledge'" class="knowledge-panel panel">
          <div class="panel-head">
            <div class="panel-title">知识库索引</div>
            <div class="panel-head-actions">
              <button class="btn btn-ghost btn-sm" type="button" @click="adminView = 'apis'">API 配置</button>
              <button class="btn btn-ghost btn-sm" type="button" :disabled="knowledgeLoading" @click="loadKnowledge">
                {{ knowledgeLoading ? '加载中…' : '刷新' }}
              </button>
              <button class="btn btn-primary btn-sm" type="button" :disabled="knowledgeRebuilding" @click="rebuildKnowledge">
                {{ knowledgeRebuilding ? '重建中…' : '重建全部索引' }}
              </button>
            </div>
          </div>

          <div v-if="knowledgeError" class="knowledge-error">{{ knowledgeError }}</div>

          <div v-if="knowledge" class="knowledge-body">
            <div class="knowledge-cards">
              <div class="knowledge-card">
                <span class="label">向量模型</span>
                <strong>{{ knowledge.embeddingModel }}</strong>
                <span class="sub" :class="{ ok: knowledge.embeddingAvailable }">
                  {{ knowledge.embeddingAvailable ? '向量服务在线' : '向量服务未连接' }}
                </span>
              </div>
              <div class="knowledge-card">
                <span class="label">问答模型</span>
                <strong>{{ knowledge.llmModel }}</strong>
                <span class="sub" :class="{ ok: knowledge.llmConfigured }">
                  {{ knowledge.llmConfigured ? '已配置模型 API' : '未配置问答模型 API' }}
                </span>
              </div>
              <div class="knowledge-card">
                <span class="label">索引进度</span>
                <strong>{{ knowledge.readyCount }} / {{ knowledge.totalBillboards }}</strong>
                <span class="sub">待索引 {{ knowledge.pendingCount }} · 失败 {{ knowledge.errorCount }}</span>
              </div>
              <div class="knowledge-card">
                <span class="label">最近成功索引</span>
                <strong>{{ knowledge.lastIndexedAt ? fmtDateTime(knowledge.lastIndexedAt) : '—' }}</strong>
                <span class="sub">共 {{ knowledge.totalBillboards }} 块广告牌</span>
              </div>
            </div>

            <div v-if="!knowledge.embeddingAvailable" class="knowledge-hint">
              向量服务未连接：请先在左侧「API 管理」检查向量模型地址与模型是否可用，
              或运行 <code>rag-service\start-embedding.bat</code> 后再点击「重建全部索引」。
            </div>
            <div v-if="!knowledge.llmConfigured" class="knowledge-hint">
              未配置问答模型：请到左侧「API 管理」新增或启用检索生成模型；
              保存后智能选牌即可生成自然语言答案，未配置时只返回知识库检索结果。
            </div>

            <div class="knowledge-table-wrap">
              <table class="knowledge-table">
                <thead>
                  <tr>
                    <th>广告牌</th>
                    <th>编号</th>
                    <th>索引状态</th>
                    <th>模型</th>
                    <th>维度</th>
                    <th>最近索引</th>
                    <th>错误信息</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="d in knowledge.documents" :key="d.billboardId">
                    <td>{{ d.name }}</td>
                    <td>{{ d.code }}</td>
                    <td>
                      <span class="knowledge-status" :class="'ks-' + d.status.toLowerCase()">
                        {{ knowledgeStatusLabel(d.status) }}
                      </span>
                    </td>
                    <td>{{ d.model || '—' }}</td>
                    <td>{{ d.dimension || '—' }}</td>
                    <td>{{ d.indexedAt ? fmtDateTime(d.indexedAt) : '—' }}</td>
                    <td class="err">{{ d.errorMessage || '—' }}</td>
                  </tr>
                  <tr v-if="!knowledge.documents?.length">
                    <td colspan="7" class="knowledge-empty-cell">暂无广告牌数据</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-else-if="knowledgeLoading" class="knowledge-empty">正在加载知识库状态…</div>
          <div v-else class="knowledge-empty">暂无知识库状态，点击"刷新"重试。</div>
        </section>
      </div>
    </div>
  </div>
</template>
