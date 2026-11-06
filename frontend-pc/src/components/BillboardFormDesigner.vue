<script setup>
/**
 * 广告牌表单设计器。
 *
 * - 字段库：把未放入表单的扩展字段添加到指定分区
 * - 画布：拖拽 / 上下移动字段，调整分区、栏宽、必填、控件类型与下拉选项
 * - 预览：按同一套字段配置渲染只读表单
 * - 保存：布局写入 billboard_field；控件类型 / 选项等变更按需同步数据库结构
 *
 * 所有修改先保存在本地草稿中，点击“保存设计”后统一落库，避免边设计边执行 DDL。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { toast } from '../utils/toast'

const props = defineProps({
  columns: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  ownerModuleId: { type: [Number, String], default: null },
})

const emit = defineEmits(['refresh', 'fields-changed', 'unauthorized'])

const FORM_SECTIONS = [
  { value: 'basic', label: '基本信息' },
  { value: 'location', label: '位置坐标' },
  { value: 'price', label: '租赁报价' },
  { value: 'params', label: '广告位参数' },
  { value: 'extension', label: '扩展信息' },
]

const INPUT_TYPES = [
  { value: 'TEXT', label: '单行文本' },
  { value: 'TEXTAREA', label: '多行文本' },
  { value: 'INTEGER', label: '整数' },
  { value: 'DECIMAL', label: '小数' },
  { value: 'DATE', label: '日期' },
  { value: 'DATETIME', label: '日期时间' },
  { value: 'SELECT', label: '下拉选项' },
  { value: 'BOOLEAN', label: '是 / 否' },
]

const SYSTEM_FORM_EXCLUDES = new Set(['id', 'deleted', 'created_at', 'updated_at'])

const EDITABLE_KEYS = [
  'label',
  'inputType',
  'length',
  'decimals',
  'required',
  'nullable',
  'enabled',
  'section',
  'width',
  'defaultValue',
  'options',
  'placeholder',
  'sortOrder',
  'visible',
]

/** 会修改数据库列定义（执行 ALTER TABLE）的字段属性。 */
const STRUCTURAL_KEYS = ['inputType', 'length', 'decimals']

/** 需要走完整字段更新接口（而不是轻量布局接口）的属性。 */
const FIELD_CONFIG_KEYS = [
  'label',
  'inputType',
  'length',
  'decimals',
  'defaultValue',
  'options',
  'placeholder',
]

const mode = ref('edit')
const fields = ref([])
const originalFields = ref([])
const libraryFilter = ref('')
const addSection = ref('extension')

const dragId = ref(null)
const dropBeforeId = ref(null)
const dropEndSection = ref(null)

const saving = ref(false)
const showOptions = ref(false)
const optionsTargetId = ref(null)
const optionsDraft = ref([])

const showNewField = ref(false)
const creating = ref(false)
const newField = reactive(emptyNewField())

const enabledFields = computed(() => fields.value.filter((f) => f.enabled))
const hiddenFields = computed(() => fields.value
  .filter((f) => !f.enabled)
  .sort((a, b) => (a.label || '').localeCompare(b.label || '', 'zh-CN')))

const filteredHiddenFields = computed(() => {
  const kw = libraryFilter.value.trim().toLowerCase()
  if (!kw) return hiddenFields.value
  return hiddenFields.value.filter((f) =>
    (f.label || '').toLowerCase().includes(kw)
    || (f.name || '').toLowerCase().includes(kw)
    || (f.type || '').toLowerCase().includes(kw))
})

const sections = computed(() => FORM_SECTIONS.map((section) => ({
  ...section,
  coreFields: props.columns
    .filter((c) => c.origin === 'SYSTEM'
      && c.section === section.value
      && !SYSTEM_FORM_EXCLUDES.has((c.name || '').toLowerCase()))
    .sort((a, b) => (a.name || '').localeCompare(b.name || '')),
  fields: fields.value
    .filter((f) => f.enabled && f.section === section.value)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label, 'zh-CN')),
})))

const previewFieldCount = computed(() => sections.value
  .reduce((total, section) => total + section.fields.length, 0))

const dirty = computed(() => snapshot(fields.value) !== snapshot(originalFields.value))

