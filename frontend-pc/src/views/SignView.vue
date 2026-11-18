<script setup>
/**
 * 乙方免登录签署页（/sign/:token）：
 *   - 手机 / 电脑均可打开；
 *   - 查看合同全文，手写签名或上传签名图片；
 *   - 也支持直接上传线下已签合同；
 *   - 签署完成后可下载最终 Word。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { toast } from '../utils/toast'
import { renderAsync } from 'docx-preview'

const route = useRoute()
let token = String(route.params.token || '')

const loading = ref(true)
const error = ref('')
const info = ref(null)
const previewLoading = ref(false)
const previewError = ref('')
const previewEl = ref(null)
const previewPdfUrl = ref('')
const previewImageUrl = ref('')
let previewBlobUrl = ''

const signerName = ref('')
const consent = ref(false)
const signMode = ref('online')
const signatureMode = ref('draw')
const submitting = ref(false)
const signatureFile = ref(null)
const signaturePreview = ref('')
const signedSuccess = ref(false)

const offlineFile = ref(null)
const offlineSigner = ref('')

const sigCanvas = ref(null)
let sigCtx = null
let drawing = false
let hasInk = false
const canvasRatio = ref(1)

const canSign = computed(() => !!info.value?.canSign)
const isSigned = computed(() => info.value?.status === 'SIGNED')

function money(value) {
  if (value == null || value === '') return '0.00'
  const n = Number(value)
  return Number.isNaN(n) ? '0.00' : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    info.value = await api.signInfo(token)
    signerName.value = info.value.tenantContact || info.value.tenantName || ''
    offlineSigner.value = info.value.tenantContact || info.value.tenantName || ''
    // 先结束整页 loading，让签署布局（含 ref=previewEl）真正挂载后再渲染 docx。
    loading.value = false
    await nextTick()
    setupCanvas()
    await loadPreview()
  } catch (e) {
    error.value = e.message || '签署链接无效或合同不存在'
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 同一组件内切换签署链接（/sign/A → /sign/B）时重新拉取合同，避免显示上一位客户的合同
watch(() => route.params.token, (value) => {
  const next = String(value || '')
  if (next === token) return
  token = next
  info.value = null
  error.value = ''
  signedSuccess.value = false
  submitting.value = false
  revokePreviewUrls()
  load()
})

onBeforeUnmount(() => {
  revokePreviewUrls()
  if (signaturePreview.value?.startsWith('blob:')) URL.revokeObjectURL(signaturePreview.value)
})

function revokePreviewUrls() {
  if (previewBlobUrl) {
    URL.revokeObjectURL(previewBlobUrl)
    previewBlobUrl = ''
  }
  if (previewPdfUrl.value) {
    URL.revokeObjectURL(previewPdfUrl.value)
    previewPdfUrl.value = ''
  }
  if (previewImageUrl.value) {
    URL.revokeObjectURL(previewImageUrl.value)
    previewImageUrl.value = ''
  }
}

async function fetchFile() {
  const res = await fetch(info.value.fileUrl)
  if (!res.ok) {
    let message = `合同文件读取失败（HTTP ${res.status}）`
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

async function loadPreview() {
  if (!info.value) return
  previewLoading.value = true
  previewError.value = ''
  revokePreviewUrls()
  try {
    const blob = await fetchFile()
    const ext = (info.value.fileName || '').split('.').pop().toLowerCase()
    if (ext === 'pdf') {
      previewPdfUrl.value = URL.createObjectURL(blob)
    } else if (['png', 'jpg', 'jpeg'].includes(ext)) {
      previewImageUrl.value = URL.createObjectURL(blob)
    } else {
      previewBlobUrl = URL.createObjectURL(blob)
      await nextTick()
      if (previewEl.value) {
        const narrow = window.innerWidth <= 720
        previewEl.value.innerHTML = ''
        await renderAsync(blob, previewEl.value, null, {
          className: 'sign-docx',
          inWrapper: true,
          // 窄屏按容器宽度重排，避免固定 A4 宽度把合同文字裁掉。
          breakPages: !narrow,
          ignoreWidth: narrow,
          ignoreHeight: narrow,
        })
      }
    }
  } catch (e) {
    previewError.value = e.message || '合同预览失败，请下载后查看'
  } finally {
    previewLoading.value = false
  }
}

/* ---------------- 手写签名 ---------------- */

