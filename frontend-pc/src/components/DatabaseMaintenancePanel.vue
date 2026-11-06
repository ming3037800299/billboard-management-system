<script setup>
/**
 * 数据库维护面板（后台）：
 *   - 左侧：可配置的多个子模块（检索 / 表结构 / 广告牌字段 / 广告牌属性）
 *   - 检索：按表名、字段名、中文注释或表内数据内容定位数据所在表/字段
 *   - 表结构：浏览全部表、字段类型、注释、主键与数据量
 *   - 字段/属性：新增扩展字段时同步执行 ALTER TABLE billboard ADD COLUMN
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api } from '../api'
import { toast } from '../utils/toast'
import { fmtDateTime } from '../utils/format'
import BillboardFormDesigner from './BillboardFormDesigner.vue'

const emit = defineEmits(['unauthorized', 'fields-changed'])

const loading = ref(true)
const error = ref('')
const modules = ref([])
const activeId = ref(null)

const databases = ref([])
const defaultDatabase = ref('')
const selectedDatabase = ref('')
const databasesLoading = ref(false)
const databasesError = ref('')

const overview = ref(null)
const overviewError = ref('')

const columns = ref([])
const columnsLoading = ref(false)
const showCommentForm = ref(false)
const commentSaving = ref(false)
const commentForm = reactive({
  name: '',
  label: '',
  comment: '',
  origin: 'SYSTEM',
  moduleName: '',
  type: '',
  baseType: '',
  length: null,
  decimals: null,
  nullable: true,
  nullableOriginal: true,
  defaultValue: '',
  primaryKey: false,
})

const tables = ref([])
const tablesLoading = ref(false)
const tablesLoaded = ref(false)
const tableFilter = ref('')
const expandedTables = reactive({})

const searchForm = reactive({ keyword: '', includeData: false })
const searchScope = ref('current')
const searching = ref(false)
const searchError = ref('')
const searchResult = ref(null)

const showTableColumnForm = ref(false)
const tableColumnTarget = ref(null)
const tableColumnSaving = ref(false)
const tableColumnForm = reactive(emptyTableColumnForm())

const showModuleForm = ref(false)
const editingModuleId = ref(null)
const moduleSaving = ref(false)
const moduleForm = reactive(emptyModuleForm())

const showFieldForm = ref(false)
const editingFieldId = ref(null)
const editingFieldOriginal = ref(null)
const fieldSaving = ref(false)
const fieldForm = reactive(emptyFieldForm())
const fieldFilter = ref('')
const fieldStats = ref(null)
const fieldStatsLoading = ref(false)

const MODULE_TYPES = [
  { value: 'SEARCH', label: '数据库检索', hint: '按表名/字段/注释/数据内容检索' },
  { value: 'SCHEMA', label: '数据表结构', hint: '浏览表与字段结构' },
  { value: 'BILLBOARD_FIELD', label: '广告牌字段', hint: '新增物理字段并同步数据库' },
  { value: 'BILLBOARD_ATTRIBUTE', label: '广告牌属性', hint: '新增业务属性并同步数据库' },
  { value: 'FIELD_MANAGER', label: '字段管理', hint: '启用/停用字段、分区与栏宽' },
  { value: 'FORM_DESIGNER', label: '表单设计', hint: '按分区设计表单并预览' },
]

const INPUT_TYPES = [
  { value: 'TEXT', label: '单行文本', length: true },
  { value: 'TEXTAREA', label: '多行文本', length: false },
  { value: 'INTEGER', label: '整数', length: false },
  { value: 'DECIMAL', label: '小数', decimals: true },
  { value: 'DATE', label: '日期', length: false },
  { value: 'DATETIME', label: '日期时间', length: false },
  { value: 'SELECT', label: '下拉选项', length: true, options: true },
  { value: 'BOOLEAN', label: '是 / 否', length: false },
]

/** 任意表新增字段时支持的数据库列类型。 */
const TABLE_COLUMN_TYPES = [
  { value: 'VARCHAR', label: '字符串 VARCHAR', length: true },
  { value: 'TEXT', label: '长文本 TEXT' },
  { value: 'INT', label: '整数 INT' },
  { value: 'BIGINT', label: '长整数 BIGINT' },
  { value: 'DECIMAL', label: '小数 DECIMAL', length: true, decimals: true },
  { value: 'DATE', label: '日期 DATE' },
  { value: 'DATETIME', label: '日期时间 DATETIME' },
  { value: 'BOOLEAN', label: '是 / 否 TINYINT(1)' },
]

/** 系统字段允许的结构编辑范围（与后端 SystemColumnService 保持一致）。 */
const SYSTEM_COLUMN_RULES = {
  id: { types: ['BIGINT'], readonly: true },
  name: { types: ['VARCHAR', 'TEXT'] },
  code: { types: ['VARCHAR'], nullableFixed: true },
  road: { types: ['VARCHAR'] },
  section: { types: ['VARCHAR', 'TEXT'] },
  address: { types: ['VARCHAR', 'TEXT'] },
  longitude: { types: ['DECIMAL'] },
  latitude: { types: ['DECIMAL'] },
  images: { types: ['TEXT', 'VARCHAR'] },
  price: { types: ['DECIMAL'] },
  price_unit: { types: ['VARCHAR'] },
  price_note: { types: ['VARCHAR', 'TEXT'] },
  size: { types: ['VARCHAR', 'TEXT'] },
  material: { types: ['VARCHAR', 'TEXT'] },
  visible_area: { types: ['VARCHAR', 'TEXT'] },
  audience: { types: ['VARCHAR', 'TEXT'] },
  status: { types: ['VARCHAR'] },
  description: { types: ['TEXT', 'VARCHAR'] },
  deleted: { types: ['TINYINT'], readonly: true },
  created_at: { types: ['DATETIME'], readonly: true },
  updated_at: { types: ['DATETIME'], readonly: true },
}

const FORM_SECTIONS = [
  { value: 'basic', label: '基本信息' },
  { value: 'location', label: '位置坐标' },
  { value: 'price', label: '租赁报价' },
  { value: 'params', label: '广告位参数' },
  { value: 'extension', label: '扩展信息' },
]
const FORM_SECTION_LABELS = Object.fromEntries(FORM_SECTIONS.map((s) => [s.value, s.label]))

const activeModule = computed(() => modules.value.find((m) => m.id === activeId.value) || null)
const isFieldModule = computed(
  () => activeModule.value?.type === 'BILLBOARD_FIELD' || activeModule.value?.type === 'BILLBOARD_ATTRIBUTE',
)
const isAttributeModule = computed(() => activeModule.value?.type === 'BILLBOARD_ATTRIBUTE')
const inputTypeMeta = computed(() => INPUT_TYPES.find((t) => t.value === fieldForm.inputType) || {})
const customColumnCount = computed(
  () => columns.value.filter((c) => c.origin === 'CUSTOM').length,
)
const filteredColumns = computed(() => {
  const kw = fieldFilter.value.trim().toLowerCase()
  if (!kw) return columns.value
  return columns.value.filter((c) =>
    (c.label || '').toLowerCase().includes(kw)
    || (c.name || '').toLowerCase().includes(kw)
    || (c.comment || '').toLowerCase().includes(kw)
    || (c.type || '').toLowerCase().includes(kw)
    || (c.moduleName || '').toLowerCase().includes(kw))
})
const tableColumnTypeMeta = computed(
  () => TABLE_COLUMN_TYPES.find((t) => t.value === tableColumnForm.type) || {},
)
const isProjectDatabase = computed(
  () => !!selectedDatabase.value && selectedDatabase.value === defaultDatabase.value,
)
const systemRule = computed(
  () => SYSTEM_COLUMN_RULES[commentForm.name] || { types: [commentForm.baseType || 'VARCHAR'], nullableFixed: false },
)
const systemStructureEditable = computed(() => !systemRule.value.readonly)
const systemTypeOptions = computed(() => systemRule.value.types || [])
const systemShowLength = computed(
  () => commentForm.baseType === 'VARCHAR' || commentForm.baseType === 'DECIMAL',
)
const systemShowDecimals = computed(() => commentForm.baseType === 'DECIMAL')
const fieldOwnerId = computed(() => {
  const type = activeModule.value?.type
  if (type === 'FIELD_MANAGER' || type === 'FORM_DESIGNER') {
    return modules.value.find((m) => m.type === 'BILLBOARD_FIELD')?.id
      ?? modules.value.find((m) => m.type === 'BILLBOARD_ATTRIBUTE')?.id
      ?? activeId.value
  }
  return activeId.value
})

function onDesignerFieldsChanged() {
  emit('fields-changed')
}

function onDesignerUnauthorized() {
  emit('unauthorized')
}

const systemColumnTypePreview = computed(() => {
  const base = commentForm.baseType
  if (base === 'VARCHAR') return `VARCHAR(${Number(commentForm.length) || 200})`
  if (base === 'DECIMAL') {
    return `DECIMAL(${Number(commentForm.length) || 18},${Number(commentForm.decimals) || 0})`
  }
  if (base === 'TEXT') return 'TEXT'
  if (base === 'DATETIME') return 'DATETIME'
  if (base === 'TINYINT') return 'TINYINT(1)'
  if (base === 'BIGINT') return 'BIGINT'
  return commentForm.type || 'VARCHAR(200)'
})

