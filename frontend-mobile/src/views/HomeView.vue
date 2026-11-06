<script setup>
/**
 * 前台展示页（公开，无需登录）：
 *   - 数据概览 + 多条件筛选（路段 / 状态 / 价格区间 / 关键词 / 排序）
 *   - 顶部视图切换："广告牌"看卡片列表，"地图定位"看整幅腾讯地图；
 *     概览与筛选对两个视图共用，切换后筛选条件保持不变
 *   - 卡片与详情里的"定位"按钮会切到地图视图并居中到该广告牌
 *   - 每 15 秒静默轮询，后台改动自动同步到前台，无需手动刷新
 *   - 智能选牌：基于广告牌知识库做向量检索，并调用云端大模型生成选牌建议
 *   - 左侧 dock "管理入口"：密码验证通过后跳转后台工作台
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { api, getSiteConfig } from '../api'
import { fmtPrice, statusInfo } from '../utils/format'
import { toast } from '../utils/toast'
import BillboardCard from '../components/BillboardCard.vue'
import TMapMap from '../components/TMapMap.vue'
import DetailModal from '../components/DetailModal.vue'
import QaSourceList from '../components/QaSourceList.vue'

const router = useRouter()

const siteName = ref('公路广告牌租赁定位平台')
const mapAk = ref('')
const cfgReady = ref(false)
const items = ref([])
/** 选中公路时，该公路全部广告牌（不受其它筛选影响，用于地图整体分布展示） */
const roadItems = ref([])
const roads = ref([])
const loading = ref(true)
const lastSync = ref(null)

/** 当前视图：list=广告牌列表，map=地图定位，qa=智能选牌 */
const view = ref('list')
/** 手机端筛选底部弹层 */
const mobileFiltersOpen = ref(false)

const filters = reactive({
  road: '',
  status: '',
  minPrice: '',
  maxPrice: '',
  keyword: '',
  sort: 'newest',
})

const detail = ref(null)
/** 数据库维护模块新增的扩展字段元数据（前台详情展示用） */
const customFields = ref([])

const mapRef = ref(null)

/* ---------------- 概览统计 ---------------- */

const availableCount = computed(
  () => items.value.filter((it) => it.status === 'AVAILABLE').length,
)
const rentedCount = computed(
  () => items.value.filter((it) => it.status === 'RENTED').length,
)
const roadCount = computed(() => roads.value.length)

const activeFilterCount = computed(() => {
  let count = 0
  if (filters.road) count += 1
  if (filters.status) count += 1
  if (filters.minPrice !== '') count += 1
  if (filters.maxPrice !== '') count += 1
  if (filters.keyword.trim()) count += 1
  return count
})

/** 地图数据源：选中公路时展示该公路全部广告牌，否则展示当前筛选结果。 */
const mapMarkers = computed(() => {
  if (!filters.road) return items.value
  return roadItems.value.length ? roadItems.value : items.value
})

const syncText = computed(() =>
  lastSync.value
    ? '最近同步 ' + lastSync.value.toLocaleTimeString('zh-CN', { hour12: false })
    : '正在同步…',
)

/** 当前视图对应的面包屑 / 页面标题 / 说明。 */
const viewLabel = computed(() => {
  if (view.value === 'qa') return '智能选牌'
  return view.value === 'map' ? '地图定位' : '广告牌资源'
})

const pageTitle = computed(() => {
  if (view.value === 'qa') return '智能选牌'
  return view.value === 'map' ? '广告牌地图定位' : '广告牌资源'
})

const pageDesc = computed(() => {
  if (view.value === 'qa') return '基于广告牌知识库的语义检索与云端大模型选牌'
  return view.value === 'map'
    ? '按地图分布查看广告牌位置与租赁状态'
    : '公路沿线广告牌资源、租赁报价与状态一览'
})

/* ---------------- 智能选牌 ---------------- */

const qaQuestion = ref('')
const qaLoading = ref(false)
const qaStreaming = ref(false)
const qaMessages = ref([])
const qaGuess = ref(null)
const qaModels = ref([])
const qaModel = ref('')
const qaImage = ref(null)
const qaImagePreviewUrl = ref('')
const qaImageInput = ref(null)
/** 已发送到对话里的图片预览 URL，重置对话时统一释放 */
const qaMessageImageUrls = []
const qaTextarea = ref(null)
const qaScrollRef = ref(null)
let qaAbortController = null
let qaScrollScheduled = false
let qaPinnedToBottom = true
let qaLastScrollTop = 0
let qaMessageSeq = 0
let qaGuessSeq = 0

/** 模型按 Markdown 输出；渲染后再经 DOMPurify 清洗，避免 XSS。 */
marked.use({ gfm: true, breaks: true })

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

function renderMarkdown(text) {
  if (!text) return ''
  const html = marked.parse(String(text), { async: false })
  const clean = DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
  if (!clean.includes('<table')) return clean
  const template = document.createElement('template')
  template.innerHTML = clean
  // 手机端把宽表格折叠成“每行一张卡片”，给每个单元格补上表头标签。
  template.content.querySelectorAll('table').forEach((table) => {
    const headers = [...table.querySelectorAll('thead th')].map((th) => th.textContent.trim())
    table.querySelectorAll('tbody tr').forEach((row) => {
      [...row.children].forEach((cell, index) => {
        if (headers[index]) cell.setAttribute('data-label', headers[index])
      })
    })
  })
  return template.innerHTML
}