function setupCanvas() {
  const canvas = sigCanvas.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  if (!rect.width) return
  const dpr = window.devicePixelRatio || 1
  canvasRatio.value = dpr
  canvas.width = Math.round(rect.width * dpr)
  canvas.height = Math.round(rect.height * dpr)
  sigCtx = canvas.getContext('2d')
  sigCtx.setTransform(dpr, 0, 0, dpr, 0, 0)
  sigCtx.lineWidth = 3
  sigCtx.lineCap = 'round'
  sigCtx.lineJoin = 'round'
  sigCtx.strokeStyle = '#12263f'
  hasInk = false
}

function pointOf(event) {
  const rect = sigCanvas.value.getBoundingClientRect()
  return { x: event.clientX - rect.left, y: event.clientY - rect.top }
}

function startDraw(event) {
  if (!sigCtx) return
  if (event.isPrimary === false) return
  event.preventDefault()
  drawing = true
  hasInk = true
  const p = pointOf(event)
  sigCtx.beginPath()
  sigCtx.moveTo(p.x, p.y)
  if (sigCanvas.value.setPointerCapture) {
    sigCanvas.value.setPointerCapture(event.pointerId)
  }
}

function moveDraw(event) {
  if (!drawing || !sigCtx) return
  event.preventDefault()
  const p = pointOf(event)
  sigCtx.lineTo(p.x, p.y)
  sigCtx.stroke()
}

function endDraw() {
  drawing = false
}

function clearSignature() {
  const canvas = sigCanvas.value
  if (!canvas || !sigCtx) return
  sigCtx.save()
  sigCtx.setTransform(1, 0, 0, 1, 0, 0)
  sigCtx.clearRect(0, 0, canvas.width, canvas.height)
  sigCtx.restore()
  hasInk = false
}

function onSignatureMode(mode) {
  signatureMode.value = mode
  if (mode === 'draw') {
    nextTick(setupCanvas)
  }
}

function onSignatureFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    toast('签名图片仅支持 PNG / JPG', 'error')
    event.target.value = ''
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    toast('签名图片不能超过 5MB', 'error')
    event.target.value = ''
    return
  }
  signatureFile.value = file
  if (signaturePreview.value?.startsWith('blob:')) URL.revokeObjectURL(signaturePreview.value)
  signaturePreview.value = URL.createObjectURL(file)
}

function canvasBlob() {
  return new Promise((resolve) => {
    if (!sigCanvas.value) return resolve(null)
    sigCanvas.value.toBlob((blob) => resolve(blob), 'image/png')
  })
}

/* ---------------- 提交 ---------------- */

async function submitSignature() {
  if (submitting.value) return
  if (!signerName.value.trim()) {
    toast('请填写签署人姓名', 'error')
    return
  }
  if (!consent.value) {
    toast('请先勾选确认，同意以电子签名方式签署合同', 'error')
    return
  }
  let blob = null
  if (signatureMode.value === 'draw') {
    if (!hasInk) {
      toast('请在签名框内手写签名', 'error')
      return
    }
    // 手写签名转 Blob 期间也占用提交锁，防止快速连点重复发起签署
    submitting.value = true
    blob = await canvasBlob()
    if (!blob) {
      submitting.value = false
      toast('签名生成失败，请重试', 'error')
      return
    }
  } else {
    if (!signatureFile.value) {
      toast('请上传签名图片', 'error')
      return
    }
    submitting.value = true
    blob = signatureFile.value
  }

  try {
    const fd = new FormData()
    fd.append('signerName', signerName.value.trim())
    fd.append('consent', 'true')
    fd.append('signature', blob, signatureMode.value === 'draw' ? 'signature.png' : signatureFile.value.name)
    info.value = await api.signContract(token, fd)
    signedSuccess.value = true
    toast('签署成功，合同已生效')
    await nextTick()
    await loadPreview()
  } catch (e) {
    toast(e.message || '签署失败，请重试', 'error')
  } finally {
    submitting.value = false
  }
}

