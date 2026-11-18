<script setup>
/**
 * 预定与合同工作台：
 *   - 从广告牌列表选中一块牌发起预定，自动带出广告牌关键信息；
 *   - 预定列表 / 合同档案双视图；
 *   - 生成合同、复制乙方签署链接、预览/下载 Word、上传线下回签件、作废/结束。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api, getToken } from '../api'
import { toast } from '../utils/toast'
import { renderAsync } from 'docx-preview'
import QRCode from 'qrcode'

const props = defineProps({
  seed: { type: Object, default: null },
})
const emit = defineEmits(['unauthorized', 'clear-seed'])

const tab = ref('reservations')
const loading = ref(false)
const reservations = ref([])
const contracts = ref([])
const billboards = ref([])
const templates = ref([])
const filters = reactive({ keyword: '', status: '' })
const contractFilters = reactive({ keyword: '', status: '' })

const RES_STATUS = {
  CONFIRMED: { label: '已预定', cls: 'is-pending' },
  SIGNED: { label: '已签约', cls: 'is-signed' },
  COMPLETED: { label: '已完成', cls: 'is-done' },
  CANCELLED: { label: '已取消', cls: 'is-cancelled' },
}
const CONTRACT_STATUS = {
  PENDING_SIGN: { label: '待签署', cls: 'is-pending' },
  SIGNED: { label: '已签署', cls: 'is-signed' },
  COMPLETED: { label: '已完成', cls: 'is-done' },
  VOID: { label: '已作废', cls: 'is-cancelled' },
}
const FILE_TYPE = {
  TEMPLATE_COPY: '模板副本',
  GENERATED: '生成草稿',
  SIGNATURE: '乙方签名',
  FINAL: '签署终稿',
  UPLOADED: '回签上传件',
}
const EVENT_ACTION = {
  RESERVATION_CREATED: '创建预定',
  RESERVATION_UPDATED: '修改预定',
  RESERVATION_CANCELLED: '取消预定',
  CONTRACT_GENERATED: '生成合同',
  CONTRACT_SIGNED: '乙方签署',
  CONTRACT_UPLOADED: '上传回签件',
  CONTRACT_VOIDED: '作废合同',
  CONTRACT_COMPLETED: '结束合同',
  TOKEN_REFRESHED: '刷新签署链接',
}

const stats = computed(() => ({
  total: reservations.value.length,
  pending: reservations.value.filter((r) => r.status === 'CONFIRMED').length,
  signed: reservations.value.filter((r) => r.status === 'SIGNED').length,
  contracts: contracts.value.length,
}))

const availableBillboards = computed(() =>
  billboards.value.filter((b) => !b.deleted && (b.status === 'AVAILABLE' || b.id === Number(form.billboardId))),
)

/* ---------------- 加载 ---------------- */

function handleError(e, fallback) {
  if (e && e.status === 401) {
    emit('unauthorized')
    toast('登录已过期，请重新验证密码', 'error')
    return
  }
  toast((e && e.message) || fallback, 'error')
}

async function loadReservations() {
  try {
    reservations.value = await api.reservations(cleanParams(filters))
  } catch (e) {
    handleError(e, '预定列表加载失败')
  }
}

async function loadContracts() {
  try {
    contracts.value = await api.contracts(cleanParams(contractFilters))
  } catch (e) {
    handleError(e, '合同列表加载失败')
  }
}

async function loadAll() {
  loading.value = true
  try {
    const [billboardList, templateList] = await Promise.all([
      api.adminList({}),
      api.contractTemplates(),
    ])
    billboards.value = billboardList
    templates.value = templateList
    await Promise.all([loadReservations(), loadContracts()])
  } catch (e) {
    handleError(e, '预定与合同数据加载失败')
  } finally {
    loading.value = false
  }
}

function cleanParams(source) {
  const params = {}
  if (source.keyword && source.keyword.trim()) params.keyword = source.keyword.trim()
  if (source.status) params.status = source.status
  return params
}

let reservationSearchTimer = null
let contractSearchTimer = null
watch(() => filters.keyword, () => {
  clearTimeout(reservationSearchTimer)
  reservationSearchTimer = setTimeout(loadReservations, 350)
})
watch(() => filters.status, loadReservations)
watch(() => contractFilters.keyword, () => {
  clearTimeout(contractSearchTimer)
  contractSearchTimer = setTimeout(loadContracts, 350)
})
watch(() => contractFilters.status, loadContracts)

onMounted(loadAll)

onBeforeUnmount(() => {
  clearTimeout(reservationSearchTimer)
  clearTimeout(contractSearchTimer)
  revokePreviewUrls()
})

/* ---------------- 新建 / 编辑预定 ---------------- */

const formOpen = ref(false)
const saving = ref(false)
const editingId = ref(null)
const form = reactive(emptyForm())

function isoDate(date) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function emptyForm() {
  const start = new Date()
  start.setDate(start.getDate() + 7)
  const end = new Date(start)
  end.setFullYear(end.getFullYear() + 1)
  end.setDate(end.getDate() - 1)
  return {
    billboardId: '',
    tenantType: 'INDIVIDUAL',
    tenantName: '',
    tenantIdNumber: '',
    tenantAddress: '',
    tenantContact: '',
    tenantPhone: '',
    tenantEmail: '',
    adContent: '',
    startDate: isoDate(start),
    endDate: isoDate(end),
    totalAmount: '',
    depositAmount: '',
    paymentTerms: '一次性支付',
    specialTerms: '',
    remark: '',
  }
}

const isEditing = computed(() => editingId.value != null)
const selectedBillboard = computed(() =>
  billboards.value.find((b) => b.id === Number(form.billboardId)) || null,
)

function leaseMonthsBetween(start, end) {
  if (!start || !end) return 0
  const s = new Date(start + 'T00:00:00')
  const e = new Date(end + 'T00:00:00')
  if (Number.isNaN(s.getTime()) || Number.isNaN(e.getTime()) || e < s) return 0
  // 租期按“起止日均包含”折算：先取结束日次日，再按自然月边界计算；
  // 结束日早于起始日（月末场景）时少算一个月，避免 1 月 31 日→2 月 28 日被算成 2 个月。
  const exclusive = new Date(e)
  exclusive.setDate(exclusive.getDate() + 1)
  let months = (exclusive.getFullYear() - s.getFullYear()) * 12
    + (exclusive.getMonth() - s.getMonth())
  if (exclusive.getDate() < s.getDate()) months -= 1
  return Math.max(1, months)
}

const previewMonths = computed(() => leaseMonthsBetween(form.startDate, form.endDate))
const previewDuration = computed(() => {
  const m = previewMonths.value
  return m >= 12 && m % 12 === 0 ? `${m / 12}年` : `${m}个月`
})

function suggestedAmount() {
  const b = selectedBillboard.value
  if (!b || b.price == null) return ''
  const months = previewMonths.value
  if (!months) return ''
  const price = Number(b.price)
  const amount = b.priceUnit === '元/年'
    ? price * Math.max(1, Math.ceil(months / 12))
    : price * months
  return amount.toFixed(2)
}

function applySuggestion(force = false) {
  if (isEditing.value) return
  if (force || form.totalAmount === '') {
    form.totalAmount = suggestedAmount()
  }
}

watch(
  () => [form.billboardId, form.startDate, form.endDate],
  () => applySuggestion(false),
)