function emptyNewField() {
  return {
    label: '',
    fieldName: '',
    inputType: 'TEXT',
    length: 200,
    decimals: 2,
    required: false,
    visible: true,
    section: 'extension',
    width: 'half',
    placeholder: '',
    optionsText: '',
  }
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function toDraft(column) {
  return {
    customFieldId: column.customFieldId,
    origin: 'CUSTOM',
    name: column.name,
    label: column.label || column.name,
    inputType: column.inputType || 'TEXT',
    length: column.length == null ? null : Number(column.length),
    decimals: column.decimals == null ? null : Number(column.decimals),
    required: !!column.required,
    nullable: column.nullable !== false,
    enabled: column.enabled !== false,
    section: FORM_SECTIONS.some((s) => s.value === column.section) ? column.section : 'extension',
    width: column.width === 'full' ? 'full' : 'half',
    defaultValue: column.defaultValue == null ? '' : String(column.defaultValue),
    options: Array.isArray(column.options) ? [...column.options] : [],
    placeholder: column.placeholder || '',
    sortOrder: Number(column.sortOrder) || 0,
    visible: column.visible !== false,
    moduleName: column.moduleName || '',
    type: column.type || '',
    comment: column.comment || '',
  }
}

function snapshot(list) {
  return JSON.stringify([...list]
    .sort((a, b) => Number(a.customFieldId) - Number(b.customFieldId))
    .map((field) => EDITABLE_KEYS.map((key) => {
      const value = field[key]
      return Array.isArray(value) ? [...value] : value
    })))
}

function syncFromColumns(columns) {
  const drafts = (columns || [])
    .filter((c) => c.origin === 'CUSTOM')
    .map(toDraft)
  fields.value = drafts
  originalFields.value = clone(drafts)
}

watch(
  () => props.columns,
  (value) => syncFromColumns(value),
  { deep: true, immediate: true },
)

function typeLabel(value) {
  return INPUT_TYPES.find((type) => type.value === value)?.label || value
}

function inputTypeOf(field) {
  if (field.inputType === 'DATE') return 'date'
  if (field.inputType === 'DATETIME') return 'datetime-local'
  if (field.inputType === 'INTEGER' || field.inputType === 'DECIMAL') return 'number'
  return 'text'
}

function sectionFields(section) {
  return fields.value
    .filter((f) => f.enabled && f.section === section)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label, 'zh-CN'))
}

function renumberSection(section) {
  sectionFields(section).forEach((field, index) => {
    field.sortOrder = (index + 1) * 10
  })
}

function moveFieldToSection(field, targetSection, beforeField = null) {
  if (!field || !FORM_SECTIONS.some((section) => section.value === targetSection)) return
  if (beforeField && beforeField.customFieldId === field.customFieldId) return

  const sourceSection = field.section
  const sourceList = sectionFields(sourceSection).filter((item) => item !== field)
  const targetList = targetSection === sourceSection
    ? sourceList
    : sectionFields(targetSection)

  let index = targetList.length
  if (beforeField) {
    const beforeIndex = targetList.findIndex((item) => item.customFieldId === beforeField.customFieldId)
    if (beforeIndex >= 0) index = beforeIndex
  }

  field.enabled = true
  field.section = targetSection
  targetList.splice(index, 0, field)
  targetList.forEach((item, itemIndex) => {
    item.sortOrder = (itemIndex + 1) * 10
  })

  if (sourceSection !== targetSection) {
    renumberSection(sourceSection)
  }
}

function onSectionChange(field, section) {
  const previous = field.section
  if (previous === section) return
  field.section = previous
  moveFieldToSection(field, section, null)
}

function onTypeChange(field) {
  if (field.inputType === 'SELECT') {
    if (!field.length) field.length = 100
    if (!field.options.length) openOptions(field)
  }
}

function addField(field) {
  moveFieldToSection(field, addSection.value, null)
  toast(`已放入「${FORM_SECTIONS.find((s) => s.value === addSection.value)?.label || '表单'}」，保存设计后生效`)
}

function removeField(field) {
  field.enabled = false
  toast('已从表单移除；数据库列和已有数据仍然保留')
}

function shiftField(field, direction) {
  const list = sectionFields(field.section)
  const index = list.findIndex((item) => item.customFieldId === field.customFieldId)
  const target = index + direction
  if (index < 0 || target < 0 || target >= list.length) return
  list.splice(index, 1)
  list.splice(target, 0, field)
  list.forEach((item, itemIndex) => {
    item.sortOrder = (itemIndex + 1) * 10
  })
}