const qaLastAssistant = computed(() => {
  for (let i = qaMessages.value.length - 1; i >= 0; i--) {
    const message = qaMessages.value[i]
    if (message.role === 'assistant') {
      return message
    }
  }
  return null
})

const qaLastVisual = computed(() => {
  const message = qaLastAssistant.value
  return message && message.mode !== 'chat' ? message.visualFeatures : null
})

/** 右侧参考栏只展示已完成回答，流式生成期间先留空，避免打断对话阅读。 */
const qaSideAnswer = computed(() => {
  if (qaLoading.value) return null
  for (let i = qaMessages.value.length - 1; i >= 0; i--) {
    const message = qaMessages.value[i]
    if (message.role === 'assistant' && !message.streaming && !message.error) {
      return message
    }
  }
  return null
})

const qaSideTitle = computed(() =>
  qaSideAnswer.value?.mode === 'chat' ? '普通对话' : '参考广告牌',
)

const qaModelLabel = computed(() => {
  const selected = qaModels.value.find((m) => m.id === qaModel.value)
  return selected?.label || qaLastAssistant.value?.llmModelLabel || '默认模型'
})

const qaExamples = ref([
  '3 万以内可租点位',
  '最便宜的可租点位',
  '适合汽车品牌的点位',
])

async function loadQaModels() {
  try {
    const list = await api.knowledgeModels()
    qaModels.value = list?.length
      ? list
      : [{ id: '', label: '默认模型', isDefault: true }]
  } catch (e) {
    qaModels.value = [{ id: '', label: '默认模型', isDefault: true }]
  }
  const selected = qaModels.value.find((m) => m.isDefault) || qaModels.value[0]
  qaModel.value = selected?.id || ''
}

async function loadQaSuggestionPrompts() {
  try {
    const list = await api.knowledgeSuggestions()
    if (Array.isArray(list) && list.length) {
      qaExamples.value = list.slice(0, 3)
    }
  } catch (e) {
    // 保留 3 条短兜底建议
  }
}

function openQaImagePicker() {
  if (qaLoading.value) return
  qaImageInput.value?.click()
}

function onImageSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    toast('仅支持 PNG 或 JPEG 图片', 'error')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    toast('图片不能超过 10MB', 'error')
    return
  }
  clearQaImage()
  qaImage.value = file
  qaImagePreviewUrl.value = URL.createObjectURL(file)
}

function clearQaImage() {
  if (qaImagePreviewUrl.value) {
    URL.revokeObjectURL(qaImagePreviewUrl.value)
  }
  qaImage.value = null
  qaImagePreviewUrl.value = ''
  if (qaImageInput.value) {
    qaImageInput.value.value = ''
  }
}

function revokeQaMessageImages() {
  while (qaMessageImageUrls.length) {
    URL.revokeObjectURL(qaMessageImageUrls.pop())
  }
}

/**
 * 默认跟随最新输出；用户主动向上翻页后暂停跟随，回到底部时自动恢复。
 */
function scheduleQaScroll(force = false) {
  if (force) qaPinnedToBottom = true
  if (!qaPinnedToBottom) return
  if (qaScrollScheduled) return
  qaScrollScheduled = true
  requestAnimationFrame(() => {
    qaScrollScheduled = false
    const el = qaScrollRef.value
    if (el && qaPinnedToBottom) {
      const behavior = el.style.scrollBehavior
      el.style.scrollBehavior = "auto"
      el.scrollTop = el.scrollHeight
      el.style.scrollBehavior = behavior
      qaLastScrollTop = el.scrollTop
    }
  })
}

function onQaScroll() {
  const el = qaScrollRef.value
  if (!el) return
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  // 用户上翻（滚轮、触摸或拖动滚动条）会让 scrollTop 减小；
  // 程序化贴底只会增大 scrollTop，内容增长本身不会误停跟随。
  if (el.scrollTop < qaLastScrollTop - 2) {
    qaPinnedToBottom = false
  } else if (distance < 40) {
    qaPinnedToBottom = true
  }
  qaLastScrollTop = el.scrollTop
}

function onQaWheel(event) {
  if (event.deltaY < 0) {
    qaPinnedToBottom = false
  }
}

let qaTouchY = 0

function onQaTouchStart(event) {
  qaTouchY = event.touches?.[0]?.clientY || 0
}

function onQaTouchMove(event) {
  const y = event.touches?.[0]?.clientY || 0
  if (y > qaTouchY) {
    qaPinnedToBottom = false
  }
  qaTouchY = y
}