function openCreate(billboard = null) {
  Object.assign(form, emptyForm())
  editingId.value = null
  if (billboard) form.billboardId = billboard.id
  formOpen.value = true
  nextTick(() => applySuggestion(true))
}

function openEdit(reservation) {
  Object.assign(form, {
    billboardId: reservation.billboardId,
    tenantType: reservation.tenantType,
    tenantName: reservation.tenantName || '',
    tenantIdNumber: reservation.tenantIdNumber || '',
    tenantAddress: reservation.tenantAddress || '',
    tenantContact: reservation.tenantContact || '',
    tenantPhone: reservation.tenantPhone || '',
    tenantEmail: reservation.tenantEmail || '',
    adContent: reservation.adContent || '',
    startDate: reservation.startDate,
    endDate: reservation.endDate,
    totalAmount: reservation.totalAmount,
    depositAmount: reservation.depositAmount,
    paymentTerms: reservation.paymentTerms || '',
    specialTerms: reservation.specialTerms || '',
    remark: reservation.remark || '',
  })
  editingId.value = reservation.id
  formOpen.value = true
}

async function saveReservation() {
  if (!form.billboardId) {
    toast('请选择广告牌', 'error')
    return
  }
  if (!form.tenantName.trim()) {
    toast('请填写乙方姓名或公司名称', 'error')
    return
  }
  if (!form.startDate || !form.endDate) {
    toast('请选择投放起止日期', 'error')
    return
  }
  if (new Date(form.endDate) < new Date(form.startDate)) {
    toast('投放结束日期不能早于开始日期', 'error')
    return
  }
  saving.value = true
  try {
    const body = {
      billboardId: Number(form.billboardId),
      tenantType: form.tenantType,
      tenantName: form.tenantName.trim(),
      tenantIdNumber: form.tenantIdNumber,
      tenantAddress: form.tenantAddress,
      tenantContact: form.tenantContact,
      tenantPhone: form.tenantPhone,
      tenantEmail: form.tenantEmail,
      adContent: form.adContent,
      startDate: form.startDate,
      endDate: form.endDate,
      totalAmount: form.totalAmount === '' ? null : Number(form.totalAmount),
      depositAmount: form.depositAmount === '' ? null : Number(form.depositAmount),
      paymentTerms: form.paymentTerms,
      specialTerms: form.specialTerms,
      remark: form.remark,
    }
    if (isEditing.value) {
      await api.updateReservation(editingId.value, body)
      toast('预定信息已更新')
    } else {
      await api.createReservation(body)
      toast('预定已创建，广告牌已锁定为「已预定」')
    }
    formOpen.value = false
    await Promise.all([loadReservations(), loadContracts(), loadBillboards()])
  } catch (e) {
    handleError(e, '预定保存失败')
  } finally {
    saving.value = false
  }
}

async function loadBillboards() {
  try {
    billboards.value = await api.adminList({})
  } catch (e) {
    handleError(e, '广告牌数据刷新失败')
  }
}

/* ---------------- 外部广告牌行入口 ---------------- */

watch(() => props.seed, (seed) => {
  if (!seed || !seed.id) return
  tab.value = 'reservations'
  openCreate(seed)
  emit('clear-seed')
}, { deep: false, immediate: true })

// 广告牌数据异步到达后，补一次金额建议（从广告牌行直接打开预定弹窗时可能早于列表加载）。
watch(billboards, () => {
  if (formOpen.value && !isEditing.value) applySuggestion(false)
})

/* ---------------- 详情 ---------------- */

const detailOpen = ref(false)
const detailLoading = ref(false)
const reservationDetail = ref(null)
const contractDetail = ref(null)
const selectedTemplateId = ref('')
const generating = ref(false)
const qrCanvas = ref(null)

const currentReservation = computed(() => contractDetail.value?.reservation || reservationDetail.value)
const currentContract = computed(() => contractDetail.value?.contract || null)
const detailFiles = computed(() => contractDetail.value?.files || [])
const detailEvents = computed(() => contractDetail.value?.events || [])
const detailTemplates = computed(() => contractDetail.value?.templates?.filter((t) => t.enabled) || templates.value.filter((t) => t.enabled))
const signingUrl = computed(() => {
  const path = currentContract.value?.signingPath
  return path ? window.location.origin + path : ''
})
const canGenerate = computed(() =>
  currentReservation.value?.status === 'CONFIRMED'
  && (!currentContract.value || currentContract.value.status === 'VOID'),
)
const canUploadSigned = computed(() =>
  currentReservation.value
  && ['CONFIRMED'].includes(currentReservation.value.status),
)

async function openReservation(id) {
  detailOpen.value = true
  detailLoading.value = true
  contractDetail.value = null
  reservationDetail.value = null
  try {
    reservationDetail.value = await api.reservation(id)
    if (reservationDetail.value.contract?.id) {
      contractDetail.value = await api.contract(reservationDetail.value.contract.id)
    }
    initTemplateSelection()
  } catch (e) {
    handleError(e, '预定详情加载失败')
  } finally {
    detailLoading.value = false
  }
  await nextTick()
  renderQr()
}

async function openContract(id) {
  detailOpen.value = true
  detailLoading.value = true
  contractDetail.value = null
  reservationDetail.value = null
  try {
    contractDetail.value = await api.contract(id)
    reservationDetail.value = contractDetail.value.reservation
    initTemplateSelection()
  } catch (e) {
    handleError(e, '合同详情加载失败')
  } finally {
    detailLoading.value = false
  }
  await nextTick()
  renderQr()
}

function initTemplateSelection() {
  if (selectedTemplateId.value) return
  const list = detailTemplates.value
  const preferred = list.find((t) => t.defaultTemplate) || list[0]
  selectedTemplateId.value = preferred ? preferred.id : ''
}

function closeDetail() {
  detailOpen.value = false
  contractDetail.value = null
  reservationDetail.value = null
  selectedTemplateId.value = ''
}

async function renderQr() {
  if (!qrCanvas.value || !signingUrl.value) return
  try {
    await QRCode.toCanvas(qrCanvas.value, signingUrl.value, {
      width: 152,
      margin: 1,
      color: { dark: '#12263f', light: '#ffffff' },
    })
  } catch (e) {
    // 二维码失败不影响链接复制与签署
  }
}

watch(signingUrl, () => nextTick(renderQr))

async function copySigningLink() {
  if (!signingUrl.value) return
  try {
    await navigator.clipboard.writeText(signingUrl.value)
    toast('签署链接已复制')
  } catch (e) {
    const input = document.createElement('textarea')
    input.value = signingUrl.value
    document.body.appendChild(input)
    input.select()
    try {
      document.execCommand('copy')
      toast('签署链接已复制')
    } catch (err) {
      toast('复制失败，请手动选中链接复制', 'error')
    } finally {
      document.body.removeChild(input)
    }
  }
}

async function generateContract() {
  if (!currentReservation.value) return
  generating.value = true
  try {
    const data = await api.generateContract(currentReservation.value.id, {
      templateId: selectedTemplateId.value ? Number(selectedTemplateId.value) : null,
    })
    contractDetail.value = data
    reservationDetail.value = data.reservation
    toast('合同已生成，可复制签署链接发给乙方')
    await Promise.all([loadReservations(), loadContracts()])
    await nextTick()
    renderQr()
  } catch (e) {
    handleError(e, '合同生成失败')
  } finally {
    generating.value = false
  }
}