function onOfflineFile(event) {
  offlineFile.value = event.target.files?.[0] || null
}

async function submitOffline() {
  if (submitting.value) return
  if (!offlineFile.value) {
    toast('请选择已签署的合同文件', 'error')
    return
  }
  if (!consent.value) {
    toast('请先勾选确认', 'error')
    return
  }
  submitting.value = true
  try {
    const fd = new FormData()
    fd.append('file', offlineFile.value)
    if (offlineSigner.value.trim()) fd.append('signerName', offlineSigner.value.trim())
    fd.append('consent', 'true')
    info.value = await api.uploadSignedByToken(token, fd)
    signedSuccess.value = true
    toast('已签合同上传成功，合同已归档')
    await nextTick()
    await loadPreview()
  } catch (e) {
    toast(e.message || '上传失败，请重试', 'error')
  } finally {
    submitting.value = false
  }
}

async function downloadContract() {
  if (!info.value?.fileUrl) return
  try {
    const blob = await fetchFile()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = info.value.fileName || `${info.value.contractNo}.docx`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 3000)
  } catch (e) {
    toast(e.message || '合同下载失败', 'error')
  }
}

const statusClass = computed(() => {
  if (isSigned.value || signedSuccess.value) return 'is-signed'
  if (info.value?.status === 'VOID') return 'is-void'
  return 'is-pending'
})
</script>