async function askKnowledge() {
  const question = qaQuestion.value.trim()
  const image = qaImage.value
  if (qaLoading.value || (!question && !image)) return

  const history = qaMessages.value
    .filter((message) => message.content && !message.streaming)
    .slice(-10)
    .map((message) => ({ role: message.role, content: message.content }))

  qaAbortController?.abort()
  const controller = new AbortController()
  qaAbortController = controller
  qaLoading.value = true
  qaStreaming.value = false
  qaGuess.value = null

  const userText = question || (image ? '请分析这张图片并推荐相似广告牌' : '')
  const sentImageUrl = image ? URL.createObjectURL(image) : ''
  qaMessages.value.push({
    id: ++qaMessageSeq,
    role: 'user',
    content: userText,
    image: !!image,
    imageUrl: sentImageUrl,
    imageName: image?.name || '',
  })
  if (sentImageUrl) qaMessageImageUrls.push(sentImageUrl)
  const assistantId = ++qaMessageSeq
  qaMessages.value.push({
    id: assistantId,
    role: 'assistant',
    question: userText,
    mode: 'billboard',
    content: '',
    sources: [],
    streaming: true,
    llmConfigured: true,
    llmModel: '',
    llmModelLabel: '',
    embeddingModel: '',
    imageMatch: false,
    visualFeatures: null,
    error: '',
  })

  // 发送后立即清空对话框与待上传图片，避免同一问题被重复提交。
  qaQuestion.value = ''
  clearQaImage()
  scheduleQaScroll(true)
  

  const current = () => qaMessages.value.find((message) => message.id === assistantId)

  const handlers = {
    signal: controller.signal,
    model: qaModel.value || undefined,
    history,
    onMeta: (meta) => {
      const message = current()
      if (!message) return
      message.mode = meta?.mode || 'billboard'
      message.sources = meta?.sources || []
      message.llmConfigured = !!meta?.llmConfigured
      message.llmModel = meta?.llmModel || ''
      message.llmModelLabel = meta?.llmModelLabel || ''
      message.embeddingModel = meta?.embeddingModel || ''
      message.imageMatch = !!meta?.imageMatch
      message.visualFeatures = meta?.visualFeatures || null
      message.streaming = true
      qaStreaming.value = true
      scheduleQaScroll()
    },
    onDelta: (text) => {
      const message = current()
      if (!message) return
      message.content += text
      message.streaming = true
      qaStreaming.value = true
      scheduleQaScroll()
    },
    onDone: (data) => {
      const message = current()
      if (!message) return
      message.content = data?.answer ?? message.content
      message.sources = data?.sources ?? message.sources
      message.mode = data?.mode ?? message.mode
      message.llmConfigured = data?.llmConfigured ?? message.llmConfigured
      message.llmModel = data?.llmModel ?? message.llmModel
      message.llmModelLabel = data?.llmModelLabel ?? message.llmModelLabel
      message.streaming = false
      qaStreaming.value = false
      loadGuessYouLike()
      scheduleQaScroll()
    },
    onError: (err) => {
      const message = current()
      if (message) {
        message.error = err?.message || '智能选牌失败，请稍后重试'
        message.streaming = false
      }
      qaStreaming.value = false
    },
  }

  try {
    if (image) {
      await api.askImageStream(image, question, 5, handlers)
    } else {
      await api.askKnowledgeStream(question, 5, handlers)
    }
  } catch (e) {
    if (e?.name !== 'AbortError') {
      const message = current()
      if (message) {
        message.error = e.message || '智能选牌失败，请稍后重试'
        message.streaming = false
      }
    }
  } finally {
    if (qaAbortController === controller) {
      qaAbortController = null
      qaLoading.value = false
      qaStreaming.value = false
      
    }
  }
}

async function loadGuessYouLike() {
  const userTurns = qaMessages.value.filter(
    (message) => message.role === 'user' && message.content,
  ).length
  const hasBillboardContext = qaMessages.value.some(
    (message) => message.role === 'assistant' && message.mode !== 'chat' && message.sources?.length,
  )
  if (userTurns < 2 || !hasBillboardContext) {
    qaGuess.value = null
    return
  }
  const history = qaMessages.value
    .filter((message) => message.content && !message.streaming)
    .slice(-10)
    .map((message) => ({ role: message.role, content: message.content }))
  const lastAssistant = [...qaMessages.value]
    .reverse()
    .find((message) => message.role === 'assistant' && !message.streaming)
  const excludeIds = (lastAssistant?.sources || []).map((source) => source.id)
  const seq = ++qaGuessSeq
  try {
    const result = await api.guessYouLike(history, excludeIds)
    if (seq === qaGuessSeq) {
      qaGuess.value = result?.items?.length ? result : null
    }
  } catch (e) {
    if (seq === qaGuessSeq) {
      qaGuess.value = null
    }
  }
}

function resetConversation() {
  qaAbortController?.abort()
  qaAbortController = null
  revokeQaMessageImages()
  qaMessages.value = []
  qaGuess.value = null
  qaGuessSeq++
  qaQuestion.value = ''
  qaLoading.value = false
  qaStreaming.value = false
  clearQaImage()
  scheduleQaScroll(true)
  
}

function useQaExample(text) {
  qaQuestion.value = text
  askKnowledge()
}

/* ---------------- 数据加载 ---------------- */

/** 拉取列表；silent=true 用于轮询（不显示加载态、失败不弹提示）。 */
async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const params = { sort: filters.sort }
    if (filters.road) params.road = filters.road
    if (filters.status) params.status = filters.status
    if (filters.minPrice !== '') params.minPrice = filters.minPrice
    if (filters.maxPrice !== '') params.maxPrice = filters.maxPrice
    const kw = filters.keyword.trim()
    if (kw) params.keyword = kw

    items.value = await api.listBillboards(params)
    lastSync.value = new Date()
    loadRoads()
    // 用户主动改筛选条件 → 地图重新全览；15 秒轮询走 silent 分支，
    // 不碰地图，保留用户当前的缩放、平移与已打开的信息窗
    if (!silent) fitMap()
  } catch (e) {
    if (!silent) toast(e.message || '广告牌数据加载失败', 'error')
  } finally {
    loading.value = false
  }
}