async function refreshSigningLink() {
  if (!currentContract.value) return
  try {
    contractDetail.value = await api.refreshContractLink(currentContract.value.id)
    reservationDetail.value = contractDetail.value.reservation
    toast('签署链接已刷新，旧链接已失效')
    await nextTick()
    renderQr()
  } catch (e) {
    handleError(e, '刷新签署链接失败')
  }
}

async function completeContract() {
  if (!currentContract.value) return
  if (!window.confirm(`确认结束合同「${currentContract.value.contractNo}」？广告牌将恢复为可租赁。`)) return
  try {
    contractDetail.value = await api.completeContract(currentContract.value.id)
    reservationDetail.value = contractDetail.value.reservation
    toast('合同已结束，广告牌已释放')
    await Promise.all([loadReservations(), loadContracts(), loadBillboards()])
  } catch (e) {
    handleError(e, '结束合同失败')
  }
}

/* ---------------- 取消 / 作废原因 ---------------- */

const reasonOpen = ref(false)
const reasonTitle = ref('')
const reasonText = ref('')
const reasonAction = ref('cancel')
const reasonTargetId = ref(null)
const reasonSaving = ref(false)

function askCancel(reservation) {
  reasonAction.value = 'cancel'
  reasonTargetId.value = reservation.id
  reasonTitle.value = `取消预定 ${reservation.reservationNo}`
  reasonText.value = ''
  reasonOpen.value = true
}

function askVoid() {
  if (!currentContract.value) return
  reasonAction.value = 'void'
  reasonTargetId.value = currentContract.value.id
  reasonTitle.value = `作废合同 ${currentContract.value.contractNo}`
  reasonText.value = ''
  reasonOpen.value = true
}

async function submitReason() {
  if (!reasonText.value.trim()) {
    toast('请填写原因', 'error')
    return
  }
  reasonSaving.value = true
  try {
    if (reasonAction.value === 'cancel') {
      await api.cancelReservation(reasonTargetId.value, { reason: reasonText.value.trim() })
      toast('预定已取消，广告牌已释放')
      closeDetail()
    } else {
      contractDetail.value = await api.voidContract(reasonTargetId.value, { reason: reasonText.value.trim() })
      reservationDetail.value = contractDetail.value.reservation
      toast('合同已作废')
    }
    reasonOpen.value = false
    await Promise.all([loadReservations(), loadContracts(), loadBillboards()])
  } catch (e) {
    handleError(e, reasonAction.value === 'cancel' ? '取消预定失败' : '作废合同失败')
  } finally {
    reasonSaving.value = false
  }
}

/* ---------------- 后台上传线下已签合同 ---------------- */

const uploadOpen = ref(false)
const uploadSaving = ref(false)
const uploadForm = reactive({ signerName: '', file: null })
const uploadInput = ref(null)

function openUpload() {
  uploadForm.signerName = currentReservation.value?.tenantName || ''
  uploadForm.file = null
  uploadOpen.value = true
}

function onUploadFile(event) {
  uploadForm.file = event.target.files?.[0] || null
}

async function submitUpload() {
  if (!uploadForm.file) {
    toast('请选择已签署的合同文件', 'error')
    return
  }
  uploadSaving.value = true
  try {
    const fd = new FormData()
    fd.append('file', uploadForm.file)
    if (uploadForm.signerName.trim()) fd.append('signerName', uploadForm.signerName.trim())
    const data = await api.uploadSignedContract(currentReservation.value.id, fd)
    contractDetail.value = data
    reservationDetail.value = data.reservation
    uploadOpen.value = false
    toast('线下已签合同已归档，预定状态更新为已签约')
    await Promise.all([loadReservations(), loadContracts(), loadBillboards()])
    await nextTick()
    renderQr()
  } catch (e) {
    handleError(e, '上传已签合同失败')
  } finally {
    uploadSaving.value = false
  }
}

/* ---------------- 预览 / 下载 ---------------- */

const previewOpen = ref(false)
const previewLoading = ref(false)
const previewError = ref('')
const previewTitle = ref('')
const previewEl = ref(null)
const previewPdfUrl = ref('')
const previewImageUrl = ref('')
const previewBlobUrl = ref('')
let previewDocxUrl = ''

function revokePreviewUrls() {
  if (previewBlobUrl.value) {
    URL.revokeObjectURL(previewBlobUrl.value)
    previewBlobUrl.value = ''
  }
  if (previewPdfUrl.value) {
    URL.revokeObjectURL(previewPdfUrl.value)
    previewPdfUrl.value = ''
  }
  if (previewImageUrl.value) {
    URL.revokeObjectURL(previewImageUrl.value)
    previewImageUrl.value = ''
  }
  if (previewDocxUrl) {
    URL.revokeObjectURL(previewDocxUrl)
    previewDocxUrl = ''
  }
}

async function fetchBlob(url, withAuth = true) {
  const headers = withAuth ? { 'X-Admin-Token': getToken() } : {}
  const res = await fetch(url, { headers })
  if (!res.ok) {
    let message = `文件读取失败（HTTP ${res.status}）`
    try {
      const body = await res.json()
      message = body.message || message
    } catch (e) {
      // 非 JSON 错误
    }
    throw new Error(message)
  }
  return res.blob()
}

async function openPreview(url, filename, withAuth = true) {
  previewOpen.value = true
  previewLoading.value = true
  previewError.value = ''
  previewTitle.value = filename || '合同预览'
  revokePreviewUrls()
  try {
    const blob = await fetchBlob(url, withAuth)
    const ext = (filename || '').split('.').pop().toLowerCase()
    if (ext === 'pdf') {
      previewPdfUrl.value = URL.createObjectURL(blob)
    } else if (['png', 'jpg', 'jpeg'].includes(ext)) {
      previewImageUrl.value = URL.createObjectURL(blob)
    } else {
      previewBlobUrl.value = URL.createObjectURL(blob)
      await nextTick()
      if (previewEl.value) {
        previewEl.value.innerHTML = ''
        await renderAsync(blob, previewEl.value, null, {
          className: 'cw-docx',
          inWrapper: true,
          breakPages: true,
          ignoreWidth: false,
          ignoreHeight: false,
        })
      }
    }
  } catch (e) {
    previewError.value = e.message || '合同预览失败，请尝试下载后查看'
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  previewOpen.value = false
  revokePreviewUrls()
}

async function downloadAdminFile(url, filename) {
  try {
    const blob = await fetchBlob(url, true)
    saveBlob(blob, filename)
  } catch (e) {
    handleError(e, '文件下载失败')
  }
}

function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || 'contract'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 3000)
}

function previewCurrentContract() {
  if (!currentContract.value) return
  openPreview(
    `/api/admin/contracts/${currentContract.value.id}/download`,
    currentContract.value.currentFileName || `${currentContract.value.contractNo}.docx`,
  )
}

function previewContractRow(contract) {
  openPreview(
    `/api/admin/contracts/${contract.id}/download`,
    contract.currentFileName || `${contract.contractNo}.docx`,
  )
}

/* ---------------- 展示工具 ---------------- */

