<script setup>
/**
 * 数据库维护面板（后台）：
 *   - 左侧：可配置的多个子模块（检索 / 表结构 / 广告牌字段 / 广告牌属性）
 *   - 检索：按表名、字段名、中文注释或表内数据内容定位数据所在表/字段
 *   - 表结构：浏览全部表、字段类型、注释、主键与数据量
 *   - 字段/属性：新增扩展字段时同步执行 ALTER TABLE billboard ADD COLUMN
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { toast } from '../utils/toast'
import { fmtDateTime } from '../utils/format'

const emit = defineEmits(['unauthorized', 'fields-changed'])

const loading = ref(true)
const error = ref('')
const modules = ref([])
const activeId = ref(null)

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
})

const tables = ref([])
const tablesLoading = ref(false)
const tablesLoaded = ref(false)
const tableFilter = ref('')
const expandedTables = reactive({})

const searchForm = reactive({ keyword: '', includeData: false })
const searching = ref(false)
const searchError = ref('')
const searchResult = ref(null)

const showModuleForm = ref(false)
const editingModuleId = ref(null)
const moduleSaving = ref(false)
const moduleForm = reactive(emptyModuleForm())

const showFieldForm = ref(false)
const editingFieldId = ref(null)
const fieldSaving = ref(false)
const fieldForm = reactive(emptyFieldForm())

const MODULE_TYPES = [
  { value: 'SEARCH', label: '数据库检索', hint: '按表名/字段/注释/数据内容检索' },
  { value: 'SCHEMA', label: '数据表结构', hint: '浏览表与字段结构' },
  { value: 'BILLBOARD_FIELD', label: '广告牌字段', hint: '新增物理字段并同步数据库' },
  { value: 'BILLBOARD_ATTRIBUTE', label: '广告牌属性', hint: '新增业务属性并同步数据库' },
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

const activeModule = computed(() => modules.value.find((m) => m.id === activeId.value) || null)
const isFieldModule = computed(
  () => activeModule.value?.type === 'BILLBOARD_FIELD' || activeModule.value?.type === 'BILLBOARD_ATTRIBUTE',
)
const isAttributeModule = computed(() => activeModule.value?.type === 'BILLBOARD_ATTRIBUTE')
const inputTypeMeta = computed(() => INPUT_TYPES.find((t) => t.value === fieldForm.inputType) || {})
const customColumnCount = computed(
  () => columns.value.filter((c) => c.origin === 'CUSTOM').length,
)

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
    defaultValue: '',
    optionsText: '',
    placeholder: '',
    sortOrder: 0,
    visible: true,
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

async function loadOverview() {
  overviewError.value = ''
  try {
    overview.value = await api.dbOverview()
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
    await Promise.all([loadModules(), loadOverview()])
    await loadActiveContent()
  } catch (e) {
    handleError(e, '数据库维护信息加载失败')
  } finally {
    loading.value = false
  }
}

async function loadActiveContent() {
  const module = activeModule.value
  if (!module) return
  if (module.type === 'SCHEMA') {
    await loadTables()
  } else if (isFieldModule.value) {
    await loadColumns()
  }
}

async function loadTables(force = false) {
  if (tablesLoading.value) return
  if (tablesLoaded.value && !force) return
  tablesLoading.value = true
  error.value = ''
  try {
    tables.value = await api.dbTables()
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
    `确认删除子模块「${module.name}」？\n仅可删除没有字段的自定义子模块，不会修改数据库表结构。`,
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
  if (column) {
    Object.assign(fieldForm, {
      label: column.label,
      fieldName: column.name,
      inputType: column.inputType || 'TEXT',
      length: column.length ?? (column.inputType === 'DECIMAL' ? 18 : 200),
      decimals: column.decimals ?? 2,
      required: column.required,
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
  showFieldForm.value = true
  showModuleForm.value = false
  showCommentForm.value = false
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

async function saveField() {
  if (fieldSaving.value) return
  if (!fieldForm.label.trim()) {
    toast('请填写字段显示名称', 'error')
    return
  }
  if (!editingFieldId.value && !fieldForm.fieldName.trim()) {
    toast('请填写字段名（英文/数字/下划线）', 'error')
    return
  }
  if (fieldForm.inputType === 'SELECT' && !fieldForm.optionsText.trim()) {
    toast('下拉选项字段至少需要一个选项', 'error')
    return
  }
  fieldSaving.value = true
  try {
    const payload = {
      label: fieldForm.label.trim(),
      fieldName: fieldForm.fieldName.trim(),
      inputType: fieldForm.inputType,
      length: fieldForm.length === '' || fieldForm.length == null ? null : Number(fieldForm.length),
      decimals: fieldForm.decimals === '' || fieldForm.decimals == null ? null : Number(fieldForm.decimals),
      required: fieldForm.required,
      defaultValue: fieldForm.defaultValue,
      options: fieldForm.optionsText.split('\n').map((s) => s.trim()).filter(Boolean),
      placeholder: fieldForm.placeholder,
      sortOrder: Number(fieldForm.sortOrder) || 0,
      visible: fieldForm.visible,
    }
    if (editingFieldId.value) {
      await api.dbUpdateField(editingFieldId.value, payload)
      toast('字段设置已保存')
    } else {
      await api.dbAddField(activeId.value, payload)
      toast('字段已新增，数据库列已同步')
    }
    showFieldForm.value = false
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
  })
  showCommentForm.value = true
  showFieldForm.value = false
  showModuleForm.value = false
}

async function saveComment() {
  if (commentSaving.value) return
  commentSaving.value = true
  try {
    await api.dbUpdateColumnComment(commentForm.name, commentForm.comment)
    toast(commentForm.origin === 'CUSTOM'
      ? '字段显示名称与数据库注释已更新'
      : '数据库字段注释已更新')
    showCommentForm.value = false
    await loadColumns()
    await loadModules()
    if (commentForm.origin === 'CUSTOM') {
      emit('fields-changed')
    }
  } catch (e) {
    handleError(e, '字段注释更新失败')
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
  searching.value = true
  searchError.value = ''
  try {
    searchResult.value = await api.dbSearch(keyword, searchForm.includeData)
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

function quickSearch(keyword, includeData = false) {
  searchForm.keyword = keyword
  searchForm.includeData = includeData
  doSearch()
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
  const unit = module.type === 'BILLBOARD_ATTRIBUTE' ? '属性' : '字段'
  return `${module.fieldCount} 个${unit}`
}

function fmtRows(rows) {
  if (rows === null || rows === undefined || rows < 0) return '—'
  return rows.toLocaleString('zh-CN')
}

onMounted(loadAll)
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
            <label class="admin-check">
              <input v-model="searchForm.includeData" type="checkbox">
              <span>同时检索表内数据内容（扫描真实数据，表较大时稍慢）</span>
            </label>
          </div>
          <div class="db-examples">
            <span>快速检索：</span>
            <button type="button" @click="quickSearch('报价')">报价</button>
            <button type="button" @click="quickSearch('京沪高速', true)">京沪高速（含数据）</button>
            <button type="button" @click="quickSearch('price')">price</button>
            <button type="button" @click="quickSearch('状态')">状态</button>
          </div>

          <div v-if="searchError" class="knowledge-error db-search-error">{{ searchError }}</div>

          <div v-if="searchResult" class="db-search-result">
            <div class="db-search-summary">
              关键词「{{ searchResult.keyword }}」：
              字段命中 <strong>{{ searchResult.columnHits.length }}</strong> 项
              <template v-if="searchResult.includeData">
                ，数据命中 <strong>{{ searchResult.dataHits.length }}</strong> 项
                （扫描 {{ searchResult.scannedTables }} 张表<template v-if="searchResult.dataSearchTruncated">，结果已截断</template>）
              </template>
            </div>

            <div class="db-result-title">字段命中（表 / 字段 / 注释）</div>
            <div class="knowledge-table-wrap">
              <table class="knowledge-table db-result-table">
                <thead>
                  <tr>
                    <th>数据表</th>
                    <th>字段</th>
                    <th>类型</th>
                    <th>字段注释</th>
                    <th>匹配方式</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="hit in searchResult.columnHits" :key="hit.table + '|' + hit.column">
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
                    <td colspan="5" class="knowledge-empty-cell">没有匹配到表或字段，尝试更换关键词</td>
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
                      <th>数据表</th>
                      <th>字段</th>
                      <th>命中值</th>
                      <th>行标识</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(hit, i) in searchResult.dataHits" :key="hit.table + '|' + hit.column + '|' + i">
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
                      <td colspan="4" class="knowledge-empty-cell">数据内容中没有匹配值</td>
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
            <input v-model="tableFilter" type="search" placeholder="过滤表名 / 字段 / 注释">
            <button class="btn btn-ghost btn-sm" type="button" :disabled="tablesLoading" @click="loadTables(true)">
              {{ tablesLoading ? '刷新中…' : '刷新' }}
            </button>
          </div>
          <div v-if="tablesLoading && !tables.length" class="db-empty-hint">正在读取数据库结构…</div>
          <div v-else class="db-schema-list">
            <div v-for="t in filteredTables" :key="t.name" class="db-schema-table">
              <button class="db-schema-head" type="button" @click="toggleTable(t.name)">
                <span class="db-schema-caret">{{ expandedTables[t.name] ? '▾' : '▸' }}</span>
                <span class="db-schema-name">{{ t.name }}</span>
                <span class="db-schema-comment">{{ t.comment || '无表注释' }}</span>
                <span class="db-schema-count">{{ t.columns.length }} 列 · 约 {{ fmtRows(t.rows) }} 行</span>
              </button>
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
              共 <strong>{{ columns.length }}</strong> 个数据库字段（其中
              <strong>{{ customColumnCount }}</strong> 个扩展字段）。
              列表直接读取 <code>billboard</code> 表实际结构；新增字段会执行
              <code>ALTER TABLE billboard ADD COLUMN</code>，编辑注释会同步数据库。
            </div>
            <button class="btn btn-primary btn-sm" type="button" @click="openFieldForm()">
              新增{{ isAttributeModule ? '属性' : '字段' }}
            </button>
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
                <tr v-for="c in columns" :key="c.name">
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
                    <template v-else>{{ c.required ? '必填' : '可选' }}</template>
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
                      <template v-if="isOwnColumn(c)">
                        <button class="link-btn" type="button" @click="openFieldForm(c)">编辑</button>
                        <button v-if="!c.synced" class="link-btn" type="button" @click="syncField(c)">同步</button>
                        <button class="link-btn danger" type="button" @click="removeField(c)">删除</button>
                      </template>
                      <span v-else-if="c.origin === 'CUSTOM'" class="db-action-hint">
                        在「{{ c.moduleName }}」中编辑
                      </span>
                      <button class="link-btn" type="button" @click="openCommentForm(c)">编辑注释</button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!columns.length">
                  <td colspan="8" class="knowledge-empty-cell">
                    未读取到 billboard 表字段，请确认数据库已初始化
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

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

        <!-- 字段编辑表单 -->
        <div v-if="showFieldForm" class="db-inline-form">
          <div class="db-inline-head">
            <h4>{{ editingFieldId ? '编辑字段设置' : '新增' + (isAttributeModule ? '属性' : '字段') }}</h4>
            <button class="modal-close" type="button" title="关闭" @click="showFieldForm = false">×</button>
          </div>
          <div class="db-form-note">
            新增后会同步执行数据库变更：
            <code>ALTER TABLE billboard ADD COLUMN {{ fieldForm.fieldName || '<field_name>' }} ...</code>
          </div>
          <div class="form-grid">
            <div class="form-field">
              <label for="db-field-label">显示名称 <em>*</em></label>
              <input id="db-field-label" v-model="fieldForm.label" type="text" maxlength="100" placeholder="如：照明方式">
            </div>
            <div class="form-field">
              <label for="db-field-name">字段名（数据库列名） <em v-if="!editingFieldId">*</em></label>
              <input
                id="db-field-name"
                v-model="fieldForm.fieldName"
                type="text"
                maxlength="64"
                :disabled="!!editingFieldId"
                placeholder="小写英文/数字/下划线，如 lighting_type"
              >
            </div>
            <div class="form-field">
              <label for="db-field-type">输入类型 <em>*</em></label>
              <select
                id="db-field-type"
                v-model="fieldForm.inputType"
                :disabled="!!editingFieldId"
                @change="applyTypeDefaults"
              >
                <option v-for="t in INPUT_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
            </div>
            <div class="form-field">
              <label for="db-field-sort">排序号</label>
              <input id="db-field-sort" v-model="fieldForm.sortOrder" type="number" placeholder="数值越小越靠前">
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
            </div>
          </div>
          <div class="db-inline-actions">
            <button class="btn btn-primary" type="button" :disabled="fieldSaving" @click="saveField">
              {{ fieldSaving ? '保存并同步中…' : (editingFieldId ? '保存修改' : '新增并同步数据库') }}
            </button>
            <button class="btn btn-ghost" type="button" @click="showFieldForm = false">取消</button>
          </div>
        </div>

        <!-- 数据库字段注释编辑表单 -->
        <div v-if="showCommentForm" class="db-inline-form">
          <div class="db-inline-head">
            <h4>编辑数据库字段注释</h4>
            <button class="modal-close" type="button" title="关闭" @click="showCommentForm = false">×</button>
          </div>
          <div class="db-form-note">
            字段 <code>{{ commentForm.name }}</code>
            （{{ commentForm.origin === 'SYSTEM' ? '系统字段' : '扩展字段 · ' + commentForm.moduleName }}）。
            保存后会修改数据库列注释；扩展字段会同时更新表单显示名称。
          </div>
          <div class="form-grid">
            <div class="form-field span-2">
              <label for="db-column-comment">字段注释 / 显示名称</label>
              <input
                id="db-column-comment"
                v-model="commentForm.comment"
                type="text"
                :maxlength="commentForm.origin === 'CUSTOM' ? 100 : 500"
                placeholder="如：广告牌名称"
              >
            </div>
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