<template>
  <div class="sign-page">
    <header class="sign-top">
      <div class="sign-brand">
        <span class="sign-logo">牌</span>
        <div>
          <strong>{{ info?.siteName || '公路广告牌租赁定位系统' }}</strong>
          <span>广告牌租赁合同 · 电子签署</span>
        </div>
      </div>
      <span class="sign-top-badge">合同签署</span>
    </header>

    <main class="sign-shell">
      <div v-if="loading" class="sign-state">
        <div class="sign-spinner"></div>
        <p>正在加载合同…</p>
      </div>

      <div v-else-if="error" class="sign-state sign-state-error">
        <h2>无法打开签署链接</h2>
        <p>{{ error }}</p>
        <p class="sign-muted">请确认链接完整；如链接已过期，请联系甲方重新获取。</p>
        <router-link class="btn btn-primary" to="/">返回首页</router-link>
      </div>

      <template v-else-if="info">
        <section class="sign-summary">
          <div class="sign-summary-head">
            <div>
              <span class="sign-kicker">合同编号</span>
              <h1>{{ info.contractNo }}</h1>
            </div>
            <span class="sign-status" :class="statusClass">{{ info.statusLabel }}</span>
          </div>
          <div class="sign-grid">
            <div>
              <span>出租方（甲方）</span>
              <strong>{{ info.partyAName }}</strong>
            </div>
            <div>
              <span>承租方（乙方）</span>
              <strong>{{ info.tenantName }}</strong>
            </div>
            <div>
              <span>广告牌</span>
              <strong>{{ info.billboardName }}（{{ info.billboardCode }}）</strong>
            </div>
            <div>
              <span>投放期限</span>
              <strong>{{ fmtDate(info.startDate) }} 至 {{ fmtDate(info.endDate) }}</strong>
            </div>
            <div>
              <span>合同金额</span>
              <strong>¥{{ money(info.totalAmount) }}</strong>
            </div>
            <div>
              <span>签署截止</span>
              <strong>{{ fmtTime(info.tokenExpiresAt) }}</strong>
            </div>
          </div>
        </section>

        <div class="sign-layout">
          <section class="sign-doc">
            <header class="sign-doc-head">
              <div>
                <h2>合同正文</h2>
                <p>{{ info.fileName }}</p>
              </div>
              <button class="btn btn-ghost btn-sm" type="button" @click="downloadContract">下载 Word</button>
            </header>
            <div class="sign-doc-body">
              <div v-if="previewLoading" class="sign-doc-loading">正在渲染合同…</div>
              <div v-else-if="previewError" class="sign-doc-error">
                <p>{{ previewError }}</p>
                <button class="btn btn-primary btn-sm" type="button" @click="downloadContract">下载后查看</button>
              </div>
              <iframe v-else-if="previewPdfUrl" :src="previewPdfUrl" class="sign-pdf" title="合同 PDF"></iframe>
              <img v-else-if="previewImageUrl" :src="previewImageUrl" class="sign-image" alt="合同图片">
              <div v-show="!previewPdfUrl && !previewImageUrl && !previewError" ref="previewEl" class="sign-docx"></div>
            </div>
          </section>

          <aside class="sign-side">
            <section v-if="canSign" class="sign-card">
              <div class="sign-card-head">
                <h2>乙方签署</h2>
                <span>请阅读合同后签署</span>
              </div>

              <div class="sign-tabs">
                <button
                  class="sign-tab"
                  :class="{ active: signMode === 'online' }"
                  type="button"
                  @click="signMode = 'online'"
                >在线签名</button>
                <button
                  class="sign-tab"
                  :class="{ active: signMode === 'offline' }"
                  type="button"
                  @click="signMode = 'offline'"
                >上传已签合同</button>
              </div>

              <template v-if="signMode === 'online'">
                <label class="sign-field">
                  <span>签署人姓名 <em>*</em></span>
                  <input v-model="signerName" type="text" maxlength="100" placeholder="请输入本人姓名">
                </label>

                <div class="sign-sig-modes">
                  <button
                    class="sign-sig-mode"
                    :class="{ active: signatureMode === 'draw' }"
                    type="button"
                    @click="onSignatureMode('draw')"
                  >手写签名</button>
                  <button
                    class="sign-sig-mode"
                    :class="{ active: signatureMode === 'upload' }"
                    type="button"
                    @click="onSignatureMode('upload')"
                  >上传签名图片</button>
                </div>

                <div v-show="signatureMode === 'draw'" class="sign-pad-wrap">
                  <canvas
                    ref="sigCanvas"
                    class="sign-pad"
                    @pointerdown="startDraw"
                    @pointermove="moveDraw"
                    @pointerup="endDraw"
                    @pointercancel="endDraw"
                    @pointerleave="endDraw"
                  ></canvas>
                  <span class="sign-pad-hint">请使用手指或鼠标在框内签名</span>
                  <button class="sign-clear" type="button" @click="clearSignature">清除重写</button>
                </div>

                <div v-show="signatureMode === 'upload'" class="sign-upload">
                  <label class="sign-upload-box">
                    <input type="file" accept=".png,.jpg,.jpeg" hidden @change="onSignatureFile">
                    <span v-if="!signaturePreview">点击上传签名图片（PNG / JPG）</span>
                    <img v-else :src="signaturePreview" alt="签名预览">
                  </label>
                </div>

                <label class="sign-consent">
                  <input v-model="consent" type="checkbox">
                  <span>本人已阅读并同意本合同全部条款，同意以电子签名方式签署，该签名与手写签名具有同等法律效力。</span>
                </label>

                <button class="sign-submit" type="button" :disabled="submitting" @click="submitSignature">
                  {{ submitting ? '正在签署…' : '确认签署合同' }}
                </button>
              </template>

              <template v-else>
                <label class="sign-field">
                  <span>签署人姓名</span>
                  <input v-model="offlineSigner" type="text" maxlength="100" placeholder="用于记录签署人">
                </label>
                <label class="sign-upload-box sign-upload-offline">
                  <input type="file" accept=".pdf,.png,.jpg,.jpeg,.docx" hidden @change="onOfflineFile">
                  <span v-if="!offlineFile">点击上传已签好的合同（PDF / 图片 / Word）</span>
                  <strong v-else>{{ offlineFile.name }}</strong>
                </label>
                <label class="sign-consent">
                  <input v-model="consent" type="checkbox">
                  <span>本人确认上传的是双方已签署完毕的合同，并同意系统归档保存。</span>
                </label>
                <button class="sign-submit" type="button" :disabled="submitting" @click="submitOffline">
                  {{ submitting ? '正在上传…' : '上传已签合同' }}
                </button>
              </template>

              <p class="sign-safe">签署信息将记录签署人、时间与网络地址，仅用于合同存证。</p>
            </section>

            <section v-else class="sign-card sign-card-done">
              <div class="sign-done-icon" :class="{ ok: isSigned || signedSuccess }">
                {{ isSigned || signedSuccess ? '✓' : '!' }}
              </div>
              <h2>{{ isSigned || signedSuccess ? '签署完成' : '当前不可签署' }}</h2>
              <p>{{ info.blockedReason || (isSigned ? '合同已签署，无需重复签署。' : '请联系甲方获取新的签署链接。') }}</p>
              <div v-if="info.signerName" class="sign-done-meta">
                签署人：{{ info.signerName }} · {{ fmtTime(info.signedAt) }}
              </div>
              <button
                v-if="info.status === 'SIGNED' || info.status === 'COMPLETED'"
                class="sign-submit"
                type="button"
                @click="downloadContract"
              >下载合同</button>
            </section>

            <section class="sign-tips">
              <h3>签署说明</h3>
              <p>1. 签署前请核对广告牌、投放期限与合同金额；如有异议请联系甲方。</p>
              <p>2. 在线手写签名或上传签名图片后，系统会自动把签名嵌入合同签章位并生成最终 Word。</p>
              <p>3. 如已线下签署，可直接上传扫描件或照片归档，无需重复在线签名。</p>
              <p>4. 签署链接具有有效期，请勿转发给无关人员。</p>
            </section>
          </aside>
        </div>
      </template>
    </main>

    <footer class="sign-foot">
      <span>{{ info?.siteName || '公路广告牌租赁定位系统' }}</span>
      <span>电子签署 · 合同文件仅通过签署令牌访问</span>
    </footer>
  </div>