function money(value) {
  if (value == null || value === '') return '0.00'
  const n = Number(value)
  if (Number.isNaN(n)) return '0.00'
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtDate(value) {
  return value ? String(value).slice(0, 10) : '—'
}

function fmtTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function resBadge(status) {
  return RES_STATUS[status] || { label: status || '未知', cls: '' }
}

function contractBadge(status) {
  return CONTRACT_STATUS[status] || { label: status || '未知', cls: '' }
}

function tenantLabel(reservation) {
  if (!reservation) return '—'
  return `${reservation.tenantName}${reservation.tenantType === 'COMPANY' ? ' · 企业' : ' · 个人'}`
}

function canEditReservation(reservation) {
  return reservation.status === 'CONFIRMED' && (!reservation.contract || reservation.contract.status === 'VOID')
}
</script>

<template>
  <section class="contract-workspace">
    <div class="cw-head">
      <div class="cw-stats">
        <div class="cw-stat">
          <span class="cw-stat-label">预定总数</span>
          <strong>{{ stats.total }}</strong>
        </div>
        <div class="cw-stat">
          <span class="cw-stat-label">已预定待签</span>
          <strong class="is-amber">{{ stats.pending }}</strong>
        </div>
        <div class="cw-stat">
          <span class="cw-stat-label">已签约</span>
          <strong class="is-green">{{ stats.signed }}</strong>
        </div>
        <div class="cw-stat">
          <span class="cw-stat-label">合同档案</span>
          <strong>{{ stats.contracts }}</strong>
        </div>
      </div>
      <div class="cw-head-actions">
        <button class="btn btn-ghost" type="button" :disabled="loading" @click="loadAll">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
        <button class="btn btn-primary" type="button" @click="openCreate()">新增预定</button>
      </div>
    </div>

    <div class="cw-tabs">
      <button
        class="cw-tab"
        :class="{ active: tab === 'reservations' }"
        type="button"
        @click="tab = 'reservations'"
      >
        预定记录
      </button>
      <button
        class="cw-tab"
        :class="{ active: tab === 'contracts' }"
        type="button"
        @click="tab = 'contracts'"
      >
        合同档案
      </button>
      <span class="cw-tab-hint">选中广告牌点「预定」可自动带入关键信息</span>
    </div>

    <!-- 预定列表 -->
    <div v-if="tab === 'reservations'" class="cw-panel">
      <div class="cw-filters">
        <input v-model="filters.keyword" type="search" placeholder="搜索预定编号 / 乙方 / 广告牌编号">
        <select v-model="filters.status">
          <option value="">全部预定状态</option>
          <option value="CONFIRMED">已预定</option>
          <option value="SIGNED">已签约</option>
          <option value="COMPLETED">已完成</option>
          <option value="CANCELLED">已取消</option>
        </select>
      </div>

      <div class="cw-table-wrap">
        <table class="cw-table">
          <thead>
            <tr>
              <th>预定编号</th>
              <th>广告牌</th>
              <th>乙方</th>
              <th>投放期限</th>
              <th class="num">合同金额</th>
              <th>预定状态</th>
              <th>合同</th>
              <th class="actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading && !reservations.length">
              <td colspan="8" class="cw-empty">正在加载…</td>
            </tr>
            <tr v-else-if="!reservations.length">
              <td colspan="8" class="cw-empty">暂无预定记录。可在广告牌管理列表选择一块牌发起预定。</td>
            </tr>
            <tr v-for="r in reservations" :key="r.id" class="cw-row" @click="openReservation(r.id)">
              <td>
                <strong class="cw-code">{{ r.reservationNo }}</strong>
                <div class="cw-sub">{{ fmtTime(r.createdAt) }}</div>
              </td>
              <td>
                <div class="cw-cell-main">{{ r.billboardName }}</div>
                <div class="cw-sub">{{ r.billboardCode }} · {{ r.billboardRoad || '—' }}</div>
              </td>
              <td>
                <div class="cw-cell-main">{{ r.tenantName }}</div>
                <div class="cw-sub">{{ r.tenantTypeLabel }} · {{ r.tenantPhone || '未填电话' }}</div>
              </td>
              <td>
                <div>{{ fmtDate(r.startDate) }} 至 {{ fmtDate(r.endDate) }}</div>
                <div class="cw-sub">{{ r.leaseDuration }}</div>
              </td>
              <td class="num">¥{{ money(r.totalAmount) }}</td>
              <td><span class="cw-badge" :class="resBadge(r.status).cls">{{ r.statusLabel }}</span></td>
              <td>
                <template v-if="r.contract">
                  <div class="cw-cell-main">{{ r.contract.contractNo }}</div>
                  <div class="cw-sub">
                    <span class="cw-badge tiny" :class="contractBadge(r.contract.status).cls">
                      {{ r.contract.statusLabel }}
                    </span>
                    <span v-if="r.contract.canSign" class="cw-sign-hint">待乙方签署</span>
                  </div>
                </template>
                <span v-else class="cw-sub">未生成</span>
              </td>
              <td class="actions" @click.stop>
                <button class="link-btn" type="button" @click="openReservation(r.id)">详情</button>
                <button
                  v-if="canEditReservation(r)"
                  class="link-btn"
                  type="button"
                  @click="openEdit(r)"
                >编辑</button>
                <button
                  v-if="r.status === 'CONFIRMED'"
                  class="link-btn danger"
                  type="button"
                  @click="askCancel(r)"
                >取消</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 合同列表 -->
    <div v-else class="cw-panel">
      <div class="cw-filters">
        <input v-model="contractFilters.keyword" type="search" placeholder="搜索合同编号 / 乙方 / 广告牌">
        <select v-model="contractFilters.status">
          <option value="">全部合同状态</option>
          <option value="PENDING_SIGN">待签署</option>
          <option value="SIGNED">已签署</option>
          <option value="COMPLETED">已完成</option>
          <option value="VOID">已作废</option>
        </select>
      </div>

      <div class="cw-table-wrap">
        <table class="cw-table">
          <thead>
            <tr>
              <th>合同编号</th>
              <th>广告牌</th>
              <th>乙方</th>
              <th>来源</th>
              <th class="num">合同金额</th>
              <th>状态</th>
              <th>签署时间</th>
              <th class="actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading && !contracts.length">
              <td colspan="8" class="cw-empty">正在加载…</td>
            </tr>
            <tr v-else-if="!contracts.length">
              <td colspan="8" class="cw-empty">暂无合同档案。生成合同或上传线下已签合同后会出现在这里。</td>
            </tr>
            <tr v-for="c in contracts" :key="c.id" class="cw-row" @click="openContract(c.id)">
              <td>
                <strong class="cw-code">{{ c.contractNo }}</strong>
                <div class="cw-sub">{{ c.templateName || '线下回签' }}</div>
              </td>
              <td>
                <div class="cw-cell-main">{{ c.billboardName || '—' }}</div>
                <div class="cw-sub">{{ c.billboardCode || '—' }}</div>
              </td>
              <td>{{ c.tenantName || '—' }}</td>
              <td>{{ c.sourceLabel }}</td>
              <td class="num">¥{{ money(c.totalAmount) }}</td>
              <td><span class="cw-badge" :class="contractBadge(c.status).cls">{{ c.statusLabel }}</span></td>
              <td>{{ c.signedAt ? fmtTime(c.signedAt) : '—' }}</td>
              <td class="actions" @click.stop>
                <button class="link-btn" type="button" @click="openContract(c.id)">详情</button>
                <button class="link-btn" type="button" @click="previewContractRow(c)">预览</button>
                <button
                  class="link-btn"
                  type="button"
                  @click="downloadAdminFile(`/api/admin/contracts/${c.id}/download`, c.currentFileName || c.contractNo + '.docx')"
                >下载</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>

  <!-- 新建 / 编辑预定 -->
  <div v-if="formOpen" class="cw-overlay" @click.self="formOpen = false">
    <div class="cw-dialog cw-dialog-form">
      <header class="cw-dialog-head">
        <div>
          <h3>{{ isEditing ? '编辑预定' : '新增预定' }}</h3>
          <p>广告牌关键信息会自动带入合同模板；保存后该广告牌将锁定为「已预定」。</p>
        </div>
        <button class="cw-close" type="button" @click="formOpen = false">×</button>
      </header>

      <div class="cw-dialog-body">
        <section class="cw-form-section">
          <h4>广告牌信息</h4>
          <div class="cw-form-grid">
            <div class="form-field">
              <label>选择广告牌 <em>*</em></label>
              <select v-model="form.billboardId" :disabled="isEditing">
                <option value="">请选择可租赁广告牌</option>
                <option v-for="b in availableBillboards" :key="b.id" :value="b.id">
                  {{ b.code }} · {{ b.name }}（{{ b.statusLabel }}）
                </option>
              </select>
            </div>
            <div class="form-field">
              <label>租赁单价</label>
              <input
                :value="selectedBillboard ? `¥${money(selectedBillboard.price)} ${selectedBillboard.priceUnit || ''}` : '—'"
                readonly
              >
            </div>
          </div>

          <div v-if="selectedBillboard" class="cw-billboard-snapshot">
            <div><span>广告牌编号</span><strong>{{ selectedBillboard.code }}</strong></div>
            <div><span>所在公路</span><strong>{{ selectedBillboard.road || '—' }}</strong></div>
            <div><span>路段位置</span><strong>{{ selectedBillboard.section || '—' }}</strong></div>
            <div><span>尺寸 / 材质</span><strong>{{ selectedBillboard.size || '—' }} / {{ selectedBillboard.material || '—' }}</strong></div>
            <div><span>可视面积</span><strong>{{ selectedBillboard.visibleArea || '—' }}</strong></div>
            <div><span>报价说明</span><strong>{{ selectedBillboard.priceNote || '—' }}</strong></div>
          </div>
        </section>

        <section class="cw-form-section">
          <h4>乙方信息</h4>
          <div class="cw-form-grid">
            <div class="form-field">
              <label>主体类型</label>
              <select v-model="form.tenantType">
                <option value="INDIVIDUAL">个人</option>
                <option value="COMPANY">企业</option>
              </select>
            </div>
            <div class="form-field">
              <label>{{ form.tenantType === 'COMPANY' ? '公司名称' : '姓名' }} <em>*</em></label>
              <input v-model="form.tenantName" type="text" maxlength="200" placeholder="合同中的乙方名称">
            </div>
            <div class="form-field">
              <label>{{ form.tenantType === 'COMPANY' ? '统一社会信用代码' : '身份证号' }}</label>
              <input v-model="form.tenantIdNumber" type="text" maxlength="100">
            </div>
            <div class="form-field">
              <label>联系人</label>
              <input v-model="form.tenantContact" type="text" maxlength="100">
            </div>
            <div class="form-field">
              <label>联系电话</label>
              <input v-model="form.tenantPhone" type="text" maxlength="50">
            </div>
            <div class="form-field">
              <label>电子邮箱</label>
              <input v-model="form.tenantEmail" type="email" maxlength="200">
            </div>
            <div class="form-field span-2">
              <label>乙方地址</label>
              <input v-model="form.tenantAddress" type="text" maxlength="500">
            </div>
          </div>
        </section>

        <section class="cw-form-section">
          <h4>租赁与商务条款</h4>
          <div class="cw-form-grid">
            <div class="form-field">
              <label>投放开始日期 <em>*</em></label>
              <input v-model="form.startDate" type="date">
            </div>
            <div class="form-field">
              <label>投放结束日期 <em>*</em></label>
              <input v-model="form.endDate" type="date">
            </div>
            <div class="form-field">
              <label>租赁时长</label>
              <input :value="previewDuration" readonly>
            </div>
            <div class="form-field">
              <label>
                合同总金额（元）
                <button class="cw-inline-link" type="button" @click="form.totalAmount = suggestedAmount()">按单价计算</button>
              </label>
              <input v-model="form.totalAmount" type="number" min="0" step="0.01">
            </div>
            <div class="form-field">
              <label>定金金额（元）</label>
              <input v-model="form.depositAmount" type="number" min="0" step="0.01">
            </div>
            <div class="form-field">
              <label>付款方式</label>
              <input v-model="form.paymentTerms" type="text" maxlength="500" placeholder="如：签订后 7 日内支付 50%，投放前支付剩余 50%">
            </div>
            <div class="form-field span-2">
              <label>广告发布内容</label>
              <textarea v-model="form.adContent" rows="3" placeholder="广告画面主题、品牌名称、发布要求等"></textarea>
            </div>
            <div class="form-field span-2">
              <label>特殊约定</label>
              <textarea v-model="form.specialTerms" rows="3" placeholder="如：画面制作安装由乙方负责；含首次亮化电费等"></textarea>
            </div>
            <div class="form-field span-2">
              <label>内部备注</label>
              <input v-model="form.remark" type="text" maxlength="500" placeholder="仅后台可见，不写入合同">
            </div>
          </div>
        </section>
      </div>

      <footer class="cw-dialog-foot">
        <span class="cw-foot-hint">保存预定不会自动生成合同，生成后可复制签署链接给乙方。</span>
        <div>
          <button class="btn btn-ghost" type="button" @click="formOpen = false">取消</button>
          <button class="btn btn-primary" type="button" :disabled="saving" @click="saveReservation">
            {{ saving ? '保存中…' : (isEditing ? '保存修改' : '创建预定') }}
          </button>
        </div>
      </footer>
    </div>
  </div>

  <!-- 预定 / 合同详情 -->
  <div v-if="detailOpen" class="cw-overlay" @click.self="closeDetail">
    <div class="cw-dialog cw-dialog-detail">
      <header class="cw-dialog-head">
        <div>
          <h3>
            {{ currentContract ? currentContract.contractNo : (currentReservation?.reservationNo || '预定详情') }}
            <span v-if="currentContract" class="cw-badge" :class="contractBadge(currentContract.status).cls">
              {{ currentContract.statusLabel }}
            </span>
            <span v-else-if="currentReservation" class="cw-badge" :class="resBadge(currentReservation.status).cls">
              {{ currentReservation.statusLabel }}
            </span>
          </h3>
          <p v-if="currentReservation">
            {{ currentReservation.billboardName }} · {{ tenantLabel(currentReservation) }}
          </p>
        </div>
        <button class="cw-close" type="button" @click="closeDetail">×</button>
      </header>

      <div class="cw-dialog-body">
        <div v-if="detailLoading" class="cw-empty">正在加载详情…</div>

        <template v-else-if="currentReservation">
          <section class="cw-detail-section">
            <div class="cw-section-head">
              <h4>预定信息</h4>
              <span>{{ currentReservation.reservationNo }}</span>
            </div>
            <div class="cw-info-grid">
              <div><span>乙方</span><strong>{{ currentReservation.tenantName }}</strong></div>
              <div><span>主体类型</span><strong>{{ currentReservation.tenantTypeLabel }}</strong></div>
              <div><span>证件号码</span><strong>{{ currentReservation.tenantIdNumber || '—' }}</strong></div>
              <div><span>联系人 / 电话</span><strong>{{ currentReservation.tenantContact || '—' }} / {{ currentReservation.tenantPhone || '—' }}</strong></div>
              <div><span>投放期限</span><strong>{{ fmtDate(currentReservation.startDate) }} 至 {{ fmtDate(currentReservation.endDate) }}（{{ currentReservation.leaseDuration }}）</strong></div>
              <div><span>合同金额</span><strong>¥{{ money(currentReservation.totalAmount) }}</strong></div>
              <div><span>定金</span><strong>¥{{ money(currentReservation.depositAmount) }}</strong></div>
              <div><span>付款方式</span><strong>{{ currentReservation.paymentTerms || '—' }}</strong></div>
              <div class="span-2"><span>广告内容</span><strong>{{ currentReservation.adContent || '—' }}</strong></div>
              <div class="span-2"><span>特殊约定</span><strong>{{ currentReservation.specialTerms || '—' }}</strong></div>
              <div v-if="currentReservation.cancelReason" class="span-2">
                <span>取消原因</span><strong>{{ currentReservation.cancelReason }}</strong>
              </div>
            </div>
            <div class="cw-detail-actions">
              <button
                v-if="canEditReservation(currentReservation)"
                class="btn btn-ghost"
                type="button"
                @click="openEdit(currentReservation)"
              >编辑预定</button>
              <button
                v-if="currentReservation.status === 'CONFIRMED'"
                class="btn btn-danger"
                type="button"
                @click="askCancel(currentReservation)"
              >取消预定</button>
            </div>
          </section>

          <section class="cw-detail-section">
            <div class="cw-section-head">
              <h4>合同</h4>
              <span v-if="currentContract">
                {{ currentContract.sourceLabel }} · {{ currentContract.templateName || '线下回签' }}
              </span>
            </div>

            <div v-if="!currentContract" class="cw-contract-empty">
              <div>
                <strong>尚未生成合同</strong>
                <p>系统会读取上方广告牌快照、甲方预设信息与签章，按模板生成 Word 合同。</p>
              </div>
              <div class="cw-generate-row">
                <select v-model="selectedTemplateId">
                  <option v-for="t in detailTemplates" :key="t.id" :value="t.id">
                    {{ t.name }}{{ t.defaultTemplate ? '（默认）' : '' }}
                  </option>
                </select>
                <button class="btn btn-primary" type="button" :disabled="generating || !detailTemplates.length" @click="generateContract">
                  {{ generating ? '生成中…' : '生成合同' }}
                </button>
                <button v-if="canUploadSigned" class="btn btn-ghost" type="button" @click="openUpload">上传线下已签合同</button>
              </div>
              <p v-if="!detailTemplates.length" class="cw-warn">
                还没有可用模板，请先到「签约设置」上传或启用模板。
              </p>
            </div>

            <template v-else>
              <div class="cw-info-grid">
                <div><span>合同编号</span><strong>{{ currentContract.contractNo }}</strong></div>
                <div><span>合同状态</span><strong>{{ currentContract.statusLabel }}</strong></div>
                <div><span>来源</span><strong>{{ currentContract.sourceLabel }}</strong></div>
                <div><span>模板</span><strong>{{ currentContract.templateName || '线下回签件' }}</strong></div>
                <div><span>签署人</span><strong>{{ currentContract.signerName || '待签署' }}</strong></div>
                <div><span>签署时间</span><strong>{{ currentContract.signedAt ? fmtTime(currentContract.signedAt) : '—' }}</strong></div>
                <div v-if="currentContract.voidReason" class="span-2"><span>作废原因</span><strong>{{ currentContract.voidReason }}</strong></div>
              </div>

              <div v-if="currentContract.status === 'PENDING_SIGN'" class="cw-sign-box">
                <div class="cw-sign-main">
                  <h5>乙方签署链接</h5>
                  <p>把链接或二维码发给乙方，乙方无需登录即可查看合同并手写签名或上传签名图片。</p>
                  <div class="cw-sign-link-row">
                    <input :value="signingUrl" readonly>
                    <button class="btn btn-primary btn-sm" type="button" @click="copySigningLink">复制链接</button>
                    <button class="btn btn-ghost btn-sm" type="button" @click="refreshSigningLink">刷新链接</button>
                  </div>
                  <div class="cw-sign-expiry">
                    链接有效期至 {{ fmtTime(currentContract.signTokenExpiresAt) }}
                    <span v-if="!signingUrl" class="cw-warn">· 当前链接已失效，请刷新</span>
                  </div>
                </div>
                <canvas ref="qrCanvas" class="cw-qr" width="152" height="152"></canvas>
              </div>

              <div class="cw-contract-actions">
                <button class="btn btn-ghost" type="button" @click="previewCurrentContract">预览合同</button>
                <button
                  class="btn btn-ghost"
                  type="button"
                  @click="downloadAdminFile(`/api/admin/contracts/${currentContract.id}/download`, currentContract.currentFileName || currentContract.contractNo + '.docx')"
                >下载当前文件</button>
                <button
                  v-if="canUploadSigned"
                  class="btn btn-ghost"
                  type="button"
                  @click="openUpload"
                >上传回签件</button>
                <button
                  v-if="['PENDING_SIGN', 'SIGNED'].includes(currentContract.status)"
                  class="btn btn-danger"
                  type="button"
                  @click="askVoid"
                >作废合同</button>
                <button
                  v-if="currentContract.status === 'SIGNED'"
                  class="btn btn-ghost"
                  type="button"
                  @click="completeContract"
                >结束合同</button>
                <button
                  v-if="currentContract.status === 'VOID' && currentReservation?.status === 'CONFIRMED'"
                  class="btn btn-primary"
                  type="button"
                  :disabled="generating"
                  @click="generateContract"
                >{{ generating ? '生成中…' : '重新生成合同' }}</button>
              </div>

              <div v-if="detailFiles.length" class="cw-file-list">
                <h5>文件版本</h5>
                <div v-for="f in detailFiles" :key="f.id" class="cw-file-row">
                  <span class="cw-file-type">{{ FILE_TYPE[f.fileType] || f.fileType }}</span>
                  <span class="cw-file-name">{{ f.originalName || '合同文件' }}</span>
                  <span class="cw-file-size">{{ Math.max(1, Math.round((f.fileSize || 0) / 1024)) }} KB</span>
                  <button
                    class="link-btn"
                    type="button"
                    @click="downloadAdminFile(f.downloadUrl, f.originalName)"
                  >下载</button>
                </div>
              </div>

              <div v-if="detailEvents.length" class="cw-event-list">
                <h5>操作记录</h5>
                <div v-for="ev in detailEvents" :key="ev.id" class="cw-event-row">
                  <span class="cw-event-dot"></span>
                  <div>
                    <strong>{{ EVENT_ACTION[ev.action] || ev.action }}</strong>
                    <span class="cw-event-meta">{{ ev.actorType === 'TENANT' ? '乙方' : (ev.actorType === 'SYSTEM' ? '系统' : '管理员') }} · {{ fmtTime(ev.createdAt) }}</span>
                    <p v-if="ev.detail">{{ ev.detail }}</p>
                  </div>
                </div>
              </div>
            </template>
          </section>
        </template>
      </div>
    </div>
  </div>

  <!-- 上传线下已签合同 -->
  <div v-if="uploadOpen" class="cw-overlay" @click.self="uploadOpen = false">
    <div class="cw-dialog cw-dialog-small">
      <header class="cw-dialog-head">
        <div>
          <h3>上传线下已签合同</h3>
          <p>支持 PDF / JPG / PNG / DOCX，单文件最大 20MB。上传后预定将标记为已签约。</p>
        </div>
        <button class="cw-close" type="button" @click="uploadOpen = false">×</button>
      </header>
      <div class="cw-dialog-body">
        <div class="cw-form-grid">
          <div class="form-field span-2">
            <label>签署人姓名</label>
            <input v-model="uploadForm.signerName" type="text" maxlength="100" placeholder="用于记录签署人">
          </div>
          <div class="form-field span-2">
            <label>已签合同文件 <em>*</em></label>
            <input ref="uploadInput" type="file" accept=".pdf,.png,.jpg,.jpeg,.docx" @change="onUploadFile">
          </div>
        </div>
      </div>
      <footer class="cw-dialog-foot">
        <span></span>
        <div>
          <button class="btn btn-ghost" type="button" @click="uploadOpen = false">取消</button>
          <button class="btn btn-primary" type="button" :disabled="uploadSaving" @click="submitUpload">
            {{ uploadSaving ? '上传中…' : '上传并归档' }}
          </button>
        </div>
      </footer>
    </div>
  </div>

  <!-- 取消 / 作废原因 -->
  <div v-if="reasonOpen" class="cw-overlay" @click.self="reasonOpen = false">
    <div class="cw-dialog cw-dialog-small">
      <header class="cw-dialog-head">
        <div>
          <h3>{{ reasonTitle }}</h3>
          <p>该操作会写入操作日志，请填写原因便于后续追溯。</p>
        </div>
        <button class="cw-close" type="button" @click="reasonOpen = false">×</button>
      </header>
      <div class="cw-dialog-body">
        <div class="form-field">
          <label>原因 <em>*</em></label>
          <textarea v-model="reasonText" rows="4" maxlength="500" placeholder="例如：客户调整投放计划 / 合同条款需重新协商"></textarea>
        </div>
      </div>
      <footer class="cw-dialog-foot">
        <span></span>
        <div>
          <button class="btn btn-ghost" type="button" @click="reasonOpen = false">取消</button>
          <button class="btn btn-danger" type="button" :disabled="reasonSaving" @click="submitReason">
            {{ reasonSaving ? '处理中…' : '确认' }}
          </button>
        </div>
      </footer>
    </div>
  </div>

  <!-- Word / PDF 预览 -->
  <div v-if="previewOpen" class="cw-overlay cw-preview-overlay" @click.self="closePreview">
    <div class="cw-preview-dialog">
      <header class="cw-preview-head">
        <div>
          <h3>{{ previewTitle }}</h3>
          <p>浏览器内预览，最终版式以下载的 Word 文件为准。</p>
        </div>
        <button class="cw-close" type="button" @click="closePreview">×</button>
      </header>
      <div class="cw-preview-body">
        <div v-if="previewLoading" class="cw-empty">正在加载合同文件…</div>
        <div v-else-if="previewError" class="cw-preview-error">{{ previewError }}</div>
        <iframe v-else-if="previewPdfUrl" :src="previewPdfUrl" class="cw-preview-pdf" title="合同 PDF 预览"></iframe>
        <img v-else-if="previewImageUrl" :src="previewImageUrl" class="cw-preview-image" alt="合同图片预览">
        <div v-show="!previewPdfUrl && !previewImageUrl && !previewError" ref="previewEl" class="cw-preview-docx"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.contract-workspace { display: flex; flex-direction: column; gap: 14px; }

.cw-head {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 16px;
}
.cw-stats { display: grid; grid-template-columns: repeat(4, minmax(110px, 1fr)); gap: 10px; flex: 1; }
.cw-stat {
  background: var(--panel);
  border: 1px solid var(--border-light);
  border-radius: var(--radius);
  padding: 12px 16px;
  box-shadow: var(--shadow-sm);
}
.cw-stat-label { display: block; color: var(--text-3); font-size: 12px; margin-bottom: 4px; }
.cw-stat strong { font-size: 24px; color: var(--text); font-weight: 600; }
.cw-stat .is-amber { color: var(--amber); }
.cw-stat .is-green { color: var(--green); }
.cw-head-actions { display: flex; align-items: flex-start; gap: 8px; padding-top: 4px; }

.cw-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  border-bottom: 1px solid var(--border);
}
.cw-tab {
  border: none;
  background: none;
  padding: 9px 16px;
  font-size: 14px;
  color: var(--text-2);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  font-family: inherit;
}
.cw-tab:hover { color: var(--primary); }
.cw-tab.active { color: var(--primary); border-bottom-color: var(--primary); font-weight: 600; }
.cw-tab-hint { margin-left: auto; color: var(--text-3); font-size: 12px; }