/* ---------------- 拖拽排序 ---------------- */

function onDragStart(field, event) {
  dragId.value = field.customFieldId
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    try {
      event.dataTransfer.setData('text/plain', String(field.customFieldId))
    } catch (e) {
      /* 部分浏览器不允许自定义 drag data，状态已保存在 dragId */
    }
  }
}

function clearDragState() {
  dragId.value = null
  dropBeforeId.value = null
  dropEndSection.value = null
}

function onFieldDragOver(field, event) {
  if (!dragId.value) return
  event.preventDefault()
  event.stopPropagation()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dropBeforeId.value = field.customFieldId
  dropEndSection.value = null
}

function onFieldDrop(section, targetField, event) {
  event.preventDefault()
  event.stopPropagation()
  const field = fields.value.find((item) => item.customFieldId === dragId.value)
  if (field) moveFieldToSection(field, section.value, targetField)
  clearDragState()
}

function onSectionDragOver(section, event) {
  if (!dragId.value) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dropBeforeId.value = null
  dropEndSection.value = section.value
}

function onSectionDrop(section, event) {
  event.preventDefault()
  const field = fields.value.find((item) => item.customFieldId === dragId.value)
  if (field) moveFieldToSection(field, section.value, null)
  clearDragState()
}

/* ---------------- 下拉选项 ---------------- */

function openOptions(field) {
  optionsTargetId.value = field.customFieldId
  optionsDraft.value = field.options?.length ? [...field.options] : ['']
  showOptions.value = true
}

function addOption() {
  optionsDraft.value.push('')
}

function removeOption(index) {
  optionsDraft.value.splice(index, 1)
  if (!optionsDraft.value.length) optionsDraft.value.push('')
}

function moveOption(index, direction) {
  const target = index + direction
  if (target < 0 || target >= optionsDraft.value.length) return
  const [option] = optionsDraft.value.splice(index, 1)
  optionsDraft.value.splice(target, 0, option)
}

function saveOptions() {
  const cleaned = optionsDraft.value.map((option) => String(option || '').trim()).filter(Boolean)
  if (!cleaned.length) {
    toast('下拉字段至少需要一个选项', 'error')
    return
  }
  const duplicate = cleaned.find((option, index) => cleaned.indexOf(option) !== index)
  if (duplicate) {
    toast(`下拉选项重复：${duplicate}`, 'error')
    return
  }
  const target = fields.value.find((field) => field.customFieldId === optionsTargetId.value)
  if (!target) return
  target.options = cleaned
  showOptions.value = false
  toast('下拉选项已更新，点击“保存设计”后写入数据库')
}

/* ---------------- 新增字段 ---------------- */

function openNewField() {
  if (dirty.value) {
    toast('请先保存当前表单设计，再新增字段', 'error')
    return
  }
  Object.assign(newField, emptyNewField())
  newField.section = addSection.value
  showNewField.value = true
}

function onNewTypeChange() {
  if (newField.inputType === 'TEXT') {
    newField.length = 200
    newField.decimals = null
  } else if (newField.inputType === 'SELECT') {
    newField.length = 100
    newField.decimals = null
  } else if (newField.inputType === 'DECIMAL') {
    newField.length = 18
    newField.decimals = 2
  } else {
    newField.length = null
    newField.decimals = null
  }
}

function suggestFieldName() {
  const raw = String(newField.label || '').trim().toLowerCase()
  let base = raw.replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '')
  if (!/^[a-z]/.test(base)) base = `ext_${Date.now().toString(36)}`
  newField.fieldName = base.slice(0, 50)
}

