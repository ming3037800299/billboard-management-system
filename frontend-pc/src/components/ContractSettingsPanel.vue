<script setup>
/**
 * 签约设置：
 *   1. 甲方签约主体与签章（生成合同时自动嵌入签章）；
 *   2. Word 合同模板管理（上传 .docx，支持 {{字段}} 和 {{@图片字段}} 占位符）。
 */
import { onMounted, reactive, ref } from 'vue'
import { api, getToken } from '../api'
import { toast } from '../utils/toast'

const emit = defineEmits(['unauthorized'])

const loading = ref(false)
const saving = ref(false)
const templates = ref([])
const settings = ref(null)
const sealPreview = ref('')
const sealFile = ref(null)
const sealInput = ref(null)
const removeSeal = ref(false)

const form = reactive({
  partyAName: '',
  partyACreditCode: '',
  partyAAddress: '',
  partyALegalRep: '',
  partyAContact: '',
  partyAPhone: '',
  partyABankName: '',
  partyABankAccount: '',
  signLinkTtlDays: 7,
})

const templateForm = reactive({ name: '', setDefault: false, file: null })
const templateInput = ref(null)
const uploading = ref(false)

function handleError(e, fallback) {
  if (e && e.status === 401) {
    emit('unauthorized')
    toast('登录已过期，请重新验证密码', 'error')
    return
  }
  toast((e && e.message) || fallback, 'error')
}