.cw-panel {
  background: var(--panel);
  border: 1px solid var(--border-light);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
.cw-filters {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-light);
  background: #fbfcfd;
}
.cw-filters input { width: 300px; max-width: 100%; }
.cw-filters input,
.cw-filters select,
.cw-form-grid input,
.cw-form-grid select,
.cw-form-grid textarea,
.cw-generate-row select,
.cw-sign-link-row input {
  height: 32px;
  border: 1px solid var(--border);
  border-radius: 3px;
  padding: 0 10px;
  font-size: 13px;
  font-family: inherit;
  color: var(--text);
  background: #fff;
  outline: none;
}
.cw-form-grid textarea { height: auto; padding: 8px 10px; resize: vertical; line-height: 1.6; }
.cw-filters input:focus,
.cw-filters select:focus,
.cw-form-grid input:focus,
.cw-form-grid select:focus,
.cw-form-grid textarea:focus,
.cw-generate-row select:focus { border-color: var(--primary); }

.cw-table-wrap { overflow-x: auto; }
.cw-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.cw-table th {
  text-align: left;
  padding: 10px 14px;
  background: #f7f9fb;
  color: var(--text-2);
  font-weight: 500;
  font-size: 12px;
  border-bottom: 1px solid var(--border-light);
  white-space: nowrap;
}
.cw-table td { padding: 11px 14px; border-bottom: 1px solid var(--border-light); vertical-align: middle; }
.cw-table tbody tr:last-child td { border-bottom: none; }
.cw-table tr.cw-row { cursor: pointer; transition: background 0.12s; }
.cw-table tr.cw-row:hover { background: #f8fbff; }
.cw-table .num { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
.cw-table .actions { text-align: right; white-space: nowrap; }
.cw-code { color: var(--primary); font-weight: 600; }
.cw-cell-main { font-weight: 500; }
.cw-sub { color: var(--text-3); font-size: 12px; margin-top: 2px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.cw-sign-hint { color: var(--amber); }
.cw-empty { padding: 42px 20px; text-align: center; color: var(--text-3); }

.cw-badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 11px;
  font-size: 12px;
  white-space: nowrap;
}
.cw-badge.tiny { height: 18px; padding: 0 6px; font-size: 11px; }
.cw-badge.is-pending { background: #fff6e8; color: #a9660f; }
.cw-badge.is-signed { background: #eaf7ef; color: #1f8a4c; }
.cw-badge.is-done { background: #eef2f7; color: #5c6675; }
.cw-badge.is-cancelled { background: #fdf1f0; color: #c0392b; }

.cw-overlay {
  position: fixed;
  inset: 0;
  z-index: 1200;
  background: rgba(15, 23, 42, 0.48);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 5vh 20px 4vh;
  overflow-y: auto;
}
.cw-dialog {
  width: 100%;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 18px 50px rgba(10, 22, 40, 0.28);
  display: flex;
  flex-direction: column;
  max-height: 90vh;
}
.cw-dialog-form { max-width: 980px; }
.cw-dialog-detail { max-width: 980px; }
.cw-dialog-small { max-width: 560px; }
.cw-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--border-light);
}
.cw-dialog-head h3 { font-size: 17px; display: flex; align-items: center; gap: 10px; }
.cw-dialog-head p { color: var(--text-3); font-size: 12px; margin-top: 4px; }
.cw-close {
  border: none;
  background: none;
  font-size: 24px;
  line-height: 1;
  color: var(--text-3);
  cursor: pointer;
  padding: 0 2px;
}
.cw-close:hover { color: var(--text); }
.cw-dialog-body { padding: 18px 22px; overflow-y: auto; }
.cw-dialog-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px;
  border-top: 1px solid var(--border-light);
  background: #fbfcfd;
  border-radius: 0 0 6px 6px;
}
.cw-dialog-foot > div { display: flex; gap: 8px; }
.cw-foot-hint { color: var(--text-3); font-size: 12px; }

.cw-form-section { margin-bottom: 20px; }
.cw-form-section h4 {
  font-size: 13px;
  color: var(--text-2);
  margin-bottom: 10px;
  padding-left: 8px;
  border-left: 3px solid var(--primary);
}
.cw-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 18px; }
.cw-form-grid .span-2 { grid-column: span 2; }
.cw-form-grid .form-field { display: flex; flex-direction: column; gap: 5px; }
.cw-form-grid label { font-size: 12px; color: var(--text-2); display: flex; align-items: center; gap: 6px; }
.cw-form-grid label em { color: var(--red); font-style: normal; }
.cw-form-grid input[readonly] { background: #f5f7fa; color: var(--text-2); }
.cw-inline-link {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--primary);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.cw-billboard-snapshot {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px 16px;
  background: #f7f9fc;
  border: 1px solid var(--border-light);
  border-radius: 4px;
  padding: 12px 14px;
}
.cw-billboard-snapshot div { display: flex; flex-direction: column; gap: 2px; }
.cw-billboard-snapshot span { color: var(--text-3); font-size: 11px; }
.cw-billboard-snapshot strong { font-size: 12px; font-weight: 500; }

.cw-detail-section { margin-bottom: 20px; }
.cw-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--border-light);
  padding-bottom: 8px;
  margin-bottom: 12px;
}
.cw-section-head h4 { font-size: 14px; }
.cw-section-head span { color: var(--text-3); font-size: 12px; }
.cw-info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 9px 20px; }
.cw-info-grid .span-2 { grid-column: span 2; }
.cw-info-grid > div { display: flex; gap: 10px; font-size: 13px; line-height: 1.65; }
.cw-info-grid span { flex: 0 0 88px; color: var(--text-3); }
.cw-info-grid strong { font-weight: 400; color: var(--text); word-break: break-all; }
.cw-detail-actions { display: flex; gap: 8px; margin-top: 14px; }