</template>

<style scoped>
.sign-page {
  min-height: 100vh;
  background: linear-gradient(180deg, #0f2138 0, #14304f 220px, #eef2f6 220px, #eef2f6 100%);
  padding-bottom: 28px;
  font-size: 14px;
}
.sign-top {
  max-width: 1280px;
  margin: 0 auto;
  padding: 18px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #fff;
}
.sign-brand { display: flex; align-items: center; gap: 11px; }
.sign-logo {
  width: 38px;
  height: 38px;
  border-radius: 8px;
  background: #fff;
  color: #12263f;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 18px;
}
.sign-brand strong { display: block; font-size: 16px; letter-spacing: 0.02em; }
.sign-brand span { display: block; color: #a9bdd6; font-size: 12px; margin-top: 1px; }
.sign-top-badge {
  border: 1px solid rgba(255, 255, 255, 0.28);
  background: rgba(255, 255, 255, 0.08);
  border-radius: 20px;
  padding: 4px 12px;
  font-size: 12px;
  color: #d7e4f3;
}
.sign-shell { max-width: 1280px; margin: 0 auto; padding: 0 24px; }

.sign-state {
  background: #fff;
  border-radius: 8px;
  padding: 70px 24px;
  text-align: center;
  color: var(--text-2);
  box-shadow: 0 10px 32px rgba(15, 32, 56, 0.09);
}
.sign-state h2 { font-size: 19px; color: var(--text); margin-bottom: 10px; }
.sign-state p { margin-top: 6px; }
.sign-state .btn { margin-top: 20px; display: inline-flex; }
.sign-state-error { padding: 60px 24px; }
.sign-muted { color: var(--text-3); font-size: 12px; }
.sign-spinner {
  width: 30px;
  height: 30px;
  border: 3px solid #dbe5f1;
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: sign-spin 0.8s linear infinite;
  margin: 0 auto 14px;
}
@keyframes sign-spin { to { transform: rotate(360deg); } }

.sign-summary {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 10px 32px rgba(15, 32, 56, 0.09);
  padding: 20px 24px;
  margin-bottom: 16px;
}
.sign-summary-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--border-light);
  padding-bottom: 14px;
  margin-bottom: 14px;
}
.sign-kicker { color: var(--text-3); font-size: 12px; }
.sign-summary h1 { font-size: 24px; letter-spacing: 0.03em; margin-top: 2px; }
.sign-status {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 12px;
  border-radius: 13px;
  font-size: 12px;
  white-space: nowrap;
}
.sign-status.is-pending { background: #fff6e8; color: #a9660f; }
.sign-status.is-signed { background: #eaf7ef; color: #1f8a4c; }
.sign-status.is-void { background: #fdf1f0; color: #c0392b; }
.sign-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px 24px; }
.sign-grid > div { display: flex; flex-direction: column; gap: 2px; }
.sign-grid span { color: var(--text-3); font-size: 12px; }
.sign-grid strong { font-weight: 500; font-size: 13px; word-break: break-all; }

.sign-layout { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
.sign-doc, .sign-card, .sign-tips {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 8px 26px rgba(15, 32, 56, 0.08);
}
.sign-doc { min-width: 0; overflow: hidden; }
.sign-doc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-light);
}
.sign-doc-head h2 { font-size: 15px; }
.sign-doc-head p { color: var(--text-3); font-size: 12px; margin-top: 2px; }
.sign-doc-body { background: #e9edf2; padding: 16px; max-height: 72vh; overflow-y: auto; }
.sign-doc-loading, .sign-doc-error { padding: 50px 20px; text-align: center; color: var(--text-3); }
.sign-doc-error p { color: var(--red); margin-bottom: 12px; }
.sign-docx :deep(.sign-docx-wrapper) { background: transparent; padding: 0; }
.sign-docx :deep(.sign-docx-wrapper > section.sign-docx) {
  margin: 0 auto 16px;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.12);
}
.sign-pdf { width: 100%; height: 68vh; border: none; background: #fff; }
.sign-image { display: block; max-width: 100%; margin: 0 auto; background: #fff; }

.sign-side { display: flex; flex-direction: column; gap: 16px; }
.sign-card { padding: 18px; }
.sign-card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.sign-card-head h2 { font-size: 16px; }
.sign-card-head span { color: var(--text-3); font-size: 12px; }
.sign-tabs, .sign-sig-modes { display: flex; gap: 4px; border-bottom: 1px solid var(--border-light); margin-bottom: 14px; }
.sign-sig-modes { border-bottom: none; margin-bottom: 8px; }
.sign-tab {
  border: none;
  background: none;
  padding: 8px 4px;
  margin-right: 12px;
  font-size: 13px;
  color: var(--text-2);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  cursor: pointer;
  font-family: inherit;
}
.sign-tab.active { color: var(--primary); border-bottom-color: var(--primary); font-weight: 600; }
.sign-sig-mode {
  border: 1px solid var(--border);
  background: #fff;
  border-radius: 3px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--text-2);
  cursor: pointer;
  font-family: inherit;
}
.sign-sig-mode.active { border-color: var(--primary); color: var(--primary); background: var(--primary-light); }

.sign-field { display: flex; flex-direction: column; gap: 5px; margin-bottom: 12px; }
.sign-field span { font-size: 12px; color: var(--text-2); }
.sign-field em { color: var(--red); font-style: normal; }
.sign-field input,
.sign-upload-box {
  height: 36px;
  border: 1px solid var(--border);
  border-radius: 3px;
  padding: 0 10px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.sign-field input:focus { border-color: var(--primary); }

.sign-pad-wrap { position: relative; }
.sign-pad {
  display: block;
  width: 100%;
  height: 180px;
  border: 1px dashed #9fb2c8;
  border-radius: 4px;
  background: #fcfdff;
  touch-action: none;
  cursor: crosshair;
}
.sign-pad-hint {
  position: absolute;
  left: 12px;
  top: 10px;
  color: #b0bccb;
  font-size: 12px;
  pointer-events: none;
}
.sign-clear {
  position: absolute;
  right: 8px;
  bottom: 8px;
  border: none;
  background: rgba(255, 255, 255, 0.9);
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  padding: 3px 8px;
  border-radius: 3px;
}
.sign-clear:hover { color: var(--primary); }
.sign-upload { margin-bottom: 6px; }
.sign-upload-box {
  height: auto;
  min-height: 90px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-3);
  font-size: 12px;
  cursor: pointer;
  text-align: center;
  padding: 12px;
  width: 100%;
}
.sign-upload-box img { max-height: 120px; }
.sign-upload-offline { margin-bottom: 12px; }
.sign-upload-offline strong { color: var(--text-2); font-weight: 500; word-break: break-all; }

.sign-consent { display: flex; gap: 8px; align-items: flex-start; font-size: 12px; color: var(--text-2); line-height: 1.6; margin: 12px 0; }
.sign-consent input { margin-top: 3px; accent-color: var(--primary); }
.sign-submit {
  width: 100%;
  height: 42px;
  border: none;
  border-radius: 4px;
  background: var(--primary);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
}
.sign-submit:hover { background: var(--primary-hover); }
.sign-submit:disabled { opacity: 0.6; cursor: not-allowed; }
.sign-safe { color: var(--text-3); font-size: 11px; margin-top: 10px; text-align: center; }

.sign-card-done { text-align: center; }
.sign-done-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 4px auto 12px;
  font-size: 22px;
  font-weight: 700;
  background: #f3f5f8;
  color: var(--text-3);
}
.sign-done-icon.ok { background: #eaf7ef; color: var(--green); }
.sign-card-done h2 { font-size: 17px; margin-bottom: 6px; }
.sign-card-done p { color: var(--text-2); font-size: 13px; line-height: 1.7; margin-bottom: 12px; }
.sign-done-meta { color: var(--text-3); font-size: 12px; margin-bottom: 14px; }

.sign-tips { padding: 16px 18px; }
.sign-tips h3 { font-size: 13px; margin-bottom: 8px; color: var(--text-2); }
.sign-tips p { color: var(--text-3); font-size: 12px; line-height: 1.75; }

.sign-foot {
  max-width: 1280px;
  margin: 20px auto 0;
  padding: 0 24px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: #8b95a5;
  font-size: 11px;
}

@media (max-width: 1040px) {
  .sign-layout { grid-template-columns: 1fr; }
  .sign-side { order: 2; }
  .sign-doc-body { max-height: none; }
}
@media (max-width: 720px) {
  .sign-page { background: #eef2f6; }
  .sign-top { padding: 14px 16px; }
  .sign-brand strong { font-size: 14px; }
  .sign-brand span { font-size: 11px; }
  .sign-top-badge { display: none; }
  .sign-shell { padding: 0 12px; }
  .sign-summary { padding: 16px; border-radius: 6px; }
  .sign-summary h1 { font-size: 20px; }
  .sign-grid { grid-template-columns: 1fr 1fr; gap: 10px 14px; }
  .sign-layout { gap: 12px; }
  .sign-doc-head { padding: 12px 14px; }
  .sign-doc-body { padding: 10px; overflow-x: auto; }
  .sign-docx :deep(.sign-docx-wrapper) {
    display: block;
    padding: 0;
    background: transparent;
    align-items: stretch;
  }
  .sign-docx :deep(.sign-docx-wrapper > section.sign-docx) {
    width: 100% !important;
    min-width: 0 !important;
    margin: 0 0 10px !important;
    padding: 12px !important;
    box-shadow: none;
  }
  .sign-docx :deep(section.sign-docx article),
  .sign-docx :deep(section.sign-docx article > p),
  .sign-docx :deep(section.sign-docx article > table) {
    width: 100% !important;
    min-width: 0 !important;
  }
  .sign-docx :deep(section.sign-docx table) {
    table-layout: fixed !important;
    width: 100% !important;
  }
  .sign-docx :deep(section.sign-docx td) {
    width: auto !important;
    word-break: break-word;
  }
  .sign-card { padding: 16px; }
  .sign-foot { padding: 0 16px; flex-direction: column; gap: 4px; }
}
</style>