const filteredTables = computed(() => {
  const kw = tableFilter.value.trim().toLowerCase()
  if (!kw) return tables.value
  return tables.value.filter((t) => {
    if ((t.name || '').toLowerCase().includes(kw) || (t.comment || '').toLowerCase().includes(kw)) return true
    return (t.columns || []).some((c) =>
      (c.name || '').toLowerCase().includes(kw)
      || (c.comment || '').toLowerCase().includes(kw)
      || (c.type || '').toLowerCase().includes(kw))
  })
})

function emptyModuleForm() {
  return {
    name: '',
    code: '',
    type: 'BILLBOARD_FIELD',
    description: '',
    sortOrder: 0,
    enabled: true,
  }
}

function emptyFieldForm() {
  return {
    label: '',
    fieldName: '',
    inputType: 'TEXT',
    length: 200,
    decimals: 2,
    required: false,
    nullable: true,
    enabled: true,
    section: 'extension',
    width: 'half',
    defaultValue: '',
    optionsText: '',
    placeholder: '',
    sortOrder: 0,
    visible: true,
  }
}

function emptyTableColumnForm() {
  return {
    fieldName: '',
    type: 'VARCHAR',
    length: 200,
    decimals: 2,
    comment: '',
    defaultValue: '',
    nullable: true,
  }
}

function handleError(e, fallback) {
  if (e && e.status === 401) {
    emit('unauthorized')
    return
  }
  const message = (e && e.message) || fallback
  error.value = message
  toast(message, 'error')
}

/* ---------------- 加载 ---------------- */

async function loadDatabases() {
  databasesLoading.value = true
  databasesError.value = ''
  try {
    const data = await api.dbDatabases()
    databases.value = data.databases || []
    defaultDatabase.value = data.defaultDatabase || ''
    if (!selectedDatabase.value
        || !databases.value.some((db) => db.name === selectedDatabase.value)) {
      selectedDatabase.value = data.defaultDatabase || databases.value[0]?.name || ''
    }
  } catch (e) {
    if (e?.status === 401) {
      emit('unauthorized')
      return
    }
    databasesError.value = e.message || '数据库列表加载失败'
    handleError(e, '数据库列表加载失败')
  } finally {
    databasesLoading.value = false
  }
}

async function loadOverview() {
  if (!selectedDatabase.value) return
  overviewError.value = ''
  try {
    overview.value = await api.dbDatabaseOverview(selectedDatabase.value)
  } catch (e) {
    if (e?.status === 401) {
      emit('unauthorized')
      return
    }
    overviewError.value = e.message || '数据库概览加载失败'
  }
}

async function loadModules() {
  modules.value = await api.dbModules()
  if (!modules.value.some((m) => m.id === activeId.value)) {
    activeId.value = modules.value[0]?.id ?? null
  }
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    await loadModules()
    await loadDatabases()
    await loadOverview()
    await loadActiveContent()
  } catch (e) {
    handleError(e, '数据库维护信息加载失败')
  } finally {
    loading.value = false
  }
}

function onDatabaseChange() {
  tablesLoaded.value = false
  tables.value = []
  tableFilter.value = ''
  searchResult.value = null
  searchError.value = ''
  showTableColumnForm.value = false
  loadOverview()
  if (activeModule.value?.type === 'SCHEMA') {
    loadTables(true)
  }
}

async function loadActiveContent() {
  const module = activeModule.value
  if (!module) return
  if (module.type === 'SCHEMA') {
    await loadTables()
  } else if (isFieldModule.value
      || module.type === 'FIELD_MANAGER'
      || module.type === 'FORM_DESIGNER') {
    await loadColumns()
  }
}

async function loadTables(force = false) {
  if (tablesLoading.value || !selectedDatabase.value) return
  if (tablesLoaded.value && !force) return
  tablesLoading.value = true
  error.value = ''
  try {
    tables.value = await api.dbDatabaseTables(selectedDatabase.value)
    tablesLoaded.value = true
  } catch (e) {
    handleError(e, '数据表结构加载失败')
  } finally {
    tablesLoading.value = false
  }
}

async function loadColumns() {
  columnsLoading.value = true
  error.value = ''
  try {
    columns.value = await api.dbBillboardColumns()
  } catch (e) {
    handleError(e, '广告牌数据库字段加载失败')
  } finally {
    columnsLoading.value = false
  }
}

/* ---------------- 子模块 ---------------- */

function selectModule(module) {
  if (activeId.value === module.id) return
  activeId.value = module.id
  searchResult.value = null
  searchError.value = ''
  showFieldForm.value = false
  showModuleForm.value = false
  showCommentForm.value = false
  showTableColumnForm.value = false
  loadActiveContent()
}

function openModuleForm(module = null) {
  editingModuleId.value = module?.id ?? null
  Object.assign(moduleForm, module
    ? {
        name: module.name,
        code: module.code,
        type: module.type,
        description: module.description || '',
        sortOrder: module.sortOrder,
        enabled: module.enabled,
      }
    : emptyModuleForm())
  showModuleForm.value = true
  showFieldForm.value = false
  showCommentForm.value = false
}

async function saveModule() {
  if (moduleSaving.value) return
  if (!moduleForm.name.trim()) {
    toast('请填写子模块名称', 'error')
    return
  }
  if (!editingModuleId.value && !moduleForm.code.trim()) {
    toast('请填写模块标识（英文/数字/下划线）', 'error')
    return
  }
  moduleSaving.value = true
  try {
    const payload = {
      name: moduleForm.name.trim(),
      code: moduleForm.code.trim(),
      type: moduleForm.type,
      description: moduleForm.description,
      sortOrder: Number(moduleForm.sortOrder) || 0,
      enabled: moduleForm.enabled,
    }
    const saved = editingModuleId.value
      ? await api.dbUpdateModule(editingModuleId.value, payload)
      : await api.dbCreateModule(payload)
    toast(editingModuleId.value ? '子模块已保存' : '子模块已创建')
    showModuleForm.value = false
    await loadModules()
    activeId.value = saved.id
    await loadActiveContent()
    emit('fields-changed')
  } catch (e) {
    handleError(e, '子模块保存失败')
  } finally {
    moduleSaving.value = false
  }
}

async function removeModule(module) {
  if (!module) return
  const ok = window.confirm(
    `确认删除子模块「${module.name}」？
仅可删除没有字段的自定义子模块，不会修改数据库表结构。`,
  )
  if (!ok) return
  try {
    await api.dbDeleteModule(module.id)
    toast('子模块已删除')
    showModuleForm.value = false
    await loadModules()
    await loadActiveContent()
  } catch (e) {
    handleError(e, '子模块删除失败')
  }
}

/* ---------------- 字段 / 属性 ---------------- */

function openFieldForm(column = null) {
  if (column && column.origin === 'SYSTEM') {
    openCommentForm(column)
    return
  }
  editingFieldId.value = column?.customFieldId ?? null
  editingFieldOriginal.value = column ? {
    fieldName: column.name,
    inputType: column.inputType || 'TEXT',
    length: column.length ?? null,
    decimals: column.decimals ?? null,
    nullable: column.nullable !== false,
  } : null
  if (column) {
    Object.assign(fieldForm, {
      label: column.label,
      fieldName: column.name,
      inputType: column.inputType || 'TEXT',
      length: column.length ?? (column.inputType === 'DECIMAL' ? 18 : 200),
      decimals: column.decimals ?? 2,
      required: column.required,
      nullable: column.nullable !== false,
      enabled: column.enabled !== false,
      section: column.section || 'extension',
      width: column.width || 'half',
      defaultValue: column.defaultValue || '',
      optionsText: (column.options || []).join('\n'),
      placeholder: column.placeholder || '',
      sortOrder: column.sortOrder,
      visible: column.visible,
    })
  } else {
    Object.assign(fieldForm, emptyFieldForm())
    fieldForm.inputType = isAttributeModule.value ? 'SELECT' : 'TEXT'
    applyTypeDefaults()
  }
  loadFieldStats(column?.name || '')
  showFieldForm.value = true
  showModuleForm.value = false
  showCommentForm.value = false
  showTableColumnForm.value = false
}

function applyTypeDefaults() {
  const meta = INPUT_TYPES.find((t) => t.value === fieldForm.inputType)
  if (fieldForm.inputType === 'DECIMAL') {
    if (!fieldForm.length) fieldForm.length = 18
    if (fieldForm.decimals == null) fieldForm.decimals = 2
  } else if (fieldForm.inputType === 'SELECT') {
    if (!fieldForm.length) fieldForm.length = 100
  } else if (fieldForm.inputType === 'TEXT') {
    if (!fieldForm.length) fieldForm.length = 200
  } else if (!meta?.length) {
    fieldForm.length = null
  }
}

function inputTypeLabel(value) {
  return INPUT_TYPES.find((t) => t.value === value)?.label || value
}

function parseDbType(type) {
  const match = /^([a-zA-Z]+)(?:\((\d+)(?:,(\d+))?\))?/.exec(type || '')
  return {
    base: (match?.[1] || 'VARCHAR').toUpperCase(),
    length: match?.[2] ? Number(match[2]) : null,
    decimals: match?.[3] ? Number(match[3]) : null,
  }
}

function onSystemTypeChange() {
  if (commentForm.baseType === 'VARCHAR') {
    if (!commentForm.length) commentForm.length = 200
    commentForm.decimals = null
  } else if (commentForm.baseType === 'DECIMAL') {
    if (!commentForm.length) commentForm.length = 18
    if (commentForm.decimals == null) commentForm.decimals = 2
  } else {
    commentForm.length = null
    commentForm.decimals = null
  }
}