.cw-contract-empty {
  border: 1px dashed var(--border);
  border-radius: 4px;
  padding: 16px;
  background: #fbfcfd;
}
.cw-contract-empty strong { font-size: 13px; }
.cw-contract-empty p { color: var(--text-3); font-size: 12px; margin: 4px 0 12px; }
.cw-generate-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.cw-generate-row select { min-width: 260px; }
.cw-warn { color: var(--amber) !important; font-size: 12px; margin-top: 8px; }

.cw-sign-box {
  display: flex;
  gap: 18px;
  justify-content: space-between;
  background: #f5f9ff;
  border: 1px solid var(--primary-border);
  border-radius: 4px;
  padding: 14px 16px;
  margin-top: 12px;
}
.cw-sign-main { flex: 1; min-width: 0; }
.cw-sign-main h5 { font-size: 13px; margin-bottom: 4px; }
.cw-sign-main p { color: var(--text-3); font-size: 12px; margin-bottom: 8px; }
.cw-sign-link-row { display: flex; gap: 6px; }
.cw-sign-link-row input { flex: 1; min-width: 0; background: #fff; }
.cw-sign-expiry { color: var(--text-3); font-size: 11px; margin-top: 6px; }
.cw-qr { width: 132px; height: 132px; background: #fff; border: 1px solid var(--border-light); border-radius: 3px; flex: 0 0 auto; }

.cw-contract-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
.cw-file-list, .cw-event-list { margin-top: 18px; }
.cw-file-list h5, .cw-event-list h5 { font-size: 13px; margin-bottom: 8px; color: var(--text-2); }
.cw-file-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--border-light);
  border-radius: 3px;
  margin-bottom: 6px;
  font-size: 12px;
}
.cw-file-type { color: var(--primary); background: var(--primary-light); padding: 2px 7px; border-radius: 3px; white-space: nowrap; }
.cw-file-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cw-file-size { color: var(--text-3); white-space: nowrap; }
.cw-event-row {
  display: flex;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--border-light);
  font-size: 12px;
}
.cw-event-row:last-child { border-bottom: none; }
.cw-event-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--primary); margin-top: 6px; flex: 0 0 auto; }
.cw-event-row strong { font-weight: 500; margin-right: 8px; }
.cw-event-meta { color: var(--text-3); }
.cw-event-row p { color: var(--text-3); margin-top: 2px; }