async function createField() {
  if (creating.value) return
  if (!newField.label.trim()) {
    toast('请填写字段显示名称', 'error')
    return
  }
  if (!newField.fieldName.trim()) suggestFieldName()
  if (!/^[a-z][a-z0-9_]{1,62}$/.test(newField.fieldName.trim())) {
    toast('字段名需以小写字母开头，只能包含小写字母、数字和下划线（2-63 位）', 'error')
    return
  }
  if (!props.ownerModuleId) {
    toast('未找到广告牌字段所属子模块，无法新增字段', 'error')
    return
  }
  if (newField.inputType === 'SELECT' && !parseOptionsText(newField.optionsText).length) {
    toast('下拉字段至少需要一个选项，每行一个', 'error')
    return
  }

  creating.value = true
  try {
    const maxSort = fields.value.reduce((max, field) => Math.max(max, Number(field.sortOrder) || 0), 0)
    const payload = {
      label: newField.label.trim(),
      fieldName: newField.fieldName.trim(),
      inputType: newField.inputType,
      length: newField.length === '' ? null : newField.length,
      decimals: newField.decimals === '' ? null : newField.decimals,
      required: newField.required,
      nullable: true,
      enabled: true,
      section: newField.section,
      width: newField.width,
      defaultValue: '',
      options: newField.inputType === 'SELECT' ? parseOptionsText(newField.optionsText) : [],
      placeholder: newField.placeholder,
      sortOrder: maxSort + 10,
      visible: newField.visible,
    }
    await api.dbAddField(props.ownerModuleId, payload)
    toast('字段已新增并放入表单，数据库列已同步')
    showNewField.value = false
    emit('refresh')
    emit('fields-changed')
  } catch (e) {
    handleError(e, '新增字段失败')
  } finally {
    creating.value = false
  }
}

function parseOptionsText(text) {
  return String(text || '').split(/\r?\n/).map((option) => option.trim()).filter(Boolean)
}

/* ---------------- 保存 ---------------- */

function fieldConfigChanged(current, original) {
  return FIELD_CONFIG_KEYS.some((key) =>
    JSON.stringify(current[key]) !== JSON.stringify(original[key]))
}

function layoutChanged(current, original) {
  return ['required', 'enabled', 'section', 'width', 'sortOrder', 'visible']
    .some((key) => current[key] !== original[key])
}

function fieldPayload(field) {
  return {
    label: field.label,
    fieldName: field.name,
    inputType: field.inputType,
    length: field.length,
    decimals: field.decimals,
    required: field.required,
    nullable: field.nullable !== false,
    enabled: field.enabled,
    section: field.section,
    width: field.width,
    defaultValue: field.defaultValue,
    options: field.options,
    placeholder: field.placeholder,
    sortOrder: field.sortOrder,
    visible: field.visible,
  }
}

function layoutPayload(field) {
  return {
    enabled: field.enabled,
    section: field.section,
    width: field.width,
    sortOrder: field.sortOrder,
    required: field.required,
    visible: field.visible,
  }
}

function validateDesign() {
  for (const field of fields.value) {
    if (!field.enabled) continue
    if (!String(field.label || '').trim()) {
      toast('存在没有显示名称的字段，请先补全', 'error')
      return false
    }
    if (field.inputType === 'SELECT' && !field.options.length) {
      toast(`下拉字段「${field.label}」至少需要一个选项`, 'error')
      openOptions(field)
      return false
    }
  }
  return true
}

async function saveDesign() {
  if (saving.value) return
  if (!validateDesign()) return

  const originalById = new Map(originalFields.value.map((field) => [field.customFieldId, field]))
  const changed = fields.value
    .map((field) => ({ field, original: originalById.get(field.customFieldId) }))
    .filter(({ field, original }) => !!original
      && (fieldConfigChanged(field, original) || layoutChanged(field, original)))

  if (!changed.length) {
    toast('没有需要保存的修改')
    return
  }

  const structural = changed.filter(({ field, original }) =>
    STRUCTURAL_KEYS.some((key) => field[key] !== original[key]))
  if (structural.length) {
    const nl = String.fromCharCode(10)
    const detail = structural
      .map(({ field, original }) => `· ${field.label}：${typeLabel(original.inputType)} → ${typeLabel(field.inputType)}`)
      .join(nl)
    const ok = window.confirm(
      `有 ${structural.length} 个字段将修改数据库列类型 / 长度。` + nl + nl
      + detail + nl + nl
      + '保存后会执行 ALTER TABLE，已有数据会按新类型转换，可能丢失精度或格式。确认继续？',
    )
    if (!ok) return
  }

  saving.value = true
  try {
    for (const { field, original } of changed) {
      if (fieldConfigChanged(field, original)) {
        await api.dbUpdateField(field.customFieldId, fieldPayload(field))
      } else {
        await api.dbUpdateFieldLayout(field.customFieldId, layoutPayload(field))
      }
    }
    originalFields.value = clone(fields.value)
    toast(`表单设计已保存（${changed.length} 个字段）`)
    emit('refresh')
    emit('fields-changed')
  } catch (e) {
    handleError(e, '表单设计保存失败')
    // 部分字段可能已经保存，重新拉取一次，保证界面与数据库一致
    emit('refresh')
  } finally {
    saving.value = false
  }
}