async function loadFieldStats(name) {
  fieldStats.value = null
  if (!name) return
  fieldStatsLoading.value = true
  try {
    fieldStats.value = await api.dbBillboardColumnStats(name)
  } catch (e) {
    fieldStats.value = null
  } finally {
    fieldStatsLoading.value = false
  }
}

function sqlText(value) {
  return String(value ?? '').replaceAll("'", "''")
}

/** 字段抽屉中展示的 DDL 预览（与实际后端生成规则保持一致）。 */
const fieldSqlPreview = computed(() => {
  const type = fieldForm.inputType
  const length = Number(fieldForm.length) || 0
  let columnType
  switch (type) {
    case 'TEXT': columnType = `VARCHAR(${length || 200})`; break
    case 'SELECT': columnType = `VARCHAR(${length || 100})`; break
    case 'TEXTAREA': columnType = 'TEXT'; break
    case 'INTEGER': columnType = 'INT'; break
    case 'DECIMAL': columnType = `DECIMAL(${length || 18},${Number(fieldForm.decimals) || 0})`; break
    case 'DATE': columnType = 'DATE'; break
    case 'DATETIME': columnType = 'DATETIME'; break
    case 'BOOLEAN': columnType = 'TINYINT(1)'; break
    default: columnType = 'VARCHAR(200)'
  }
  const nullable = fieldForm.nullable ? 'NULL' : 'NOT NULL'
  const rawDefault = String(fieldForm.defaultValue || '').trim()
  let defaultSql = ''
  if (rawDefault) {
    if (type === 'INTEGER' || type === 'DECIMAL') {
      defaultSql = ` DEFAULT ${rawDefault}`
    } else if (type === 'BOOLEAN') {
      defaultSql = ` DEFAULT ${rawDefault === 'true' || rawDefault === '是' ? '1' : '0'}`
    } else {
      defaultSql = ` DEFAULT '${sqlText(rawDefault)}'`
    }
  }
  if (!fieldForm.nullable && !rawDefault) {
    // 非空且未填写默认值时，后端会自动添加类型空值作为插入兜底
    if (type === 'TEXTAREA') {
      defaultSql = " DEFAULT ('')"
    } else if (type === 'INTEGER' || type === 'DECIMAL' || type === 'BOOLEAN') {
      defaultSql = ' DEFAULT 0'
    } else if (type === 'DATE') {
      defaultSql = " DEFAULT '1970-01-01'"
    } else if (type === 'DATETIME') {
      defaultSql = " DEFAULT '1970-01-01 00:00:00'"
    } else {
      defaultSql = " DEFAULT ''"
    }
  }
  const commentSql = ` COMMENT '${sqlText(fieldForm.label || '')}'`
  const name = fieldForm.fieldName || '<field_name>'
  if (editingFieldId.value && editingFieldOriginal.value) {
    return `ALTER TABLE billboard CHANGE COLUMN ${editingFieldOriginal.value.fieldName} ${name} `
      + `${columnType} ${nullable}${defaultSql}${commentSql};`
  }
  return `ALTER TABLE billboard ADD COLUMN ${name} ${columnType} ${nullable}${defaultSql}${commentSql};`
})

const commentSqlPreview = computed(() => {
  const commentValue = sqlText(commentForm.comment || '')
  const nullable = commentForm.nullable ? 'NULL' : 'NOT NULL'
  return `ALTER TABLE billboard MODIFY COLUMN ${commentForm.name} ${systemColumnTypePreview.value} `
    + `${nullable} ... COMMENT '${commentValue}';`
})

async function copySql(sql) {
  try {
    await navigator.clipboard.writeText(sql)
    toast('SQL 已复制')
  } catch (e) {
    toast('复制失败，请手动选择 SQL 文本', 'error')
  }
}

function onGlobalKeydown(e) {
  if (e.key === 'Escape') {
    showFieldForm.value = false
    showCommentForm.value = false
    showTableColumnForm.value = false
    showModuleForm.value = false
  }
}

async function saveField() {
  if (fieldSaving.value) return
  if (!fieldForm.label.trim()) {
    toast('请填写字段显示名称', 'error')
    return
  }
  if (!fieldForm.fieldName.trim()) {
    toast('请填写字段名（英文/数字/下划线）', 'error')
    return
  }
  if (fieldForm.inputType === 'SELECT' && !fieldForm.optionsText.trim()) {
    toast('下拉选项字段至少需要一个选项', 'error')
    return
  }

  const payload = {
    label: fieldForm.label.trim(),
    fieldName: fieldForm.fieldName.trim(),
    inputType: fieldForm.inputType,
    length: fieldForm.length === '' || fieldForm.length == null ? null : Number(fieldForm.length),
    decimals: fieldForm.decimals === '' || fieldForm.decimals == null ? null : Number(fieldForm.decimals),
    required: fieldForm.required,
    nullable: fieldForm.nullable,
    enabled: fieldForm.enabled,
    section: fieldForm.section,
    width: fieldForm.width,
    defaultValue: fieldForm.defaultValue,
    options: fieldForm.optionsText.split(/\r?\n/).map((s) => s.trim()).filter(Boolean),
    placeholder: fieldForm.placeholder,
    sortOrder: Number(fieldForm.sortOrder) || 0,
    visible: fieldForm.visible,
  }

  const original = editingFieldOriginal.value
  if (editingFieldId.value && original) {
    const typeChanged = payload.inputType !== original.inputType
    const nameChanged = payload.fieldName !== original.fieldName
    const lengthApplies = !typeChanged && ['TEXT', 'SELECT', 'DECIMAL'].includes(payload.inputType)
    const lengthChanged = lengthApplies
      && Number(payload.length ?? 0) !== Number(original.length ?? 0)
    const decimalsChanged = !typeChanged && payload.inputType === 'DECIMAL'
      && Number(payload.decimals ?? 0) !== Number(original.decimals ?? 0)
    const nullableChanged = payload.nullable !== original.nullable
    if (nameChanged || typeChanged || lengthChanged || decimalsChanged || nullableChanged) {
      const nl = String.fromCharCode(10)
      const ok = window.confirm(
        '确认修改字段结构？' + nl + nl
        + `字段名：${original.fieldName} → ${payload.fieldName}` + nl
        + `类型：${inputTypeLabel(original.inputType)} → ${inputTypeLabel(payload.inputType)}` + nl
        + `数据库可空：${original.nullable ? '可空' : '非空'} → ${payload.nullable ? '可空' : '非空'}` + nl + nl
        + '保存后会执行 ALTER TABLE ... CHANGE COLUMN，已有数据会按新类型转换，'
        + '可能丢失精度或格式。请确认已备份重要数据。',
      )
      if (!ok) return
    }
  }

  fieldSaving.value = true
  try {
    if (editingFieldId.value) {
      await api.dbUpdateField(editingFieldId.value, payload)
      toast('字段设置已保存，数据库结构已同步')
    } else {
      await api.dbAddField(fieldOwnerId.value, payload)
      toast('字段已新增，数据库列已同步')
    }
    showFieldForm.value = false
    editingFieldOriginal.value = null
    await loadColumns()
    await loadModules()
    await loadOverview()
    emit('fields-changed')
  } catch (e) {
    // 新增字段时元数据可能已登记但 DDL 失败：刷新列表让用户看到“待同步”并重试
    if (!editingFieldId.value) {
      await loadColumns()
    }
    handleError(e, '字段保存失败')
  } finally {
    fieldSaving.value = false
  }
}

async function updateFieldLayout(column, patch) {
  if (!column?.customFieldId) return
  try {
    await api.dbUpdateFieldLayout(column.customFieldId, patch)
    await loadColumns()
    await loadModules()
    emit('fields-changed')
    toast('字段布局已更新')
  } catch (e) {
    handleError(e, '字段布局更新失败')
  }
}

function toggleFieldEnabled(column) {
  updateFieldLayout(column, { enabled: !column.enabled })
}

function changeFieldSection(column, section) {
  updateFieldLayout(column, { section })
}

function changeFieldWidth(column, width) {
  updateFieldLayout(column, { width })
}

async function moveField(column, direction) {
  const list = columns.value
    .filter((c) => c.origin === 'CUSTOM' && (c.section || 'extension') === (column.section || 'extension'))
    .sort((a, b) => a.sortOrder - b.sortOrder)
  const index = list.findIndex((c) => c.customFieldId === column.customFieldId)
  const target = index + direction
  if (index < 0 || target < 0 || target >= list.length) return
  const other = list[target]
  const a = column.sortOrder
  const b = other.sortOrder
  if (a === b) {
    await updateFieldLayout(column, { sortOrder: b + 10 })
    await updateFieldLayout(other, { sortOrder: a })
  } else {
    await updateFieldLayout(column, { sortOrder: b })
    await updateFieldLayout(other, { sortOrder: a })
  }
}

async function removeField(field) {
  if (!field?.customFieldId) return
  const ok = window.confirm(
    `确认删除「${field.label}」？\n将从数据库中执行 DROP COLUMN ${field.name}，该列已有数据会永久丢失！`,
  )
  if (!ok) return
  try {
    await api.dbDeleteField(field.customFieldId)
    toast('字段已删除，数据库列已同步移除')
    await loadColumns()
    await loadModules()
    await loadOverview()
    emit('fields-changed')
  } catch (e) {
    handleError(e, '字段删除失败')
  }
}

async function syncField(field) {
  if (!field?.customFieldId) return
  try {
    await api.dbSyncField(field.customFieldId)
    toast('数据库列已同步')
    await loadColumns()
    await loadModules()
    emit('fields-changed')
  } catch (e) {
    handleError(e, '字段同步失败')
  }
}