.cw-preview-overlay { padding: 3vh 16px; }
.cw-preview-dialog {
  width: 100%;
  max-width: 1180px;
  background: #fff;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  max-height: 94vh;
  overflow: hidden;
  box-shadow: 0 18px 50px rgba(10, 22, 40, 0.3);
}
.cw-preview-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-light);
}
.cw-preview-head h3 { font-size: 15px; }
.cw-preview-head p { color: var(--text-3); font-size: 12px; margin-top: 3px; }
.cw-preview-body { flex: 1; overflow: auto; background: #eef1f5; padding: 18px; }
.cw-preview-docx { min-height: 200px; }
.cw-preview-docx :deep(.cw-docx-wrapper) { background: transparent; padding: 0; }
.cw-preview-docx :deep(.cw-docx-wrapper > section.cw-docx) {
  margin: 0 auto 18px;
  box-shadow: 0 2px 12px rgba(15, 23, 42, 0.12);
}
.cw-preview-pdf { width: 100%; height: 78vh; border: none; background: #fff; }
.cw-preview-image { display: block; max-width: 100%; margin: 0 auto; background: #fff; }
.cw-preview-error { padding: 40px; text-align: center; color: var(--red); }

@media (max-width: 900px) {
  .cw-head { flex-direction: column; }
  .cw-stats { grid-template-columns: repeat(2, 1fr); }
  .cw-head-actions { justify-content: flex-end; }
  .cw-tab-hint { display: none; }
  .cw-form-grid, .cw-info-grid { grid-template-columns: 1fr; }
  .cw-form-grid .span-2, .cw-info-grid .span-2 { grid-column: span 1; }
  .cw-billboard-snapshot { grid-template-columns: 1fr 1fr; }
  .cw-sign-box { flex-direction: column; }
  .cw-qr { align-self: center; }
  .cw-dialog-foot { flex-direction: column; align-items: stretch; }
  .cw-dialog-foot > div { justify-content: flex-end; }
}
</style>