function resetDraft() {
  if (!dirty.value) return
  if (!window.confirm('放弃未保存的表单设计修改？')) return
  syncFromColumns(props.columns)
  toast('已恢复为上次保存的设计')
}

function refresh() {
  if (saving.value) return
  if (dirty.value && !window.confirm('刷新会放弃未保存的修改，确定继续？')) return
  emit('refresh')
}

function handleError(e, fallback) {
  if (e?.status === 401) {
    emit('unauthorized')
    return
  }
  toast(e?.message || fallback, 'error')
}

function onKeydown(e) {
  if (e.key !== 'Escape') return
  if (showOptions.value) {
    showOptions.value = false
    return
  }
  if (showNewField.value) {
    showNewField.value = false
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="db-fdesigner">
    <div class="db-toolbar db-fdesigner-toolbar">
      <div class="db-field-summary">
        从左侧字段库放字段，拖动 <span class="db-fdesigner-grip-inline">⋮⋮</span> 调整位置，
        并为每个字段设置必填、控件类型和下拉选项。
        <span v-if="dirty" class="db-fdesigner-dirty">有未保存修改</span>
      </div>
      <div class="db-toolbar-actions db-fdesigner-toolbar-actions">
        <div class="db-fdesigner-mode-switch">
          <button
            id="db-designer-edit-mode"
            type="button"
            :class="{ active: mode === 'edit' }"
            @click="mode = 'edit'"
          >
            编辑设计
          </button>
          <button
            id="db-designer-preview-mode"
            type="button"
            :class="{ active: mode === 'preview' }"
            @click="mode = 'preview'"
          >
            预览效果
          </button>
        </div>
        <button
          id="db-designer-new-field"
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="saving"
          @click="openNewField"
        >
          + 新增字段
        </button>
        <button
          v-if="mode === 'edit'"
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="!dirty || saving"
          @click="resetDraft"
        >
          撤销修改
        </button>
        <button
          v-if="mode === 'edit'"
          id="db-designer-save"
          class="btn btn-primary btn-sm"
          type="button"
          :disabled="!dirty || saving"
          @click="saveDesign"
        >
          {{ saving ? '保存中…' : '保存设计' }}
        </button>
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading || saving" @click="refresh">
          刷新
        </button>
      </div>
    </div>

    <div v-if="loading" class="db-empty-hint">正在读取表单布局…</div>

    <div v-else class="db-fdesigner-workspace" :class="{ 'mode-preview': mode === 'preview' }">
      <aside v-if="mode === 'edit'" class="db-fdesigner-library">
        <div class="db-fdesigner-pane-head">
          <strong>字段库</strong>
          <span>{{ hiddenFields.length }} 个未放入</span>
        </div>
        <p class="db-action-hint">
          系统字段固定显示；这里管理扩展字段。移除字段不会删除数据库列。
        </p>
        <label class="db-fdesigner-inline-label" for="db-designer-add-section">
          放入分区
          <select id="db-designer-add-section" v-model="addSection" class="db-manager-select">
            <option v-for="section in FORM_SECTIONS" :key="section.value" :value="section.value">
              {{ section.label }}
            </option>
          </select>
        </label>
        <input
          v-model="libraryFilter"
          type="search"
          class="db-fdesigner-library-search"
          placeholder="搜索字段名称 / 数据库列"
        >
        <div class="db-fdesigner-library-list">
          <div
            v-for="field in filteredHiddenFields"
            :key="field.customFieldId"
            class="db-fdesigner-library-item"
            :data-library-field="field.name"
          >
            <div class="db-fdesigner-library-main">
              <strong>{{ field.label }}</strong>
              <span>{{ field.name }} · {{ typeLabel(field.inputType) }}</span>
            </div>
            <button
              class="link-btn"
              type="button"
              :data-action="'add-' + field.name"
              @click="addField(field)"
            >
              + 放入
            </button>
          </div>
          <div v-if="!filteredHiddenFields.length" class="db-fdesigner-empty-box">
            {{ hiddenFields.length ? '没有匹配的字段' : '所有扩展字段都已放入表单' }}
          </div>
        </div>
        <button class="btn btn-ghost btn-sm db-fdesigner-library-add" type="button" @click="openNewField">
          + 新建扩展字段
        </button>
      </aside>

      <main v-if="mode === 'edit'" class="db-designer-sections db-fdesigner-canvas">
        <section
          v-for="section in sections"
          :key="section.value"
          class="db-designer-card db-fdesigner-section"
          :class="{ 'is-drop-target': dropEndSection === section.value }"
          :data-section="section.value"
          @dragover="onSectionDragOver(section, $event)"
          @drop="onSectionDrop(section, $event)"
        >
          <div class="db-designer-card-head">
            <strong>{{ section.label }}</strong>
            <span class="db-action-hint">
              {{ section.fields.length }} 个扩展字段
              <template v-if="section.coreFields.length"> · {{ section.coreFields.length }} 个系统字段</template>
            </span>
          </div>
          <div class="db-designer-core">
            <span
              v-for="field in section.coreFields"
              :key="field.name"
              class="db-designer-chip"
              :title="field.comment || field.name"
            >
              {{ field.label }} <em>固定</em>
            </span>
            <span v-if="!section.coreFields.length" class="db-action-hint">无系统字段</span>
          </div>

          <div class="db-fdesigner-field-list">
            <div
              v-for="field in section.fields"
              :key="field.customFieldId"
              class="db-designer-field db-fdesigner-field"
              :class="{
                'is-dragging': dragId === field.customFieldId,
                'is-drop-before': dropBeforeId === field.customFieldId,
              }"
              :data-field="field.name"
              @dragover="onFieldDragOver(field, $event)"
              @drop="onFieldDrop(section, field, $event)"
            >
              <div class="db-fdesigner-field-head">
                <span
                  class="db-fdesigner-drag"
                  draggable="true"
                  title="拖动调整字段位置"
                  @dragstart="onDragStart(field, $event)"
                  @dragend="clearDragState"
                >⋮⋮</span>
                <strong>{{ field.label }}</strong>
                <span class="db-fdesigner-type-badge">{{ typeLabel(field.inputType) }}</span>
                <span class="db-table-comment">{{ field.name }}</span>
                <span v-if="field.required" class="db-fdesigner-required-badge">必填</span>
              </div>

              <div class="db-fdesigner-field-settings">
                <label class="admin-check">
                  <input v-model="field.required" type="checkbox" data-role="required">
                  <span>必填</span>
                </label>
                <label class="admin-check">
                  <input v-model="field.visible" type="checkbox" data-role="visible">
                  <span>前台展示</span>
                </label>
                <label class="db-fdesigner-inline-label">
                  分区
                  <select
                    class="db-manager-select"
                    :value="field.section"
                    data-role="section"
                    @change="onSectionChange(field, $event.target.value)"
                  >
                    <option v-for="item in FORM_SECTIONS" :key="item.value" :value="item.value">
                      {{ item.label }}
                    </option>
                  </select>
                </label>
                <label class="db-fdesigner-inline-label">
                  类型
                  <select
                    class="db-manager-select"
                    :value="field.inputType"
                    data-role="type"
                    @change="field.inputType = $event.target.value; onTypeChange(field)"
                  >
                    <option v-for="type in INPUT_TYPES" :key="type.value" :value="type.value">
                      {{ type.label }}
                    </option>
                  </select>
                </label>
                <label class="db-fdesigner-inline-label">
                  栏宽
                  <select v-model="field.width" class="db-manager-select" data-role="width">
                    <option value="half">半栏</option>
                    <option value="full">整行</option>
                  </select>
                </label>
                <button
                  v-if="field.inputType === 'SELECT'"
                  class="link-btn db-fdesigner-option-btn"
                  type="button"
                  data-action="options"
                  @click="openOptions(field)"
                >
                  选项 ({{ field.options.length }})
                </button>
                <span class="db-fdesigner-field-spacer"></span>
                <button class="link-btn" type="button" title="上移" data-action="up" @click="shiftField(field, -1)">↑</button>
                <button class="link-btn" type="button" title="下移" data-action="down" @click="shiftField(field, 1)">↓</button>
                <button class="link-btn danger" type="button" data-action="remove" @click="removeField(field)">
                  从表单移除
                </button>
              </div>
            </div>

            <div
              v-if="!section.fields.length"
              class="db-fdesigner-dropzone"
              @dragover.prevent="onSectionDragOver(section, $event)"
              @drop="onSectionDrop(section, $event)"
            >
              拖拽字段到这里，或从左侧字段库放入
            </div>
          </div>
          <div v-if="dropEndSection === section.value" class="db-fdesigner-drop-line"></div>
        </section>
      </main>

      <aside class="db-designer-preview db-fdesigner-preview">
        <div class="db-designer-preview-head">
          <span>表单预览 <em>{{ previewFieldCount }} 个扩展字段</em></span>
          <span class="db-action-hint">{{ mode === 'edit' ? '后台编辑页同布局' : '只读预览' }}</span>
        </div>
        <div class="db-preview-form">
          <div v-if="mode === 'preview'" class="db-fdesigner-preview-note">
            这是用户看到的表单排版。返回“编辑设计”可继续拖拽、设置必填和下拉选项。
          </div>
          <fieldset v-for="section in sections" :key="section.value" class="db-preview-section">
            <legend>{{ section.label }}</legend>
            <div class="form-grid">
              <div
                v-for="field in section.coreFields"
                :key="'core-' + field.name"
                class="form-field"
              >
                <label>{{ field.label }}</label>
                <input type="text" disabled placeholder="系统字段">
              </div>
              <div
                v-for="field in section.fields"
                :key="'custom-' + field.name"
                class="form-field"
                :class="{ 'span-2': field.width === 'full' || field.inputType === 'TEXTAREA' }"
                :data-preview-field="field.name"
              >
                <label>
                  {{ field.label }}
                  <em v-if="field.required">*</em>
                </label>
                <select v-if="field.inputType === 'SELECT'" disabled>
                  <option value="">请选择</option>
                  <option v-for="option in field.options" :key="option" :value="option">{{ option }}</option>
                  <option v-if="!field.options.length" value="">请先在“选项”中配置</option>
                </select>
                <select v-else-if="field.inputType === 'BOOLEAN'" disabled>
                  <option value="">未选择</option>
                  <option value="true">是</option>
                  <option value="false">否</option>
                </select>
                <textarea
                  v-else-if="field.inputType === 'TEXTAREA'"
                  disabled
                  rows="2"
                  :placeholder="field.placeholder || '多行文本'"
                ></textarea>
                <input
                  v-else
                  type="text"
                  disabled
                  :placeholder="field.placeholder || typeLabel(field.inputType)"
                >
                <span v-if="field.inputType === 'SELECT' && !field.options.length" class="db-field-hint db-fdesigner-warning">
                  尚未配置下拉选项
                </span>
              </div>
            </div>
          </fieldset>
          <div v-if="!previewFieldCount" class="db-fdesigner-empty-box">
            表单还没有扩展字段，去左侧字段库放入一个吧
          </div>
        </div>
      </aside>
    </div>

    <!-- 下拉选项编辑器 -->
    <div v-if="showOptions" class="db-fdesigner-modal-mask" @click.self="showOptions = false">
      <div id="db-designer-options-modal" class="db-fdesigner-modal">
        <div class="db-fdesigner-modal-head">
          <div>
            <h4>设计下拉选项</h4>
            <p>每个选项一行，保存设计后同步到数据库中的字段定义。</p>
          </div>
          <button class="modal-close" type="button" title="关闭" @click="showOptions = false">×</button>
        </div>
        <div class="db-fdesigner-option-list">
          <div v-for="(option, index) in optionsDraft" :key="index" class="db-fdesigner-option-row">
            <span class="db-fdesigner-option-index">{{ index + 1 }}</span>
            <input
              v-model="optionsDraft[index]"
              type="text"
              class="db-fdesigner-option-input"
              maxlength="200"
              placeholder="选项文字"
              @keydown.enter.prevent="addOption"
            >
            <button class="link-btn" type="button" title="上移" @click="moveOption(index, -1)">↑</button>
            <button class="link-btn" type="button" title="下移" @click="moveOption(index, 1)">↓</button>
            <button class="link-btn danger" type="button" title="删除选项" @click="removeOption(index)">删除</button>
          </div>
        </div>
        <button id="db-option-add" class="btn btn-ghost btn-sm" type="button" @click="addOption">+ 添加选项</button>
        <div class="db-fdesigner-modal-actions">
          <button class="btn btn-ghost" type="button" @click="showOptions = false">取消</button>
          <button id="db-option-save" class="btn btn-primary" type="button" @click="saveOptions">
            应用选项
          </button>
        </div>
      </div>
    </div>

    <!-- 新增字段 -->
    <div v-if="showNewField" class="db-fdesigner-modal-mask" @click.self="showNewField = false">
      <div id="db-designer-new-field-modal" class="db-fdesigner-modal db-fdesigner-new-modal">
        <div class="db-fdesigner-modal-head">
          <div>
            <h4>新增扩展字段</h4>
            <p>保存后会执行 ALTER TABLE billboard ADD COLUMN，并立即放入当前表单。</p>
          </div>
          <button class="modal-close" type="button" title="关闭" @click="showNewField = false">×</button>
        </div>
        <div class="form-grid">
          <div class="form-field">
            <label for="db-new-field-label">显示名称 <em>*</em></label>
            <input id="db-new-field-label" v-model="newField.label" type="text" maxlength="100" placeholder="如：照明类型">
          </div>
          <div class="form-field">
            <label for="db-new-field-name">数据库字段名 <em>*</em></label>
            <div class="db-fdesigner-inline-input">
              <input
                id="db-new-field-name"
                v-model="newField.fieldName"
                type="text"
                maxlength="63"
                placeholder="如：lighting_type"
                @blur="!newField.fieldName && suggestFieldName()"
              >
              <button class="link-btn" type="button" @click="suggestFieldName">自动生成</button>
            </div>
            <span class="db-field-hint">小写字母开头，可含数字和下划线，创建后也可修改</span>
          </div>
          <div class="form-field">
            <label for="db-new-field-type">控件类型</label>
            <select id="db-new-field-type" v-model="newField.inputType" @change="onNewTypeChange">
              <option v-for="type in INPUT_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option>
            </select>
          </div>
          <div class="form-field">
            <label for="db-new-field-section">表单分区</label>
            <select id="db-new-field-section" v-model="newField.section">
              <option v-for="section in FORM_SECTIONS" :key="section.value" :value="section.value">{{ section.label }}</option>
            </select>
          </div>
          <div class="form-field">
            <label for="db-new-field-width">栏宽</label>
            <select id="db-new-field-width" v-model="newField.width">
              <option value="half">半栏</option>
              <option value="full">整行</option>
            </select>
          </div>
          <div class="form-field">
            <label for="db-new-field-length">长度 / 精度</label>
            <div class="db-fdesigner-inline-input">
              <input
                id="db-new-field-length"
                v-model.number="newField.length"
                type="number"
                min="1"
                :disabled="!['TEXT', 'SELECT', 'DECIMAL'].includes(newField.inputType)"
              >
              <input
                v-if="newField.inputType === 'DECIMAL'"
                v-model.number="newField.decimals"
                type="number"
                min="0"
                max="30"
                placeholder="小数位"
              >
            </div>
          </div>
          <div class="form-field">
            <label for="db-new-field-placeholder">提示文字</label>
            <input id="db-new-field-placeholder" v-model="newField.placeholder" type="text" maxlength="200" placeholder="输入框中的灰色提示">
          </div>
          <div class="form-field">
            <label>字段要求</label>
            <div class="db-fdesigner-check-row">
              <label class="admin-check"><input v-model="newField.required" type="checkbox"><span>必填</span></label>
              <label class="admin-check"><input v-model="newField.visible" type="checkbox"><span>前台展示</span></label>
            </div>
          </div>
          <div v-if="newField.inputType === 'SELECT'" class="form-field span-2">
            <label for="db-new-field-options">下拉选项 <em>*</em></label>
            <textarea
              id="db-new-field-options"
              v-model="newField.optionsText"
              rows="4"
              placeholder="每行一个选项，例如：&#10;单立柱&#10;高炮&#10;跨线桥"
            ></textarea>
            <span class="db-field-hint">每行一个选项，保存后可在设计器中继续调整</span>
          </div>
        </div>
        <div class="db-fdesigner-modal-actions">
          <button class="btn btn-ghost" type="button" :disabled="creating" @click="showNewField = false">取消</button>
          <button id="db-designer-create-field" class="btn btn-primary" type="button" :disabled="creating" @click="createField">
            {{ creating ? '创建中…' : '创建并放入表单' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