/** 当前列是否属于正在查看的字段/属性子模块。 */
function isOwnColumn(column) {
  return column.origin === 'CUSTOM' && column.moduleId === activeId.value
}

function sourceLabel(column) {
  if (column.origin === 'SYSTEM') return '系统字段'
  return column.moduleName || '扩展字段'
}

function columnStatus(column) {
  if (column.origin === 'SYSTEM' || column.synced) {
    return { label: '已同步', cls: 'ks-ready' }
  }
  return { label: '待同步', cls: 'ks-error' }
}

/** 编辑数据库列注释（系统字段只改数据库；扩展字段同时更新显示名称）。 */
function openCommentForm(column) {
  Object.assign(commentForm, {
    name: column.name,
    label: column.label,
    comment: column.comment || '',
    origin: column.origin,
    moduleName: column.moduleName || '',
    type: column.type || '',
    baseType: parseDbType(column.type).base,
    length: parseDbType(column.type).length,
    decimals: parseDbType(column.type).decimals,
    nullable: !!column.nullable,
    nullableOriginal: !!column.nullable,
    defaultValue: column.defaultValue == null ? '' : String(column.defaultValue),
    primaryKey: !!column.primaryKey,
  })
  loadFieldStats(column.name)
  showCommentForm.value = true
  showFieldForm.value = false
  showModuleForm.value = false
}

async function saveComment() {
  if (commentSaving.value) return
  const isSystem = commentForm.origin === 'SYSTEM'
  const original = parseDbType(commentForm.type)
  const base = commentForm.baseType || original.base
  const length = ['VARCHAR', 'DECIMAL'].includes(base)
    ? (Number(commentForm.length) || null) : null
  const decimals = base === 'DECIMAL' ? (Number(commentForm.decimals) || 0) : null
  const structural = isSystem && (
    base !== original.base
    || (length != null && length !== original.length)
    || (decimals != null && decimals !== original.decimals)
    || commentForm.nullable !== commentForm.nullableOriginal
  )
  if (structural) {
    const nl = String.fromCharCode(10)
    const ok = window.confirm(
      '确认修改系统字段结构？' + nl + nl
      + `类型：${commentForm.type} → ${systemColumnTypePreview.value}` + nl
      + `可空：${commentForm.nullableOriginal ? '可空' : '非空'} → ${commentForm.nullable ? '可空' : '非空'}` + nl + nl
      + '保存后会执行 ALTER TABLE ... MODIFY COLUMN，已有数据按新类型转换；'
      + '非空化时会将空值填充为默认值或类型空值。',
    )
    if (!ok) return
  }

  commentSaving.value = true
  try {
    if (isSystem) {
      await api.dbUpdateSystemColumn(commentForm.name, {
        type: base,
        length,
        decimals,
        nullable: commentForm.nullable,
        comment: commentForm.comment,
      })
      toast('字段设置已保存，数据库结构已同步')
    } else {
      await api.dbUpdateColumnComment(commentForm.name, commentForm.comment)
      toast('字段显示名称与数据库注释已更新')
    }
    showCommentForm.value = false
    await loadColumns()
    await loadModules()
    await loadOverview()
    if (!isSystem) {
      emit('fields-changed')
    }
  } catch (e) {
    handleError(e, '字段设置保存失败')
  } finally {
    commentSaving.value = false
  }
}

/* ---------------- 检索 / 表结构 ---------------- */

async function doSearch() {
  const keyword = searchForm.keyword.trim()
  if (!keyword) {
    toast('请输入要检索的表名、字段名、注释或数据内容', 'error')
    return
  }
  if (searchScope.value !== 'all' && !selectedDatabase.value) {
    toast('请先选择数据库', 'error')
    return
  }
  searching.value = true
  searchError.value = ''
  try {
    searchResult.value = searchScope.value === 'all'
      ? await api.dbSearchAll(keyword)
      : await api.dbDatabaseSearch(selectedDatabase.value, keyword, searchForm.includeData)
  } catch (e) {
    searchResult.value = null
    if (e?.status === 401) {
      emit('unauthorized')
      return
    }
    searchError.value = e.message || '检索失败'
  } finally {
    searching.value = false
  }
}

function quickSearch(keyword, includeData = false, scope = 'current') {
  searchForm.keyword = keyword
  searchScope.value = scope
  searchForm.includeData = scope === 'all' ? false : includeData
  doSearch()
}

function onSearchScopeChange() {
  if (searchScope.value === 'all') {
    searchForm.includeData = false
  }
  searchResult.value = null
  searchError.value = ''
}

function isProjectBillboard(table) {
  return isProjectDatabase.value && (table?.name || '').toLowerCase() === 'billboard'
}

function goBillboardFields() {
  const module = modules.value.find((m) => m.type === 'BILLBOARD_FIELD')
  if (module) selectModule(module)
}

/** 打开“任意表新增字段”表单。 */
function openTableColumnForm(table) {
  if (isProjectBillboard(table)) {
    toast('billboard 表字段请使用「广告牌字段」子模块新增，以便接入前台表单', 'error')
    return
  }
  tableColumnTarget.value = table
  Object.assign(tableColumnForm, emptyTableColumnForm())
  applyTableTypeDefaults()
  showTableColumnForm.value = true
  showFieldForm.value = false
  showCommentForm.value = false
  showModuleForm.value = false
}

function applyTableTypeDefaults() {
  if (tableColumnForm.type === 'VARCHAR') {
    if (!tableColumnForm.length) tableColumnForm.length = 200
    tableColumnForm.decimals = null
  } else if (tableColumnForm.type === 'DECIMAL') {
    if (!tableColumnForm.length) tableColumnForm.length = 18
    if (tableColumnForm.decimals == null) tableColumnForm.decimals = 2
  } else {
    tableColumnForm.length = null
    tableColumnForm.decimals = null
  }
}

async function saveTableColumn() {
  if (tableColumnSaving.value || !tableColumnTarget.value) return
  if (!tableColumnForm.fieldName.trim()) {
    toast('请填写字段名', 'error')
    return
  }
  if (!tableColumnForm.type) {
    toast('请选择字段类型', 'error')
    return
  }
  if (!tableColumnForm.nullable && !tableColumnForm.defaultValue.trim()) {
    toast('非空字段需要填写默认值，避免存量数据加列失败', 'error')
    return
  }
  tableColumnSaving.value = true
  try {
    await api.dbAddTableColumn(selectedDatabase.value, tableColumnTarget.value.name, {
      fieldName: tableColumnForm.fieldName.trim(),
      type: tableColumnForm.type,
      length: tableColumnForm.length === '' || tableColumnForm.length == null
        ? null : Number(tableColumnForm.length),
      decimals: tableColumnForm.decimals === '' || tableColumnForm.decimals == null
        ? null : Number(tableColumnForm.decimals),
      comment: tableColumnForm.comment,
      defaultValue: tableColumnForm.defaultValue,
      nullable: tableColumnForm.nullable,
    })
    toast(`字段已新增：${selectedDatabase.value}.${tableColumnTarget.value.name}.${tableColumnForm.fieldName.trim()}`)
    showTableColumnForm.value = false
    await loadTables(true)
    await loadOverview()
  } catch (e) {
    handleError(e, '新增字段失败')
  } finally {
    tableColumnSaving.value = false
  }
}

function toggleTable(name) {
  expandedTables[name] = !expandedTables[name]
}

function typeLabel(type) {
  return MODULE_TYPES.find((t) => t.value === type)?.label || type
}

/** 子模块列表的副标题：只读模块显示用途，字段模块显示字段数量。 */
function moduleSubLabel(module) {
  if (module.type === 'SEARCH') return '表 / 字段 / 数据检索'
  if (module.type === 'SCHEMA') return '表结构浏览'
  if (module.type === 'FIELD_MANAGER') return '启用 / 停用与布局'
  if (module.type === 'FORM_DESIGNER') return '分区 / 栏宽 / 预览'
  const unit = module.type === 'BILLBOARD_ATTRIBUTE' ? '属性' : '字段'
  return `${module.fieldCount} 个${unit}`
}