/**
 * 选中公路时拉取该公路全部广告牌，供地图展示整条公路的分布；
 * 不受状态 / 价格 / 关键词等其它筛选影响。
 */
async function loadRoadDistribution() {
  if (!filters.road) {
    roadItems.value = []
    return
  }
  try {
    roadItems.value = await api.listBillboards({ road: filters.road, sort: 'newest' })
    await nextTick()
    if (view.value === 'map') fitMap()
  } catch (e) {
    roadItems.value = []
  }
}

/** 路段下拉数据源（失败静默，不影响主流程）。 */
async function loadRoads() {
  try {
    roads.value = await api.roads()
  } catch (e) {
    /* 忽略：路段下拉为空时仍可按关键词筛选 */
  }
}

function resetFilters() {
  filters.road = ''
  filters.status = ''
  filters.minPrice = ''
  filters.maxPrice = ''
  filters.keyword = ''
  filters.sort = 'newest'
}

/* ---------------- 筛选联动（关键词防抖） ---------------- */

let keywordTimer = null

watch(
  () => filters.keyword,
  () => {
    clearTimeout(keywordTimer)
    keywordTimer = setTimeout(() => load(), 350)
  },
)

watch(
  () => [filters.road, filters.status, filters.minPrice, filters.maxPrice, filters.sort],
  () => load(),
)

// 公路筛选变化时单独拉取该公路全部点位，供地图展示整条公路的分布
watch(
  () => filters.road,
  () => loadRoadDistribution(),
)

/* ---------------- 自动同步（15 秒轮询） ---------------- */

let pollTimer = null

function onVisibilityChange() {
  if (!document.hidden) load(true)
}