async function loadAll() {
  loading.value = true
  try {
    const [s, t] = await Promise.all([api.contractSettings(), api.contractTemplates()])
    settings.value = s
    Object.assign(form, {
      partyAName: s.partyAName || '',
      partyACreditCode: s.partyACreditCode || '',
      partyAAddress: s.partyAAddress || '',
      partyALegalRep: s.partyALegalRep || '',
      partyAContact: s.partyAContact || '',
      partyAPhone: s.partyAPhone || '',
      partyABankName: s.partyABankName || '',
      partyABankAccount: s.partyABankAccount || '',
      signLinkTtlDays: s.signLinkTtlDays || 7,
    })
    templates.value = t
    sealPreview.value = s.sealDataUrl || ''
    sealFile.value = null
    removeSeal.value = false
    if (sealInput.value) sealInput.value.value = ''
  } catch (e) {
    handleError(e, '签约设置加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

function onSealChange(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/jpg'].includes(file.type)) {
    toast('签章仅支持 PNG / JPG 图片', 'error')
    event.target.value = ''
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    toast('签章图片不能超过 5MB', 'error')
    event.target.value = ''
    return
  }
  sealFile.value = file
  removeSeal.value = false
  if (sealPreview.value?.startsWith('blob:')) URL.revokeObjectURL(sealPreview.value)
  sealPreview.value = URL.createObjectURL(file)
}

function clearSeal() {
  sealFile.value = null
  removeSeal.value = true
  sealPreview.value = ''
  if (sealInput.value) sealInput.value.value = ''
}

async function saveSettings() {
  if (!form.partyAName.trim()) {
    toast('请填写甲方名称', 'error')
    return
  }
  saving.value = true
  try {
    const fd = new FormData()
    fd.append('partyAName', form.partyAName.trim())
    fd.append('partyACreditCode', form.partyACreditCode || '')
    fd.append('partyAAddress', form.partyAAddress || '')
    fd.append('partyALegalRep', form.partyALegalRep || '')
    fd.append('partyAContact', form.partyAContact || '')
    fd.append('partyAPhone', form.partyAPhone || '')
    fd.append('partyABankName', form.partyABankName || '')
    fd.append('partyABankAccount', form.partyABankAccount || '')
    fd.append('signLinkTtlDays', String(form.signLinkTtlDays || 7))
    fd.append('removeSeal', removeSeal.value ? 'true' : 'false')
    if (sealFile.value) fd.append('seal', sealFile.value)
    const data = await api.saveContractSettings(fd)
    settings.value = data
    sealPreview.value = data.sealDataUrl || ''
    sealFile.value = null
    removeSeal.value = false
    if (sealInput.value) sealInput.value.value = ''
    toast('签约设置已保存')
  } catch (e) {
    handleError(e, '签约设置保存失败')
  } finally {
    saving.value = false
  }
}

function onTemplateFile(event) {
  templateForm.file = event.target.files?.[0] || null
}

async function uploadTemplate() {
  if (!templateForm.file) {
    toast('请选择 .docx 合同模板', 'error')
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('name', templateForm.name.trim() || templateForm.file.name.replace(/\.docx$/i, ''))
    fd.append('setDefault', templateForm.setDefault ? 'true' : 'false')
    fd.append('file', templateForm.file)
    const data = await api.uploadContractTemplate(fd)
    templates.value = await api.contractTemplates()
    templateForm.name = ''
    templateForm.file = null
    templateForm.setDefault = false
    if (templateInput.value) templateInput.value.value = ''
    toast(`模板「${data.name}」已上传`)
  } catch (e) {
    handleError(e, '模板上传失败')
  } finally {
    uploading.value = false
  }
}

async function setDefaultTemplate(template) {
  try {
    await api.setDefaultContractTemplate(template.id)
    templates.value = await api.contractTemplates()
    toast('默认模板已切换')
  } catch (e) {
    handleError(e, '设置默认模板失败')
  }
}

async function deleteTemplate(template) {
  if (!window.confirm(`确认停用模板「${template.name}」？\n已生成合同使用模板副本，不受影响。`)) return
  try {
    await api.deleteContractTemplate(template.id)
    templates.value = await api.contractTemplates()
    toast('模板已停用')
  } catch (e) {
    handleError(e, '停用模板失败')
  }
}

async function downloadTemplate(template) {
  try {
    const res = await fetch(template.downloadUrl, { headers: { 'X-Admin-Token': getToken() } })
    if (!res.ok) {
      let message = `下载失败（HTTP ${res.status}）`
      try {
        const body = await res.json()
        message = body.message || message
      } catch (e) {
        // 非 JSON
      }
      throw new Error(message)
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = template.originalName || `${template.name}.docx`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 3000)
  } catch (e) {
    handleError(e, '模板下载失败')
  }
}

const TAG_SEAL = '{{@甲方签章}}'
const TAG_SIGN = '{{@乙方签名}}'
const TAG_FIELD = '{{字段}}'

const commonFields = [
  '{{合同编号}}', '{{签订日期}}', '{{签订地点}}',
  '{{甲方名称}}', '{{甲方统一社会信用代码}}', '{{甲方地址}}', '{{甲方法定代表人}}',
  '{{甲方联系人}}', '{{甲方电话}}', '{{甲方开户行}}', '{{甲方账号}}',
  '{{乙方名称}}', '{{乙方类型}}', '{{乙方证件号码}}', '{{乙方地址}}',
  '{{乙方联系人}}', '{{乙方电话}}', '{{乙方邮箱}}', '{{乙方签署人}}',
  '{{广告牌编号}}', '{{广告牌名称}}', '{{广告牌公路}}', '{{广告牌路段}}',
  '{{广告牌地址}}', '{{广告牌尺寸}}', '{{广告牌材质}}', '{{广告牌可视面积}}',
  '{{广告牌单价}}', '{{广告牌计费单位}}', '{{广告牌报价说明}}',
  '{{租赁开始日期}}', '{{租赁结束日期}}', '{{租赁时长}}',
  '{{合同金额}}', '{{合同金额大写}}', '{{定金金额}}', '{{付款方式}}',
  '{{广告内容}}', '{{特殊约定}}', '{{备注}}',
  '{{@甲方签章}}', '{{@乙方签名}}',
]
</script>

<template>
  <section class="settings-panel">
    <div v-if="loading" class="settings-loading">正在加载签约设置…</div>

    <template v-else>
      <section class="settings-card">
        <header class="settings-head">
          <div>
            <h3>甲方签约主体</h3>
            <p>生成合同时自动写入甲方信息，并把签章嵌入合同签章位。首次使用带「示例」的数据请替换为真实主体。</p>
          </div>
          <span class="settings-status" :class="settings?.hasSeal ? 'ok' : 'warn'">
            {{ settings?.hasSeal ? '签章已配置' : '尚未配置签章' }}
          </span>
        </header>

        <div class="settings-body">
          <div class="settings-grid">
            <div class="form-field span-2">
              <label>甲方名称 <em>*</em></label>
              <input v-model="form.partyAName" type="text" maxlength="200" placeholder="合同中的甲方全称">
            </div>
            <div class="form-field">
              <label>统一社会信用代码</label>
              <input v-model="form.partyACreditCode" type="text" maxlength="50">
            </div>
            <div class="form-field">
              <label>法定代表人</label>
              <input v-model="form.partyALegalRep" type="text" maxlength="100">
            </div>
            <div class="form-field">
              <label>联系人</label>
              <input v-model="form.partyAContact" type="text" maxlength="100">
            </div>
            <div class="form-field">
              <label>联系电话</label>
              <input v-model="form.partyAPhone" type="text" maxlength="50">
            </div>
            <div class="form-field span-2">
              <label>甲方地址</label>
              <input v-model="form.partyAAddress" type="text" maxlength="500">
            </div>
            <div class="form-field">
              <label>开户行</label>
              <input v-model="form.partyABankName" type="text" maxlength="200">
            </div>
            <div class="form-field">
              <label>银行账号</label>
              <input v-model="form.partyABankAccount" type="text" maxlength="100">
            </div>
            <div class="form-field">
              <label>签署链接有效期（天）</label>
              <input v-model.number="form.signLinkTtlDays" type="number" min="1" max="365">
            </div>
          </div>

          <div class="seal-block">
            <div class="seal-preview">
              <img v-if="sealPreview" :src="sealPreview" alt="甲方签章">
              <div v-else class="seal-empty">未配置签章</div>
            </div>
            <div class="seal-meta">
              <strong>甲方电子签章</strong>
              <p>建议使用透明底 PNG（300×300 左右），生成合同时会嵌入到「甲方签章」位置。当前示例章带「示例签章」字样，正式使用请替换。</p>
              <div class="seal-actions">
                <label class="btn btn-ghost btn-sm">
                  选择签章图片
                  <input ref="sealInput" type="file" accept=".png,.jpg,.jpeg" hidden @change="onSealChange">
                </label>
                <button v-if="sealPreview" class="btn btn-danger btn-sm" type="button" @click="clearSeal">移除签章</button>
              </div>
            </div>
          </div>
        </div>

        <footer class="settings-foot">
          <span>保存后立即用于新生成的合同；已生成合同的签章副本不会被改变。</span>
          <button class="btn btn-primary" type="button" :disabled="saving" @click="saveSettings">
            {{ saving ? '保存中…' : '保存签约设置' }}
          </button>
        </footer>
      </section>

      <section class="settings-card">
        <header class="settings-head">
          <div>
            <h3>合同 Word 模板</h3>
            <p>上传 .docx 模板，系统按占位符自动填充广告牌、乙方与商务条款，并嵌入甲乙双方签章/签名。</p>
          </div>
          <span class="settings-status">{{ templates.length }} 个模板</span>
        </header>

        <div class="settings-body">
          <div class="template-upload">
            <div class="form-field">
              <label>模板名称</label>
              <input v-model="templateForm.name" type="text" maxlength="200" placeholder="如：新疆区域广告牌租赁合同 2026 版">
            </div>
            <div class="form-field">
              <label>.docx 模板文件 <em>*</em></label>
              <input ref="templateInput" type="file" accept=".docx" @change="onTemplateFile">
            </div>
            <label class="admin-check template-default-check">
              <input v-model="templateForm.setDefault" type="checkbox">
              <span>上传后设为默认模板</span>
            </label>
            <button class="btn btn-primary" type="button" :disabled="uploading" @click="uploadTemplate">
              {{ uploading ? '上传中…' : '上传模板' }}
            </button>
          </div>

          <div class="template-hint">
            <strong>模板要求：</strong>
            必须包含图片占位符 <code>{{ TAG_SEAL }}</code> 与 <code>{{ TAG_SIGN }}</code>，
            其他字段使用 <code>{{ TAG_FIELD }}</code>。占位符请放在同一个 Word 文本段内，避免被 Word 拆成多段格式。
          </div>

          <div class="template-list">
            <div v-for="t in templates" :key="t.id" class="template-row" :class="{ disabled: !t.enabled }">
              <div class="template-main">
                <div class="template-title">
                  <strong>{{ t.name }}</strong>
                  <span v-if="t.defaultTemplate" class="template-badge">默认</span>
                  <span v-if="!t.enabled" class="template-badge off">已停用</span>
                </div>
                <div class="template-meta">
                  {{ t.originalName || '未记录文件名' }} · 识别到 {{ t.placeholders?.length || 0 }} 个占位符
                </div>
                <div class="template-placeholders">
                  <span v-for="p in (t.placeholders || []).slice(0, 8)" :key="p" class="ph-chip">{{ p }}</span>
                  <span v-if="(t.placeholders || []).length > 8" class="ph-more">
                    +{{ t.placeholders.length - 8 }}
                  </span>
                </div>
              </div>
              <div class="template-actions">
                <button class="link-btn" type="button" @click="downloadTemplate(t)">下载</button>
                <button
                  v-if="t.enabled && !t.defaultTemplate"
                  class="link-btn"
                  type="button"
                  @click="setDefaultTemplate(t)"
                >设为默认</button>
                <button
                  v-if="t.enabled"
                  class="link-btn danger"
                  type="button"
                  @click="deleteTemplate(t)"
                >停用</button>
              </div>
            </div>
            <div v-if="!templates.length" class="template-empty">暂无模板，上传一个 .docx 后即可生成合同。</div>
          </div>

          <details class="field-guide">
            <summary>查看支持的合同字段（{{ commonFields.length }} 个）</summary>
            <div class="field-guide-list">
              <span v-for="f in commonFields" :key="f" class="ph-chip">{{ f }}</span>
            </div>
          </details>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.settings-panel { display: flex; flex-direction: column; gap: 16px; }
.settings-loading {
  background: #fff;
  border: 1px solid var(--border-light);
  padding: 40px;
  text-align: center;
  color: var(--text-3);
}
.settings-card {
  background: var(--panel);
  border: 1px solid var(--border-light);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
.settings-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-light);
}
.settings-head h3 { font-size: 15px; margin-bottom: 4px; }
.settings-head p { color: var(--text-3); font-size: 12px; max-width: 760px; line-height: 1.6; }
.settings-status {
  flex: 0 0 auto;
  font-size: 12px;
  padding: 3px 9px;
  border-radius: 11px;
  background: #eef2f7;
  color: var(--text-2);
}
.settings-status.ok { background: #eaf7ef; color: var(--green); }
.settings-status.warn { background: #fff6e8; color: var(--amber); }
.settings-body { padding: 18px 20px; }
.settings-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 18px; }
.settings-grid .span-2 { grid-column: span 2; }
.settings-grid .form-field { display: flex; flex-direction: column; gap: 5px; }
.settings-grid label { font-size: 12px; color: var(--text-2); }
.settings-grid label em { color: var(--red); font-style: normal; }
.settings-grid input,
.template-upload input[type="text"],
.seal-actions input {
  height: 32px;
  border: 1px solid var(--border);
  border-radius: 3px;
  padding: 0 10px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.settings-grid input:focus,
.template-upload input[type="text"]:focus { border-color: var(--primary); }

.seal-block {
  display: flex;
  gap: 18px;
  align-items: center;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--border-light);
}
.seal-preview {
  width: 132px;
  height: 132px;
  flex: 0 0 auto;
  border: 1px dashed var(--border);
  border-radius: 4px;
  background: #fbfcfd;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
}
.seal-preview img { max-width: 100%; max-height: 100%; object-fit: contain; }
.seal-empty { color: var(--text-3); font-size: 12px; }
.seal-meta strong { font-size: 13px; }
.seal-meta p { color: var(--text-3); font-size: 12px; line-height: 1.65; margin: 5px 0 10px; max-width: 700px; }
.seal-actions { display: flex; gap: 8px; align-items: center; }
.seal-actions .btn { position: relative; overflow: hidden; }

.settings-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 20px;
  border-top: 1px solid var(--border-light);
  background: #fbfcfd;
  color: var(--text-3);
  font-size: 12px;
}

.template-upload {
  display: grid;
  grid-template-columns: 1.2fr 1.4fr auto auto;
  gap: 12px;
  align-items: end;
  padding-bottom: 16px;
  border-bottom: 1px dashed var(--border-light);
}
.template-upload .form-field { display: flex; flex-direction: column; gap: 5px; }
.template-upload label { font-size: 12px; color: var(--text-2); }
.template-upload label em { color: var(--red); font-style: normal; }
.template-upload input[type="file"] { font-size: 12px; }
.template-default-check {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  font-size: 12px;
  color: var(--text-2);
  white-space: nowrap;
}
.template-hint {
  margin: 14px 0;
  padding: 10px 12px;
  background: #f5f9ff;
  border: 1px solid var(--primary-border);
  border-radius: 3px;
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.7;
}
.template-hint code,
.field-guide code {
  background: #eef3fa;
  color: var(--primary);
  padding: 1px 5px;
  border-radius: 3px;
  font-family: Consolas, monospace;
}
.template-list { display: flex; flex-direction: column; gap: 8px; }
.template-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid var(--border-light);
  border-radius: 4px;
  padding: 12px 14px;
}
.template-row.disabled { opacity: 0.58; background: #fafafa; }
.template-main { min-width: 0; }
.template-title { display: flex; align-items: center; gap: 8px; }
.template-title strong { font-size: 13px; }
.template-badge {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 9px;
  background: var(--primary-light);
  color: var(--primary);
}
.template-badge.off { background: #f1f3f5; color: var(--text-3); }
.template-meta { color: var(--text-3); font-size: 12px; margin: 4px 0 6px; }
.template-placeholders { display: flex; flex-wrap: wrap; gap: 5px; }
.ph-chip {
  font-family: Consolas, monospace;
  font-size: 11px;
  color: var(--text-2);
  background: #f3f5f8;
  border: 1px solid var(--border-light);
  border-radius: 3px;
  padding: 1px 5px;
}
.ph-more { color: var(--text-3); font-size: 11px; }
.template-actions { display: flex; gap: 10px; white-space: nowrap; padding-top: 2px; }
.template-empty { padding: 24px; text-align: center; color: var(--text-3); border: 1px dashed var(--border); }
.field-guide { margin-top: 14px; font-size: 12px; color: var(--text-2); }
.field-guide summary { cursor: pointer; color: var(--primary); }
.field-guide-list { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }

@media (max-width: 900px) {
  .settings-grid { grid-template-columns: 1fr; }
  .settings-grid .span-2 { grid-column: span 1; }
  .seal-block { flex-direction: column; align-items: flex-start; }
  .template-upload { grid-template-columns: 1fr; }
  .template-row { flex-direction: column; }
  .template-actions { padding-top: 0; }
  .settings-foot { flex-direction: column; align-items: stretch; }
}
</style>