function fmtRows(rows) {
  if (rows === null || rows === undefined || rows < 0) return '—'
  return rows.toLocaleString('zh-CN')
}

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
  loadAll()
})

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <section class="db-panel panel">
    <div class="panel-head">
      <div class="panel-title">
        数据库维护
        <span class="count">{{ modules.length }} 个子模块</span>
      </div>
      <div class="panel-head-actions">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="loadAll">
          {{ loading ? '加载中…' : '刷新全部' }}
        </button>
        <button class="btn btn-primary btn-sm" type="button" @click="openModuleForm()">
          新建子模块
        </button>
      </div>
    </div>

    <!-- 数据库列表 / 选择 -->
    <div class="db-database-bar">
      <div class="db-database-select">
        <label for="db-database-select">数据库</label>
        <select
          id="db-database-select"
          v-model="selectedDatabase"
          :disabled="databasesLoading"
          @change="onDatabaseChange"
        >
          <option v-for="db in databases" :key="db.name" :value="db.name">
            {{ db.name }}{{ db.current ? '（当前项目库）' : '' }} · {{ db.tableCount }} 表 / {{ db.columnCount }} 字段
          </option>
        </select>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="databasesLoading"
          @click="loadDatabases"
        >{{ databasesLoading ? '刷新中…' : '刷新数据库列表' }}</button>
      </div>
      <div class="db-database-hint">
        维护账号可见 {{ databases.length }} 个数据库
        <template v-if="overview"> · {{ overview.ddlAllowed ? '可新增字段（ALTER）' : '当前账号缺少 ALTER 权限' }}</template>
      </div>
    </div>
    <div v-if="databasesError" class="db-warning">{{ databasesError }}</div>

    <div v-if="error" class="knowledge-error db-error">{{ error }}</div>

    <div class="db-layout">
      <!-- 左侧子模块列表 -->
      <aside class="db-module-nav">
        <div class="db-module-nav-title">维护子模块</div>
        <button
          v-for="m in modules"
          :key="m.id"
          class="db-module-item"
          :class="{ active: m.id === activeId, disabled: !m.enabled }"
          type="button"
          @click="selectModule(m)"
        >
          <span class="db-module-name">
            {{ m.name }}
            <span v-if="m.builtin" class="db-builtin">内置</span>
          </span>
          <span class="db-module-sub">
            {{ moduleSubLabel(m) }}
            <template v-if="!m.enabled"> · 已停用</template>
          </span>
        </button>
        <div v-if="!loading && !modules.length" class="db-module-empty">暂无子模块</div>
        <div class="db-module-tip">
          子模块用于组织不同的维护功能。可以建立多个“广告牌字段 / 属性”子模块，
          分别管理报价、照明、运营等不同类别的扩展字段。
        </div>
      </aside>

      <!-- 右侧内容 -->
      <div class="db-module-body">
        <div v-if="activeModule" class="db-module-head">
          <div>
            <h3>{{ activeModule.name }}</h3>
            <p>{{ activeModule.description || '暂无说明' }}</p>
            <div class="db-module-meta">
              <span class="db-chip">{{ activeModule.typeLabel }}</span>
              <span class="db-chip" :class="activeModule.enabled ? 'ok' : 'off'">
                {{ activeModule.enabled ? '已启用' : '已停用' }}
              </span>
              <span class="db-chip mono">{{ activeModule.code }}</span>
              <span v-if="activeModule.createdAt" class="db-chip">
                创建于 {{ fmtDateTime(activeModule.createdAt) }}
              </span>
            </div>
          </div>
          <div class="db-module-actions">
            <button class="btn btn-ghost btn-sm" type="button" @click="openModuleForm(activeModule)">
              编辑子模块
            </button>
            <button
              v-if="!activeModule.builtin"
              class="btn btn-danger btn-sm"
              type="button"
              @click="removeModule(activeModule)"
            >删除</button>
          </div>
        </div>

        <!-- 数据库概览 -->
        <div v-if="overview" class="db-overview">
          <div class="db-overview-card">
            <span class="label">数据库</span>
            <strong>{{ overview.database || overview.schema || '—' }}</strong>
            <span class="sub">{{ overview.product }} {{ overview.version }}</span>
          </div>
          <div class="db-overview-card">
            <span class="label">表 / 字段</span>
            <strong>{{ overview.tableCount }} / {{ overview.columnCount }}</strong>
            <span class="sub">当前库全部基础表</span>
          </div>
          <div class="db-overview-card">
            <span class="label">DDL 权限</span>
            <strong :class="overview.ddlAllowed ? 'ok' : 'off'">
              {{ overview.ddlAllowed ? '允许 ALTER' : '缺少 ALTER' }}
            </strong>
            <span class="sub">{{ overview.ddlAllowed ? '可新增/删除扩展字段列' : '新增字段将无法同步' }}</span>
          </div>
        </div>
        <div v-else-if="overviewError" class="db-warning">{{ overviewError }}</div>
        <div v-if="overview && !overview.ddlAllowed" class="db-warning">
          {{ overview.ddlHint }}
        </div>

        <!-- 检索子模块 -->
        <div v-if="activeModule?.type === 'SEARCH'" class="db-section">
          <div class="db-search-bar">
            <input
              v-model="searchForm.keyword"
              type="search"
              placeholder="输入表名、字段名、中文注释或数据内容，如：报价 / 京沪高速 / price"
              @keyup.enter="doSearch"
            >
            <button class="btn btn-primary" type="button" :disabled="searching" @click="doSearch">
              {{ searching ? '检索中…' : '检索' }}
            </button>
          </div>
          <div class="db-search-options">
            <label class="db-scope">
              检索范围
              <select v-model="searchScope" @change="onSearchScopeChange">
                <option value="current">当前数据库（{{ selectedDatabase }}）</option>
                <option value="all">全部数据库（仅字段 / 注释）</option>
              </select>
            </label>
            <label class="admin-check" :class="{ disabled: searchScope === 'all' }">
              <input v-model="searchForm.includeData" type="checkbox" :disabled="searchScope === 'all'">
              <span>同时检索表内数据内容（仅当前库）</span>
            </label>
          </div>
          <div class="db-examples">
            <span>快速检索：</span>
            <button type="button" @click="quickSearch('报价')">报价</button>
            <button type="button" @click="quickSearch('京沪高速', true)">京沪高速（含数据）</button>
            <button type="button" @click="quickSearch('price')">price</button>
            <button type="button" @click="quickSearch('price', false, 'all')">price（跨库）</button>
            <button type="button" @click="quickSearch('状态')">状态</button>
          </div>

          <div v-if="searchError" class="knowledge-error db-search-error">{{ searchError }}</div>

          <div v-if="searchResult" class="db-search-result">
            <div class="db-search-summary">
              检索范围：<strong>{{ searchScope === 'all' ? '全部数据库' : selectedDatabase }}</strong> ·
              关键词「{{ searchResult.keyword }}」：
              字段命中 <strong>{{ searchResult.columnHits.length }}</strong> 项
              <template v-if="searchResult.includeData">
                ，数据命中 <strong>{{ searchResult.dataHits.length }}</strong> 项
                （扫描 {{ searchResult.scannedTables }} 张表<template v-if="searchResult.dataSearchTruncated">，结果已截断</template>）
              </template>
            </div>

            <div class="db-result-title">字段命中（数据库 / 表 / 字段）</div>
            <div class="knowledge-table-wrap">
              <table class="knowledge-table db-result-table">
                <thead>
                  <tr>
                    <th>数据库</th>
                    <th>数据表</th>
                    <th>字段</th>
                    <th>类型</th>
                    <th>字段注释</th>
                    <th>匹配方式</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="hit in searchResult.columnHits" :key="hit.database + '|' + hit.table + '|' + hit.column">
                    <td><span class="db-database-name">{{ hit.database }}</span></td>
                    <td>
                      <span class="db-table-name">{{ hit.table }}</span>
                      <span v-if="hit.tableComment" class="db-table-comment">{{ hit.tableComment }}</span>
                    </td>
                    <td class="mono">{{ hit.column }}</td>
                    <td class="mono">{{ hit.columnType }}</td>
                    <td>{{ hit.columnComment || '—' }}</td>
                    <td><span class="db-match-type">{{ hit.matchType }}</span></td>
                  </tr>
                  <tr v-if="!searchResult.columnHits.length">
                    <td colspan="6" class="knowledge-empty-cell">没有匹配到表或字段，尝试更换关键词</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <template v-if="searchResult.includeData">
              <div class="db-result-title">数据内容命中</div>
              <div class="knowledge-table-wrap">
                <table class="knowledge-table db-result-table">
                  <thead>
                    <tr>
                      <th>数据库</th>
                      <th>数据表</th>
                      <th>字段</th>
                      <th>命中值</th>
                      <th>行标识</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(hit, i) in searchResult.dataHits" :key="hit.database + '|' + hit.table + '|' + hit.column + '|' + i">
                      <td><span class="db-database-name">{{ hit.database }}</span></td>
                      <td>
                        <span class="db-table-name">{{ hit.table }}</span>
                        <span v-if="hit.tableComment" class="db-table-comment">{{ hit.tableComment }}</span>
                      </td>
                      <td>
                        <span class="mono">{{ hit.column }}</span>
                        <span v-if="hit.columnComment" class="db-table-comment">{{ hit.columnComment }}</span>
                      </td>
                      <td class="db-sample">{{ hit.sampleValue }}</td>
                      <td class="mono">{{ hit.rowRef }}</td>
                    </tr>
                    <tr v-if="!searchResult.dataHits.length">
                      <td colspan="5" class="knowledge-empty-cell">数据内容中没有匹配值</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </div>
          <div v-else-if="!searching" class="db-empty-hint">
            输入关键词后点击“检索”。勾选“同时检索表内数据内容”可以直接定位某条数据所在的表和字段。
          </div>
        </div>

        <!-- 表结构子模块 -->
        <div v-if="activeModule?.type === 'SCHEMA'" class="db-section">
          <div class="db-toolbar">
            <div class="db-field-summary">
              数据库 <strong>{{ selectedDatabase }}</strong> · 共
              <strong>{{ filteredTables.length }}</strong> 张表
              <span v-if="!isProjectDatabase" class="db-action-hint">（非项目库，新增字段只改数据库结构）</span>
            </div>
            <div class="db-toolbar-actions">
              <input v-model="tableFilter" type="search" placeholder="过滤表名 / 字段 / 注释">
              <button class="btn btn-ghost btn-sm" type="button" :disabled="tablesLoading" @click="loadTables(true)">
                {{ tablesLoading ? '刷新中…' : '刷新' }}
              </button>
            </div>
          </div>
          <div v-if="!isProjectDatabase" class="db-warning">
            当前选择的是非项目数据库，新增字段会直接修改该库的表结构，不会同步到本项目的广告牌表单或前台。
          </div>

          <!-- 任意表新增字段 -->
          <div v-if="showTableColumnForm" class="db-inline-form">
            <div class="db-inline-head">
              <h4>在 {{ selectedDatabase }}.{{ tableColumnTarget?.name }} 新增字段</h4>
              <button class="modal-close" type="button" title="关闭" @click="showTableColumnForm = false">×</button>
            </div>
            <div class="db-form-note">
              将执行 <code>ALTER TABLE {{ selectedDatabase }}.{{ tableColumnTarget?.name }} ADD COLUMN ...</code>，
              直接修改数据库结构；非项目库请确认影响范围。
            </div>
            <div class="form-grid">
              <div class="form-field">
                <label for="db-table-column-name">字段名 <em>*</em></label>
                <input id="db-table-column-name" v-model="tableColumnForm.fieldName" type="text" maxlength="64" placeholder="如 remark_code">
              </div>
              <div class="form-field">
                <label for="db-table-column-type">字段类型 <em>*</em></label>
                <select id="db-table-column-type" v-model="tableColumnForm.type" @change="applyTableTypeDefaults">
                  <option v-for="t in TABLE_COLUMN_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
                </select>
              </div>
              <div v-if="tableColumnTypeMeta.length" class="form-field">
                <label for="db-table-column-length">
                  {{ tableColumnForm.type === 'DECIMAL' ? '精度（总位数）' : '字段长度' }}
                </label>
                <input id="db-table-column-length" v-model="tableColumnForm.length" type="number" min="1" placeholder="如 200">
              </div>
              <div v-if="tableColumnTypeMeta.decimals" class="form-field">
                <label for="db-table-column-decimals">小数位</label>
                <input id="db-table-column-decimals" v-model="tableColumnForm.decimals" type="number" min="0" placeholder="如 2">
              </div>
              <div class="form-field">
                <label for="db-table-column-default">默认值</label>
                <input id="db-table-column-default" v-model="tableColumnForm.defaultValue" type="text" maxlength="500" placeholder="可留空">
              </div>
              <div class="form-field">
                <label for="db-table-column-comment">字段注释</label>
                <input id="db-table-column-comment" v-model="tableColumnForm.comment" type="text" maxlength="500" placeholder="如：扩展备注">
              </div>
              <div class="form-field span-2">
                <label class="admin-check">
                  <input v-model="tableColumnForm.nullable" type="checkbox">
                  <span>允许 NULL（取消勾选时必须填写默认值，避免存量数据加列失败）</span>
                </label>
              </div>
            </div>
            <div class="db-inline-actions">
              <button class="btn btn-primary" type="button" :disabled="tableColumnSaving" @click="saveTableColumn">
                {{ tableColumnSaving ? '新增中…' : '新增字段并同步数据库' }}
              </button>
              <button class="btn btn-ghost" type="button" @click="showTableColumnForm = false">取消</button>
            </div>
          </div>

          <div v-if="tablesLoading && !tables.length" class="db-empty-hint">正在读取数据库结构…</div>
          <div v-else class="db-schema-list">
            <div v-for="t in filteredTables" :key="t.name" class="db-schema-table">
              <div class="db-schema-row">
                <button class="db-schema-head" type="button" @click="toggleTable(t.name)">
                  <span class="db-schema-caret">{{ expandedTables[t.name] ? '▾' : '▸' }}</span>
                  <span class="db-schema-name">{{ t.name }}</span>
                  <span class="db-schema-comment">{{ t.comment || '无表注释' }}</span>
                  <span class="db-schema-count">{{ t.columns.length }} 列 · 约 {{ fmtRows(t.rows) }} 行</span>
                </button>
                <div class="db-schema-actions">
                  <button
                    v-if="!isProjectBillboard(t)"
                    class="link-btn"
                    type="button"
                    @click.stop="openTableColumnForm(t)"
                  >新增字段</button>
                  <button
                    v-else
                    class="link-btn"
                    type="button"
                    @click.stop="goBillboardFields"
                  >去广告牌字段子模块</button>
                </div>
              </div>
              <div v-if="expandedTables[t.name]" class="knowledge-table-wrap db-column-wrap">
                <table class="knowledge-table db-column-table">
                  <thead>
                    <tr>
                      <th>字段名</th>
                      <th>类型</th>
                      <th>注释</th>
                      <th>可空</th>
                      <th>默认值</th>
                      <th>键</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="c in t.columns" :key="c.name">
                      <td class="mono">
                        {{ c.name }}
                        <span v-if="c.primaryKey" class="db-pk">PK</span>
                      </td>
                      <td class="mono">{{ c.type }}</td>
                      <td>{{ c.comment || '—' }}</td>
                      <td>{{ c.nullable ? '是' : '否' }}</td>
                      <td class="mono">{{ c.defaultValue == null ? '—' : c.defaultValue }}</td>
                      <td class="mono">{{ c.key || '—' }}<span v-if="c.extra"> · {{ c.extra }}</span></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
            <div v-if="!filteredTables.length" class="db-empty-hint">没有匹配的数据表</div>
          </div>
        </div>

        <!-- 字段 / 属性子模块 -->
        <div v-if="isFieldModule" class="db-section">
          <div class="db-toolbar">
            <div class="db-field-summary">
              广告牌字段固定管理项目库 <strong>{{ defaultDatabase }}.billboard</strong>；左侧数据库选择只影响检索和表结构。
              共 <strong>{{ columns.length }}</strong> 个数据库字段（其中
              <strong>{{ customColumnCount }}</strong> 个扩展字段）。
              列表直接读取 <code>billboard</code> 表实际结构；新增字段会执行
              <code>ALTER TABLE billboard ADD COLUMN</code>，编辑注释会同步数据库。
            </div>
            <button class="btn btn-primary btn-sm" type="button" @click="openFieldForm()">
              新增{{ isAttributeModule ? '属性' : '字段' }}
            </button>
          </div>
          <div v-if="!isProjectDatabase" class="db-warning">
            当前选中的数据库是 {{ selectedDatabase }}，但广告牌字段/属性子模块始终管理项目库
            {{ defaultDatabase }}.billboard；如需管理其他库的字段，请使用“数据表结构”子模块。
          </div>

          <div class="db-field-toolbar">
            <input v-model="fieldFilter" type="search" placeholder="过滤字段显示名称 / 数据库列 / 注释 / 子模块">
            <span class="db-action-hint">显示 {{ filteredColumns.length }} / {{ columns.length }} 个字段</span>
          </div>

          <div v-if="columnsLoading" class="db-empty-hint">正在读取 billboard 表字段…</div>
          <div v-else class="knowledge-table-wrap">
            <table class="knowledge-table db-field-table">
              <thead>
                <tr>
                  <th>显示名称 / 来源</th>
                  <th>数据库列 / 类型</th>
                  <th>数据库注释</th>
                  <th>可空性</th>
                  <th>前台展示</th>
                  <th>排序</th>
                  <th>同步状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in filteredColumns" :key="c.name">
                  <td>
                    <strong>{{ c.label }}</strong>
                    <span
                      class="db-origin"
                      :class="c.origin === 'SYSTEM' ? 'system' : 'custom'"
                    >{{ sourceLabel(c) }}</span>
                    <span v-if="c.placeholder" class="db-table-comment">{{ c.placeholder }}</span>
                  </td>
                  <td class="mono">
                    {{ c.name }}
                    <span class="db-table-comment">{{ c.type }}</span>
                  </td>
                  <td>{{ c.comment || '—' }}</td>
                  <td>
                    <template v-if="c.origin === 'SYSTEM'">{{ c.nullable ? '可空' : '非空' }}</template>
                    <template v-else>
                      {{ c.required ? '必填' : '可选' }}
                      <span class="db-table-comment">数据库：{{ c.nullable ? '可空' : '非空' }}</span>
                    </template>
                  </td>
                  <td>
                    <template v-if="c.origin === 'SYSTEM'">—</template>
                    <template v-else>{{ c.visible ? '是' : '否' }}</template>
                  </td>
                  <td>{{ c.origin === 'SYSTEM' ? '—' : c.sortOrder }}</td>
                  <td>
                    <span class="knowledge-status" :class="columnStatus(c).cls">
                      {{ columnStatus(c).label }}
                    </span>
                  </td>
                  <td>
                    <div class="db-row-actions">
                      <button
                        v-if="c.origin === 'CUSTOM'"
                        class="link-btn"
                        type="button"
                        title="修改字段名、类型和显示属性"
                        @click="openFieldForm(c)"
                      >编辑</button>
                      <template v-if="c.origin === 'CUSTOM'">
                        <button v-if="!c.synced" class="link-btn" type="button" @click="syncField(c)">同步</button>
                        <button class="link-btn danger" type="button" @click="removeField(c)">删除</button>
                      </template>
                      <button
                        v-else
                        class="link-btn"
                        type="button"
                        title="系统字段结构只读，可编辑数据库注释"
                        @click="openCommentForm(c)"
                      >编辑</button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!filteredColumns.length">
                  <td colspan="8" class="knowledge-empty-cell">
                    {{ columns.length ? '没有匹配当前过滤条件的字段' : '未读取到 billboard 表字段，请确认数据库已初始化' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 字段管理子模块 -->
        <div v-if="activeModule?.type === 'FIELD_MANAGER'" class="db-section">
          <div class="db-toolbar">
            <div class="db-field-summary">
              共 <strong>{{ columns.length }}</strong> 个字段。启用/停用只影响后台表单和前台展示，
              <strong>不会删除数据库列</strong>；扩展字段可随时新增或删除。
            </div>
            <div class="db-toolbar-actions">
              <input v-model="fieldFilter" type="search" placeholder="过滤字段名称 / 数据库列 / 注释">
              <button class="btn btn-primary btn-sm" type="button" @click="openFieldForm()">新增字段</button>
            </div>
          </div>

          <div v-if="columnsLoading" class="db-empty-hint">正在读取字段…</div>
          <div v-else class="knowledge-table-wrap">
            <table class="knowledge-table db-field-table">
              <thead>
                <tr>
                  <th>字段</th>
                  <th>来源</th>
                  <th>启用</th>
                  <th>表单分区</th>
                  <th>栏宽</th>
                  <th>排序</th>
                  <th>同步</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in filteredColumns" :key="c.name">
                  <td>
                    <strong>{{ c.label }}</strong>
                    <span class="db-table-comment">{{ c.name }} · {{ c.type }}</span>
                  </td>
                  <td>
                    <span class="db-origin" :class="c.origin === 'SYSTEM' ? 'system' : 'custom'">
                      {{ c.origin === 'SYSTEM' ? '系统字段' : (c.moduleName || '扩展字段') }}
                    </span>
                  </td>
                  <td>
                    <label v-if="c.origin === 'CUSTOM'" class="admin-check">
                      <input
                        type="checkbox"
                        :checked="c.enabled !== false"
                        @change="toggleFieldEnabled(c)"
                      >
                      <span>{{ c.enabled !== false ? '启用' : '停用' }}</span>
                    </label>
                    <span v-else class="db-action-hint">固定启用</span>
                  </td>
                  <td>
                    <select
                      v-if="c.origin === 'CUSTOM'"
                      class="db-manager-select"
                      :value="c.section || 'extension'"
                      @change="changeFieldSection(c, $event.target.value)"
                    >
                      <option v-for="sec in FORM_SECTIONS" :key="sec.value" :value="sec.value">{{ sec.label }}</option>
                    </select>
                    <span v-else>{{ FORM_SECTION_LABELS[c.section] || '—' }}</span>
                  </td>
                  <td>
                    <select
                      v-if="c.origin === 'CUSTOM'"
                      class="db-manager-select"
                      :value="c.width || 'half'"
                      @change="changeFieldWidth(c, $event.target.value)"
                    >
                      <option value="half">半栏</option>
                      <option value="full">整行</option>
                    </select>
                    <span v-else>—</span>
                  </td>
                  <td>{{ c.origin === 'CUSTOM' ? c.sortOrder : '—' }}</td>
                  <td>
                    <span class="knowledge-status" :class="columnStatus(c).cls">{{ columnStatus(c).label }}</span>
                  </td>
                  <td>
                    <div class="db-row-actions">
                      <template v-if="c.origin === 'CUSTOM'">
                        <button class="link-btn" type="button" @click="moveField(c, -1)">↑</button>
                        <button class="link-btn" type="button" @click="moveField(c, 1)">↓</button>
                        <button class="link-btn" type="button" @click="openFieldForm(c)">编辑</button>
                        <button class="link-btn danger" type="button" @click="removeField(c)">删除</button>
                      </template>
                      <button v-else class="link-btn" type="button" @click="openCommentForm(c)">编辑</button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!filteredColumns.length">
                  <td colspan="8" class="knowledge-empty-cell">
                    {{ columns.length ? '没有匹配当前过滤条件的字段' : '暂无字段' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 表单设计子模块 -->
        <BillboardFormDesigner
          v-if="activeModule?.type === 'FORM_DESIGNER'"
          :columns="columns"
          :loading="columnsLoading"
          :owner-module-id="fieldOwnerId"
          @refresh="loadColumns"
          @fields-changed="onDesignerFieldsChanged"
          @unauthorized="onDesignerUnauthorized"
        />

        <!-- 子模块编辑表单 -->
        <div v-if="showModuleForm" class="db-inline-form">
          <div class="db-inline-head">
            <h4>{{ editingModuleId ? '编辑子模块' : '新建子模块' }}</h4>
            <button class="modal-close" type="button" title="关闭" @click="showModuleForm = false">×</button>
          </div>
          <div class="form-grid">
            <div class="form-field">
              <label for="db-module-name">子模块名称 <em>*</em></label>
              <input id="db-module-name" v-model="moduleForm.name" type="text" maxlength="100" placeholder="如：广告牌照明字段">
            </div>
            <div class="form-field">
              <label for="db-module-code">模块标识 <em v-if="!editingModuleId">*</em></label>
              <input
                id="db-module-code"
                v-model="moduleForm.code"
                type="text"
                maxlength="50"
                :disabled="!!editingModuleId"
                placeholder="小写英文/数字/下划线，如 lighting_fields"
              >
            </div>
            <div class="form-field">
              <label for="db-module-type">子模块类型 <em>*</em></label>
              <select id="db-module-type" v-model="moduleForm.type" :disabled="!!editingModuleId">
                <option v-for="t in MODULE_TYPES" :key="t.value" :value="t.value">{{ t.label }} · {{ t.hint }}</option>
              </select>
            </div>
            <div class="form-field">
              <label for="db-module-sort">排序号</label>
              <input id="db-module-sort" v-model="moduleForm.sortOrder" type="number" placeholder="数值越小越靠前">
            </div>
            <div class="form-field span-2">
              <label for="db-module-desc">说明</label>
              <input id="db-module-desc" v-model="moduleForm.description" type="text" maxlength="500" placeholder="说明该子模块的用途">
            </div>
            <div class="form-field span-2">
              <label class="admin-check">
                <input v-model="moduleForm.enabled" type="checkbox">
                <span>启用该子模块（停用后其字段不会出现在广告牌表单与前台详情）</span>
              </label>
            </div>
          </div>
          <div class="db-inline-actions">
            <button class="btn btn-primary" type="button" :disabled="moduleSaving" @click="saveModule">
              {{ moduleSaving ? '保存中…' : (editingModuleId ? '保存修改' : '创建子模块') }}
            </button>
            <button class="btn btn-ghost" type="button" @click="showModuleForm = false">取消</button>
          </div>
        </div>

        <!-- 字段编辑抽屉 -->
        <div v-if="showFieldForm" class="db-drawer-mask" @click="showFieldForm = false"></div>
        <div v-if="showFieldForm" class="db-inline-form db-drawer-form">
          <div class="db-inline-head">
            <div>
              <h4>{{ editingFieldId ? '编辑字段' : '新增' + (isAttributeModule ? '属性' : '字段') }}</h4>
              <div class="db-drawer-sub">
                {{ editingFieldId
                  ? '可修改字段名、类型和显示属性；结构修改会同步数据库。'
                  : '保存后会同步执行 ALTER TABLE ADD COLUMN。' }}
              </div>
            </div>
            <button class="modal-close" type="button" title="关闭" @click="showFieldForm = false">×</button>
          </div>

          <div v-if="editingFieldId" class="db-form-warning">
            修改字段名或类型会执行 <code>ALTER TABLE billboard CHANGE COLUMN</code>，
            已有数据会按新类型转换，可能丢失精度或格式，请确认后再保存。
          </div>
          <div v-else class="db-form-note">
            新增后会同步执行数据库变更：
            <code>ALTER TABLE billboard ADD COLUMN {{ fieldForm.fieldName || '&lt;field_name&gt;' }} ...</code>
          </div>

          <div v-if="editingFieldId" class="db-stats-card">
            <div class="db-stats-head">
              <strong>数据概况</strong>
              <span v-if="fieldStatsLoading">加载中…</span>
            </div>
            <div v-if="fieldStats" class="db-stats-grid">
              <div><span>总记录</span><strong>{{ fieldStats.totalRows }}</strong></div>
              <div><span>非空</span><strong>{{ fieldStats.nonNullRows }}</strong></div>
              <div><span>空值</span><strong :class="{ warn: fieldStats.nullRows > 0 }">{{ fieldStats.nullRows }}</strong></div>
              <div><span>不同值</span><strong>{{ fieldStats.distinctValues }}</strong></div>
            </div>
            <div v-if="fieldStats?.sampleValues?.length" class="db-stats-samples">
              <span>示例值：</span>
              <code v-for="(v, i) in fieldStats.sampleValues" :key="i">{{ v }}</code>
            </div>
            <div v-if="fieldStats && fieldStats.nullRows > 0 && !fieldForm.nullable" class="db-form-warning db-stats-warning">
              改为非空会将 {{ fieldStats.nullRows }} 条空值填充为空串 / 0；类型变更也可能丢失精度。
            </div>
          </div>

          <div class="db-field-section-title">数据库列与表单属性</div>
          <div class="form-grid">
            <div class="form-field">
              <label for="db-field-label">显示名称 <em>*</em></label>
              <input id="db-field-label" v-model="fieldForm.label" type="text" maxlength="100" placeholder="如：照明方式">
            </div>
            <div class="form-field">
              <label for="db-field-name">字段名（数据库列名） <em>*</em></label>
              <input
                id="db-field-name"
                v-model="fieldForm.fieldName"
                type="text"
                maxlength="64"
                placeholder="小写英文/数字/下划线，如 lighting_type"
              >
              <span class="db-field-hint">重命名会同步修改数据库列，后台表单和前台详情会自动适配。</span>
            </div>
            <div class="form-field">
              <label for="db-field-type">输入类型 <em>*</em></label>
              <select id="db-field-type" v-model="fieldForm.inputType" @change="applyTypeDefaults">
                <option v-for="t in INPUT_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
              <span class="db-field-hint">修改类型会转换已有数据，保存前会二次确认。</span>
            </div>
            <div class="form-field">
              <label for="db-field-sort">排序号</label>
              <input id="db-field-sort" v-model="fieldForm.sortOrder" type="number" placeholder="数值越小越靠前">
            </div>
            <div class="form-field">
              <label for="db-field-section">表单分区</label>
              <select id="db-field-section" v-model="fieldForm.section">
                <option v-for="sec in FORM_SECTIONS" :key="sec.value" :value="sec.value">{{ sec.label }}</option>
              </select>
              <span class="db-field-hint">后台广告牌编辑页按此分区放置字段</span>
            </div>
            <div class="form-field">
              <label for="db-field-width">表单栏宽</label>
              <select id="db-field-width" v-model="fieldForm.width">
                <option value="half">半栏</option>
                <option value="full">整行</option>
              </select>
            </div>
            <div v-if="inputTypeMeta.length" class="form-field">
              <label for="db-field-length">
                {{ fieldForm.inputType === 'DECIMAL' ? '精度（总位数）' : '字段长度' }}
              </label>
              <input id="db-field-length" v-model="fieldForm.length" type="number" min="1" placeholder="如 200">
            </div>
            <div v-if="inputTypeMeta.decimals" class="form-field">
              <label for="db-field-decimals">小数位</label>
              <input id="db-field-decimals" v-model="fieldForm.decimals" type="number" min="0" placeholder="如 2">
            </div>
            <div v-if="inputTypeMeta.options" class="form-field span-2">
              <label for="db-field-options">下拉选项（每行一个） <em>*</em></label>
              <textarea id="db-field-options" v-model="fieldForm.optionsText" rows="4" placeholder="如：&#10;单立柱&#10;跨线桥&#10;LED 屏"></textarea>
            </div>
            <div class="form-field">
              <label for="db-field-default">默认值</label>
              <input id="db-field-default" v-model="fieldForm.defaultValue" type="text" maxlength="500" placeholder="可留空">
            </div>
            <div class="form-field">
              <label for="db-field-placeholder">表单提示文字</label>
              <input id="db-field-placeholder" v-model="fieldForm.placeholder" type="text" maxlength="200" placeholder="如：是否含夜间亮化">
            </div>
            <div class="form-field span-2 db-checks">
              <label class="admin-check">
                <input v-model="fieldForm.required" type="checkbox">
                <span>必填（保存广告牌时校验，数据库列仍允许空值以兼容存量数据）</span>
              </label>
              <label class="admin-check">
                <input v-model="fieldForm.visible" type="checkbox">
                <span>在前台广告牌详情中展示</span>
              </label>
              <label class="admin-check">
                <input id="db-field-nullable" v-model="fieldForm.nullable" type="checkbox">
                <span>允许数据库 NULL（取消勾选为非空；已有空值会填充为空串/0，表单保存时需填写或使用默认值）</span>
              </label>
              <label class="admin-check">
                <input id="db-field-enabled" v-model="fieldForm.enabled" type="checkbox">
                <span>启用字段（停用后不进入后台表单和前台详情，但数据库列保留，可随时重新启用）</span>
              </label>
            </div>
          </div>
          <div class="db-sql-preview-section">
            <div class="db-sql-preview-head">
              <span>将要执行的 SQL</span>
              <button class="link-btn" type="button" @click="copySql(fieldSqlPreview)">复制</button>
            </div>
            <pre class="db-sql-preview">{{ fieldSqlPreview }}</pre>
          </div>
          <div class="db-inline-actions">
            <button class="btn btn-primary" type="button" :disabled="fieldSaving" @click="saveField">
              {{ fieldSaving ? '保存并同步中…' : (editingFieldId ? '保存修改' : '新增并同步数据库') }}
            </button>
            <button class="btn btn-ghost" type="button" @click="showFieldForm = false">取消</button>
          </div>
        </div>

        <!-- 字段注释编辑抽屉 -->
        <div v-if="showCommentForm" class="db-drawer-mask" @click="showCommentForm = false"></div>
        <div v-if="showCommentForm" class="db-inline-form db-drawer-form">
          <div class="db-inline-head">
            <div>
              <h4>编辑数据库字段注释</h4>
              <div class="db-drawer-sub">
                字段 <code>{{ commentForm.name }}</code>
                · {{ commentForm.origin === 'SYSTEM' ? '系统字段' : '扩展字段 · ' + commentForm.moduleName }}
              </div>
            </div>
            <button class="modal-close" type="button" title="关闭" @click="showCommentForm = false">×</button>
          </div>
          <div v-if="commentForm.origin === 'SYSTEM' && systemStructureEditable" class="db-form-note">
            系统字段的物理列名不可修改；类型 / 长度 / 精度 / 可空性只在兼容业务类型族内开放，
            默认值、索引、ON UPDATE 等属性会保留。
          </div>
          <div v-else-if="commentForm.origin === 'SYSTEM'" class="db-form-note">
            该字段结构由业务代码维护（主键 / 时间戳 / 删除标记等），只能修改数据库注释。
          </div>
          <div v-else class="db-form-note">
            保存后会更新扩展字段的显示名称和数据库列注释。
          </div>
          <div class="db-readonly-grid">
            <div><span>数据库列</span><strong class="mono">{{ commentForm.name }}</strong></div>
            <div><span>字段类型</span><strong class="mono">{{ commentForm.type || '—' }}</strong></div>
            <div><span>可空</span><strong>{{ commentForm.nullable ? '是' : '否' }}</strong></div>
            <div><span>默认值</span><strong class="mono">{{ commentForm.defaultValue || '—' }}</strong></div>
            <div><span>主键</span><strong>{{ commentForm.primaryKey ? '是' : '否' }}</strong></div>
          </div>
          <div class="db-stats-card">
            <div class="db-stats-head">
              <strong>数据概况</strong>
              <span v-if="fieldStatsLoading">加载中…</span>
            </div>
            <div v-if="fieldStats" class="db-stats-grid">
              <div><span>总记录</span><strong>{{ fieldStats.totalRows }}</strong></div>
              <div><span>非空</span><strong>{{ fieldStats.nonNullRows }}</strong></div>
              <div><span>空值</span><strong :class="{ warn: fieldStats.nullRows > 0 }">{{ fieldStats.nullRows }}</strong></div>
              <div><span>不同值</span><strong>{{ fieldStats.distinctValues }}</strong></div>
            </div>
            <div v-if="fieldStats?.sampleValues?.length" class="db-stats-samples">
              <span>示例值：</span>
              <code v-for="(v, i) in fieldStats.sampleValues" :key="i">{{ v }}</code>
            </div>
          </div>
          <template v-if="commentForm.origin === 'SYSTEM' && systemStructureEditable">
            <div class="db-field-section-title">数据库结构</div>
            <div class="form-grid">
              <div class="form-field">
                <label for="db-system-type">字段类型</label>
                <select id="db-system-type" v-model="commentForm.baseType" @change="onSystemTypeChange">
                  <option v-for="t in systemTypeOptions" :key="t" :value="t">{{ t }}</option>
                </select>
              </div>
              <div v-if="systemShowLength" class="form-field">
                <label for="db-system-length">
                  {{ commentForm.baseType === 'DECIMAL' ? '精度（总位数）' : '字段长度' }}
                </label>
                <input id="db-system-length" v-model="commentForm.length" type="number" min="1">
              </div>
              <div v-if="systemShowDecimals" class="form-field">
                <label for="db-system-decimals">小数位</label>
                <input id="db-system-decimals" v-model="commentForm.decimals" type="number" min="0">
              </div>
              <div class="form-field span-2">
                <label class="admin-check">
                  <input
                    id="db-system-nullable"
                    v-model="commentForm.nullable"
                    type="checkbox"
                    :disabled="systemRule.nullableFixed"
                  >
                  <span>
                    允许数据库 NULL
                    <template v-if="systemRule.nullableFixed">（可空性由业务代码固定）</template>
                  </span>
                </label>
              </div>
            </div>
          </template>
          <div class="form-grid">
            <div class="form-field span-2">
              <label for="db-column-comment">
                {{ commentForm.origin === 'SYSTEM' ? '字段注释' : '字段注释 / 显示名称' }}
              </label>
              <input
                id="db-column-comment"
                v-model="commentForm.comment"
                type="text"
                :maxlength="commentForm.origin === 'CUSTOM' ? 100 : 500"
                placeholder="如：广告牌名称"
              >
            </div>
          </div>
          <div class="db-sql-preview-section">
            <div class="db-sql-preview-head">
              <span>将要执行的 SQL</span>
              <button class="link-btn" type="button" @click="copySql(commentSqlPreview)">复制</button>
            </div>
            <pre class="db-sql-preview">{{ commentSqlPreview }}</pre>
          </div>
          <div class="db-form-note">
            系统字段注释由后端读取当前列定义后只替换 COMMENT；上面的 <code>...</code> 表示保留类型、默认值、自增等原定义。
          </div>
          <div class="db-inline-actions">
            <button class="btn btn-primary" type="button" :disabled="commentSaving" @click="saveComment">
              {{ commentSaving ? '保存并同步中…' : '保存并同步数据库' }}
            </button>
            <button class="btn btn-ghost" type="button" @click="showCommentForm = false">取消</button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