onMounted(async () => {
  try {
    const cfg = await getSiteConfig()
    if (cfg?.siteName) siteName.value = cfg.siteName
    mapAk.value = cfg?.mapAk || ''
  } catch (e) {
    toast('站点配置加载失败，地图可能无法显示', 'error')
  } finally {
    cfgReady.value = true
  }

  await loadQaModels()
  await loadQaSuggestionPrompts()
  await Promise.all([load(), loadCustomFields()])
  pollTimer = setInterval(() => {
    if (!document.hidden) load(true)
  }, 15000)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

/** 扩展字段元数据加载失败时静默降级：详情仍展示广告牌核心字段。 */
async function loadCustomFields() {
  try {
    customFields.value = await api.publicBillboardFields()
  } catch (e) {
    customFields.value = []
  }
}

onUnmounted(() => {
  clearInterval(pollTimer)
  clearTimeout(keywordTimer)
  qaAbortController?.abort()
  clearQaImage()
  revokeQaMessageImages()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

/* ---------------- 视图切换 ---------------- */

/** 站点配置已就绪但缺地图 Key：地图定位视图给出明确提示，而不是一片空白。 */
const mapUnavailable = computed(() => cfgReady.value && !mapAk.value)

// 两个视图高度差很大，切换后回到顶部，避免停留在长列表的底部
watch(view, async (next) => {
  mobileFiltersOpen.value = false
  window.scrollTo({ top: 0, behavior: 'smooth' })
})

/* ---------------- 详情与地图联动 ---------------- */

/** 全览全部点位（地图未挂载时静默跳过，等它挂载后自己会做首次全览）。 */
async function fitMap() {
  await nextTick()
  mapRef.value?.fitAll()
}

/** 打开详情：先用列表数据即时渲染，再拉取最新详情覆盖。 */
async function openDetail(item) {
  detail.value = item
  try {
    detail.value = await api.detail(item.id)
  } catch (e) {
    /* 详情拉取失败时保留列表数据，不打断浏览 */
  }
}

async function openDetailById(id) {
  const item = items.value.find((it) => it.id === id)
  if (item) {
    openDetail(item)
    return
  }
  // 智能选牌命中的广告牌可能不在当前筛选结果中，按需拉取详情
  try {
    detail.value = await api.detail(id)
  } catch (e) {
    toast('广告牌详情加载失败', 'error')
  }
}

/** 地图定位：切到"地图定位"视图并居中到该广告牌。 */
async function locate(item) {
  if (item.longitude == null || item.latitude == null) {
    toast('该广告牌暂未标注坐标', 'error')
    return
  }
  if (mapUnavailable.value) {
    toast('未配置腾讯地图 Key，无法定位', 'error')
    return
  }
  view.value = 'map'
  // 地图组件在该视图下才挂载，等 DOM 就绪后再定位；
  // 地图脚本仍在加载时组件会暂存这次请求，就绪后自动执行
  await nextTick()
  mapRef.value?.focusOn(item.longitude, item.latitude)
}

function locateFromModal(item) {
  detail.value = null
  locate(item)
}

/**
 * 手机端管理入口：直接进入后台页面。
 * 未登录时由 AdminView 在页面内展示密码表单，不再弹窗。
 */
function openAdmin() {
  router.push('/admin')
}
</script>

<template>
  <div class="oa-shell portal-shell" :class="{ 'mobile-qa-mode': view === 'qa' }">
    <!-- 左侧 dock：业务导航与系统入口 -->
    <aside class="oa-sidebar portal-sidebar">
      <div class="oa-sidebar-brand">
        <span class="oa-sidebar-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="4" width="16" height="9" rx="1" />
            <path d="M9 20l2-7M15 20l-2-7M7 20h4M13 20h4" />
          </svg>
        </span>
        <span class="oa-sidebar-brand-text">
          <strong>公路广告牌租赁平台</strong>
          <span>Billboard Leasing</span>
        </span>
      </div>

      <div class="oa-menu-label">广告牌业务</div>
      <nav class="oa-menu" role="tablist" aria-label="视图切换">
        <button
          class="oa-menu-item view-switch-btn"
          :class="{ active: view === 'list' }"
          type="button"
          role="tab"
          :aria-selected="view === 'list'"
          @click="view = 'list'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="4" width="18" height="11" rx="1" />
            <path d="M8 20l2-5M16 20l-2-5M6 20h4M14 20h4" />
          </svg>
          广告牌资源
        </button>
        <button
          class="oa-menu-item view-switch-btn"
          :class="{ active: view === 'map' }"
          type="button"
          role="tab"
          :aria-selected="view === 'map'"
          @click="view = 'map'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 21s7-6.1 7-11a7 7 0 1 0-14 0c0 4.9 7 11 7 11z" />
            <circle cx="12" cy="10" r="2.4" />
          </svg>
          地图定位
        </button>
      </nav>

      <div class="oa-menu-label">智能服务</div>
      <nav class="oa-menu">
        <button
          class="oa-menu-item"
          :class="{ active: view === 'qa' }"
          type="button"
          @click="view = 'qa'"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 11.5a8.4 8.4 0 0 1-8.5 8.5 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7A8.4 8.4 0 0 1 4 11.5 8.5 8.5 0 0 1 12.5 3h.5a8.5 8.5 0 0 1 8 8v.5z" />
            <path d="M8.5 11.5h.01M12 11.5h.01M15.5 11.5h.01" />
          </svg>
          智能选牌
        </button>
      </nav>

      <div class="oa-menu-label">系统管理</div>
      <nav class="oa-menu">
        <button class="oa-menu-item" type="button" @click="openAdmin">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="5" y="10" width="14" height="10" rx="1" />
            <path d="M8 10V7a4 4 0 0 1 8 0v3" />
          </svg>
          管理入口
        </button>
      </nav>

      <div class="oa-sidebar-status">
        <span class="status-dot"></span>
        <div>
          <strong>系统运行正常</strong>
          <span>数据每 15 秒自动同步</span>
        </div>
      </div>
      <div class="oa-sidebar-foot">{{ siteName }} · v1.0.0</div>
    </aside>

    <div class="oa-main">
      <header class="portal-topbar">
        <div class="portal-topbar-inner">
          <div class="portal-mobile-brand">
            <span class="portal-mobile-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="4" y="4" width="16" height="9" rx="1" />
                <path d="M9 20l2-7M15 20l-2-7M7 20h4M13 20h4" />
              </svg>
            </span>
            <strong>广告牌租赁</strong>
          </div>
          <div class="oa-crumb">首页 <span>/</span> <b>{{ viewLabel }}</b></div>
          <div class="portal-topbar-right">
            <span class="sync-dot"></span>
            <span>{{ syncText }}</span>
            <span class="portal-topbar-sep">|</span>
            <span>每 15 秒自动同步</span>
          </div>
        </div>
      </header>

      <main class="page" :class="{ 'map-view': view === 'map', 'qa-view': view === 'qa' }">
        <div class="page-head" v-if="view !== 'qa'">
          <div>
            <h1 class="page-title">{{ pageTitle }}</h1>
            <p class="page-desc">{{ pageDesc }}</p>
          </div>
        </div>

      <!-- 手机端概览条 -->
      <div v-if="view !== 'qa'" class="mobile-summary">
        <span><b>{{ items.length }}</b> 块广告牌</span>
        <span><b>{{ availableCount }}</b> 可租</span>
        <span><b>{{ rentedCount }}</b> 已租</span>
        <span><b>{{ roadCount }}</b> 条公路</span>
      </div>

      <!-- 数据概览 -->
      <section v-if="view !== 'qa'" class="stats">
        <div class="stat-card">
          <div class="stat-icon stat-icon-blue" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="4" width="18" height="11" rx="1" />
              <path d="M8 20l2-5M16 20l-2-5M6 20h4M14 20h4" />
            </svg>
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ items.length }}</div>
            <div class="stat-label">当前列表广告牌</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon stat-icon-green" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="8" />
              <path d="M8.5 12.2l2.4 2.4 4.6-5" />
            </svg>
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ availableCount }}</div>
            <div class="stat-label">可租赁</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon stat-icon-red" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M7 4h10a1 1 0 0 1 1 1v15l-6-3-6 3V5a1 1 0 0 1 1-1z" />
            </svg>
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ rentedCount }}</div>
            <div class="stat-label">已租赁</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon stat-icon-amber" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 20L10 4M20 20L14 4M12 6v3M12 12v3M12 18v2" />
            </svg>
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ roadCount }}</div>
            <div class="stat-label">覆盖公路数</div>
          </div>
        </div>
      </section>

      <!-- 手机筛选弹层遮罩 -->
      <div
        v-if="mobileFiltersOpen"
        class="mobile-sheet-backdrop"
        aria-hidden="true"
        @click="mobileFiltersOpen = false"
      ></div>

      <!-- 筛选栏：桌面常驻，手机端变为底部弹层 -->
      <section
        v-if="view !== 'qa'"
        class="filters panel"
        :class="{
          'filters-open': mobileFiltersOpen,
          'map-filter-open': mobileFiltersOpen && view === 'map'
        }"
      >
        <div class="panel-head">
          <div class="panel-title">查询条件</div>
          <div class="panel-head-actions">
            <span class="auto-query-tip">条件变更后自动查询</span>
            <button class="btn btn-ghost btn-sm" type="button" @click="resetFilters">重置筛选</button>
            <button
              class="mobile-sheet-close"
              type="button"
              aria-label="关闭筛选"
              @click="mobileFiltersOpen = false"
            >×</button>
          </div>
        </div>
        <div class="filters-body">
          <div class="filters-row">
            <div class="field">
              <label for="f-road">所在公路</label>
              <select id="f-road" v-model="filters.road">
                <option value="">全部公路</option>
                <option v-for="r in roads" :key="r" :value="r">{{ r }}</option>
              </select>
            </div>

            <div class="field">
              <label for="f-status">租赁状态</label>
              <select id="f-status" v-model="filters.status">
                <option value="">全部状态</option>
                <option value="AVAILABLE">可租赁</option>
                <option value="RESERVED">已预定</option>
                <option value="RENTED">已租赁</option>
                <option value="PAUSED">暂停出租</option>
              </select>
            </div>

            <div class="field">
              <label for="f-min">最低价（元）</label>
              <input id="f-min" v-model="filters.minPrice" type="number" min="0" placeholder="不限">
            </div>

            <div class="field">
              <label for="f-max">最高价（元）</label>
              <input id="f-max" v-model="filters.maxPrice" type="number" min="0" placeholder="不限">
            </div>

            <div class="field field-grow">
              <label for="f-kw">关键词</label>
              <input
                id="f-kw"
                v-model="filters.keyword"
                type="search"
                placeholder="搜索名称 / 编号 / 路段 / 地址"
              >
            </div>

            <div class="field field-grow">
              <label for="f-sort">排序</label>
              <select id="f-sort" v-model="filters.sort">
                <option value="newest">最新发布</option>
                <option value="priceAsc">价格从低到高</option>
                <option value="priceDesc">价格从高到低</option>
              </select>
            </div>
          </div>

          <div class="filters-meta">
            <span>共 <strong>{{ items.length }}</strong> 块广告牌</span>
            <span class="filters-tip">数据来源：后台管理端</span>
          </div>
        </div>
        <div class="mobile-sheet-actions">
          <button class="btn btn-ghost" type="button" @click="resetFilters">重置</button>
          <button class="btn btn-primary" type="button" @click="mobileFiltersOpen = false">完成</button>
        </div>
      </section>

      <!-- 视图一：广告牌列表 -->
      <section v-if="view === 'list'" class="panel list-panel">
        <div class="panel-head">
          <div class="panel-title">广告牌列表</div>
          <span class="panel-hint">点击卡片查看完整详情</span>
          <button class="mobile-filter-trigger" type="button" @click="mobileFiltersOpen = true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M4 6h16M7 12h10M10 18h4" />
            </svg>
            筛选
            <span v-if="activeFilterCount" class="mobile-filter-count">{{ activeFilterCount }}</span>
          </button>
        </div>

        <div v-if="loading" class="empty-state">
          <span class="empty-icon">…</span>
          <span>正在加载广告牌数据</span>
        </div>

        <div v-else-if="!items.length" class="empty-state">
          <span class="empty-icon">—</span>
          <span>没有找到符合条件的广告牌</span>
          <button class="btn btn-ghost" type="button" @click="resetFilters">清空筛选条件</button>
        </div>

        <div v-else class="card-grid">
          <BillboardCard
            v-for="it in items"
            :key="it.id"
            :item="it"
            @detail="openDetail"
            @locate="locate"
          />
        </div>
      </section>

      <!-- 视图二：地图定位（整幅铺开，图钉随上方筛选条件变化） -->
      <section v-else-if="view === 'map'" class="map-panel">
        <div class="map-head">
          <span class="map-title">地图定位</span>
          <button class="mobile-filter-trigger" type="button" @click="mobileFiltersOpen = true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M4 6h16M7 12h10M10 18h4" />
            </svg>
            筛选
            <span v-if="activeFilterCount" class="mobile-filter-count">{{ activeFilterCount }}</span>
          </button>
          <span class="map-tip">
            <template v-if="filters.road">
              <span class="map-road-chip">{{ filters.road }}</span>
              全部 {{ mapMarkers.length }} 块广告牌 · 已高亮该公路路段
            </template>
            <template v-else>点击图钉查看该广告牌位置</template>
          </span>
        </div>
        <TMapMap
          v-if="mapAk && !(mobileFiltersOpen && view === 'map') && !detail"
          ref="mapRef"
          class="map-container"
          :ak="mapAk"
          :markers="mapMarkers"
          :highlight-road="!!filters.road"
          :road-name="filters.road"
          @open-detail="openDetailById"
          @map-error="toast('地图加载失败，请检查网络后刷新页面', 'error')"
        />
        <div v-else class="map-error">
          {{ mapUnavailable ? '未配置腾讯地图 Key，无法显示地图定位' : '地图配置加载中…' }}
        </div>
      </section>

      <!-- 视图三：智能选牌 -->
      <section v-else class="panel qa-panel">
        <div class="panel-head qa-panel-head">
          <div class="qa-title-wrap">
            <button class="qa-mobile-back" type="button" aria-label="返回广告牌资源" @click="view = 'list'">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M15 5l-7 7 7 7" />
              </svg>
            </button>
            <div class="panel-title">智能选牌</div>
          </div>
          <div class="qa-head-actions">
            <span class="panel-hint qa-panel-hint">
              <span class="qa-panel-role">小金桥 · 高级营销推荐员</span>
              <span class="qa-panel-model">{{ qaModelLabel }}</span>
              <template v-if="qaLastVisual"> · 已比对 {{ qaLastVisual.comparedCount }} 张图片</template>
            </span>
            <button type="button" class="qa-new-chat" :disabled="qaLoading" @click="resetConversation">
              新对话
            </button>
          </div>
        </div>
        <div class="qa-body">
          <div class="qa-main">
            <div
              ref="qaScrollRef"
              class="qa-scroll"
              :class="{ 'qa-empty': !qaMessages.length && !qaLoading }"
              @scroll="onQaScroll"
              @wheel="onQaWheel"
              @touchstart.passive="onQaTouchStart"
              @touchmove.passive="onQaTouchMove"
            >
            <div v-if="!qaMessages.length && !qaLoading" class="qa-intro">
              <h2>我是小金桥，你的广告牌营销推荐员</h2>
              <p>我是高级公路广告牌营销推荐员，可以按预算、路段、受众和品牌调性帮你选牌；也可以上传参考图片，我会识别图中文字和画面，再结合广告牌资源给出建议。支持连续追问。</p>
              <div class="qa-starters">
                <span class="qa-starters-label">你可以这样问</span>
                <div class="qa-starters-grid">
                  <button
                    v-for="ex in qaExamples"
                    :key="ex"
                    class="qa-starter"
                    type="button"
                    :disabled="qaLoading"
                    @click="useQaExample(ex)"
                  >{{ ex }}</button>
                </div>
              </div>
            </div>

            <div class="qa-thread">
              <div
                v-for="msg in qaMessages"
                :key="msg.id"
                class="qa-message"
                :class="msg.role"
              >
                <template v-if="msg.role === 'user'">
                  <div class="qa-user-row">
                    <div class="qa-user-bubble">
                      <img
                        v-if="msg.imageUrl"
                        :src="msg.imageUrl"
                        :alt="msg.imageName || '用户上传的图片'"
                        class="qa-user-image"
                      >
                      <span v-if="msg.content">{{ msg.content }}</span>
                      <span v-if="msg.image" class="qa-user-image-tag">含图片</span>
                    </div>
                  </div>
                </template>
                <template v-else>
                  <div class="qa-assistant-block">
                    <div class="qa-assistant-head">
                      <span class="qa-assistant-avatar">金</span>
                      <span class="qa-assistant-name">小金桥</span>
                      <span class="qa-assistant-role">高级营销推荐员</span>
                      <span v-if="msg.mode === 'chat'" class="qa-assistant-model">普通对话</span>
                      <span v-if="msg.llmModelLabel" class="qa-assistant-model">{{ msg.llmModelLabel }}</span>
                    </div>

                    <div v-if="msg.mode !== 'chat' && msg.imageMatch && msg.visualFeatures" class="qa-image-analysis">
                      <div class="qa-image-analysis-title">本地视觉特征</div>
                      <div class="qa-image-analysis-meta">
                        {{ msg.visualFeatures.orientation }} · 主色
                        {{ (msg.visualFeatures.dominantColors || []).join(' / ') }}
                        · 亮度{{ msg.visualFeatures.brightness }}
                        · {{ msg.visualFeatures.colorfulness }}
                        · 已比对 {{ msg.visualFeatures.comparedCount }} 张广告牌图片
                      </div>
                    </div>

                    <div v-if="!msg.llmConfigured && msg.content" class="qa-notice">
                      未配置可用的问答模型，以下为知识库检索结果；可在后台「API 管理」中启用检索生成线路。
                    </div>

                    <div v-if="msg.error" class="qa-error">{{ msg.error }}</div>

                    <div
                      v-if="msg.content"
                      class="qa-answer markdown-body"
                      :class="{ 'is-streaming': msg.streaming }"
                      v-html="renderMarkdown(msg.content)"
                    ></div>
                    <div v-else-if="msg.streaming" class="qa-message-pending">
                      正在检索广告牌知识库并生成回答…
                    </div>

                    <QaSourceList
                      v-if="msg.sources?.length"
                      class="qa-sources qa-sources-inline"
                      :sources="msg.sources"
                      show-title
                      @select="openDetailById"
                    />
                  </div>
                </template>
              </div>
            </div>
          </div>

          <div class="qa-composer">
            <div class="qa-composer-top">
              <button
                type="button"
                class="qa-tool-btn"
                :disabled="qaLoading"
                @click="openQaImagePicker"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="4" width="18" height="16" rx="2" />
                  <circle cx="8.5" cy="9.5" r="1.5" />
                  <path d="m21 15-5-5L5 21" />
                </svg>
                {{ qaImage ? '更换参考图片' : '上传图片选牌' }}
              </button>
              <input
                ref="qaImageInput"
                type="file"
                accept="image/png,image/jpeg"
                hidden
                @change="onImageSelected"
              >
              <label class="qa-model-picker">
                <span>模型</span>
                <select v-model="qaModel" :disabled="qaLoading || !qaModels.length">
                  <option v-for="m in qaModels" :key="m.id" :value="m.id">{{ m.label }}</option>
                </select>
              </label>
            </div>

            <div v-if="qaImage" class="qa-image-preview">
              <img :src="qaImagePreviewUrl" alt="待比对图片">
              <div class="qa-image-preview-info">
                <span class="qa-image-preview-name">{{ qaImage.name }}</span>
                <span class="qa-image-preview-hint">将识别图片文字与画面，并比对广告牌图片</span>
              </div>
              <button type="button" class="qa-image-remove" :disabled="qaLoading" @click="clearQaImage">移除</button>
            </div>

            <form class="qa-form" @submit.prevent="askKnowledge">
              <textarea
                ref="qaTextarea"
                v-model="qaQuestion"
                maxlength="500"
                rows="2"
                :placeholder="qaImage ? '可选：补充选牌需求，例如：预算 3 万以内、偏蓝色调' : '请输入选牌需求，例如：月租 3 万以内的可租赁广告牌'"
                @keydown.enter.exact.prevent="askKnowledge"
              ></textarea>
              <button
                class="btn btn-primary"
                type="submit"
                :disabled="qaLoading || (!qaQuestion.trim() && !qaImage)"
              >
                {{ qaStreaming ? '生成中…' : (qaLoading ? '分析中…' : '选牌') }}
              </button>
            </form>
          </div>
          </div>
          <aside class="qa-side">
            <div class="qa-side-head">
              <div class="qa-side-title">
                {{ qaSideTitle }}
                <span v-if="qaSideAnswer?.mode !== 'chat' && qaSideAnswer?.sources?.length">{{ qaSideAnswer.sources.length }}</span>
              </div>
              <span class="qa-side-hint">回答完成后更新</span>
            </div>

            <div v-if="qaLoading" class="qa-side-updating">
              正在生成新回答…
            </div>
            <template v-else-if="qaSideAnswer">
              <div v-if="qaSideAnswer.mode === 'chat'" class="qa-side-empty">
                本轮为普通对话，未推荐广告牌。
              </div>
              <template v-else>
                <div v-if="qaSideAnswer.question" class="qa-side-question">
                  针对：{{ qaSideAnswer.question }}
                </div>
                <QaSourceList
                  v-if="qaSideAnswer.sources?.length"
                  :sources="qaSideAnswer.sources"
                  @select="openDetailById"
                />
                <div v-else class="qa-side-empty">本轮回答没有检索到参考广告牌。</div>
              </template>
            </template>
            <div v-else class="qa-side-empty">
              发送问题后，这里会显示该轮回答引用的广告牌。
            </div>

            <div v-if="qaGuess && !qaLoading" class="qa-guess">
              <div class="qa-guess-head">
                <div class="qa-guess-title">猜你喜欢</div>
                <span class="qa-guess-badge">基于最近对话</span>
              </div>
              <div v-if="qaGuess.summary" class="qa-guess-summary">{{ qaGuess.summary }}</div>
              <QaSourceList
                v-if="qaGuess.items?.length"
                :sources="qaGuess.items"
                @select="openDetailById"
              />
              <div v-else class="qa-side-empty">暂时没有更多符合偏好的点位。</div>
            </div>
          </aside>
        </div>
      </section>
      </main>

      <footer class="footer" v-if="view !== 'qa'">
        <div class="footer-inner">
          <span>{{ siteName }}</span>
          <span>数据由后台管理端维护 · 前台每 15 秒自动同步</span>
        </div>
      </footer>
    </div>
    <!-- /oa-main -->

    <!-- 手机底部主导航 -->
    <nav class="mobile-tabbar" aria-label="主导航">
      <button
        class="mobile-tab"
        :class="{ active: view === 'list' }"
        type="button"
        @click="view = 'list'"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <rect x="3" y="4" width="18" height="11" rx="1" />
          <path d="M8 20l2-5M16 20l-2-5M6 20h4M14 20h4" />
        </svg>
        <span>广告牌</span>
      </button>
      <button
        class="mobile-tab"
        :class="{ active: view === 'map' }"
        type="button"
        @click="view = 'map'"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 21s7-6.1 7-11a7 7 0 1 0-14 0c0 4.9 7 11 7 11z" />
          <circle cx="12" cy="10" r="2.4" />
        </svg>
        <span>地图</span>
      </button>
      <button
        class="mobile-tab"
        :class="{ active: view === 'qa' }"
        type="button"
        @click="view = 'qa'"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M21 11.5a8.4 8.4 0 0 1-8.5 8.5 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7A8.4 8.4 0 0 1 4 11.5 8.5 8.5 0 0 1 12.5 3h.5a8.5 8.5 0 0 1 8 8v.5z" />
          <path d="M8.5 11.5h.01M12 11.5h.01M15.5 11.5h.01" />
        </svg>
        <span>选牌</span>
      </button>
      <button class="mobile-tab" type="button" @click="openAdmin">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <circle cx="12" cy="8" r="3.2" />
          <path d="M5.5 20a6.5 6.5 0 0 1 13 0" />
        </svg>
        <span>管理</span>
      </button>
    </nav>

    <!-- 详情弹窗 -->
    <DetailModal
      v-if="detail"
      :item="detail"
      :fields="customFields"
      @close="detail = null"
      @locate="locateFromModal"
    />

  </div>
</template>
