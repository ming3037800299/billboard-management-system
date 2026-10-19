<script setup>
/**
 * 广告牌详情弹窗：
 *   - 图片画廊（主图 + 缩略图切换，支持键盘 ← → 翻图、Esc 关闭）
 *   - 完整参数表（空值行自动隐藏）、报价、描述
 *   - 底部操作按钮：地图定位 / 关闭
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { fmtDateTime, fmtPrice, statusInfo } from '../utils/format'

const props = defineProps({
  /** 广告牌视图对象 */
  item: { type: Object, required: true },
  /** 数据库维护模块配置的扩展字段元数据 */
  fields: { type: Array, default: () => [] },
})

const emit = defineEmits(['close', 'locate'])

const images = computed(() => props.item.images || [])
const activeIndex = ref(0)
const st = computed(() => statusInfo(props.item.status))

const hasCoord = computed(
  () => props.item.longitude != null && props.item.latitude != null,
)

/** 报价展示：未填写时显示"面议"。 */
const priceText = computed(() =>
  props.item.price == null ? '面议' : '¥' + fmtPrice(props.item.price),
)

/** 扩展字段展示值（仅展示有值且允许前台展示的字段）。 */
function extraFieldText(field, value) {
  if (value === null || value === undefined || value === '') return ''
  if (field.inputType === 'BOOLEAN') {
    const truthy = value === true || value === 1 || value === '1' || value === 'true'
    const falsy = value === false || value === 0 || value === '0' || value === 'false'
    if (truthy) return '是'
    if (falsy) return '否'
    return ''
  }
  if (field.inputType === 'DATETIME') return String(value).replace('T', ' ')
  return String(value)
}

const extraRows = computed(() => (props.fields || [])
  .map((field) => ({
    label: field.label,
    value: extraFieldText(field, props.item.extraValues?.[field.fieldName]),
    wide: field.inputType === 'TEXTAREA',
  }))
  .filter((row) => row.value !== ''))

/** 参数表数据（空值行由模板过滤掉）。 */
const rows = computed(() => {
  const it = props.item
  return [
    { label: '广告牌编号', value: it.code },
    { label: '租赁状态', value: st.value.label },
    { label: '所在公路', value: it.road },
    { label: '所属路段', value: it.section },
    { label: '详细位置', value: it.address, wide: true },
    {
      label: '经纬度坐标',
      value: hasCoord.value ? `${it.longitude}, ${it.latitude}` : '暂未标注坐标',
      wide: true,
      muted: !hasCoord.value,
    },
    { label: '尺寸规格', value: it.size },
    { label: '画面材质', value: it.material },
    { label: '可视面积', value: it.visibleArea },
    { label: '主要受众', value: it.audience },
    ...extraRows.value,
    { label: '计费周期', value: it.priceUnit },
    { label: '报价说明', value: it.priceNote, wide: true },
    { label: '创建时间', value: fmtDateTime(it.createdAt) },
    { label: '最近更新', value: fmtDateTime(it.updatedAt) },
  ].filter((r) => r.value !== null && r.value !== undefined && r.value !== '')
})

/** 键盘翻图（仅在多图时生效）。 */
function step(delta) {
  const len = images.value.length
  if (len < 2) return
  activeIndex.value = (activeIndex.value + delta + len) % len
}

function onKeydown(e) {
  if (e.key === 'Escape') {
    emit('close')
  } else if (e.key === 'ArrowLeft') {
    step(-1)
  } else if (e.key === 'ArrowRight') {
    step(1)
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="modal" role="dialog" aria-modal="true" :aria-label="item.name">
    <div class="modal-mask" @click="emit('close')"></div>
    <div class="modal-card">
      <div class="modal-head">
        <span class="modal-head-title">广告牌详情</span>
        <button class="modal-close" type="button" title="关闭" @click="emit('close')">×</button>
      </div>

      <div class="modal-body">
        <!-- 图片画廊 -->
        <div
          class="detail-gallery"
          :style="images.length > 1 ? null : { gridTemplateColumns: '1fr' }"
        >
          <div class="detail-main-img">
            <img v-if="images.length" :src="images[activeIndex]" :alt="item.name">
            <div v-else class="detail-img-empty">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4" width="18" height="11" rx="1" />
                <path d="M8 20l2-5M16 20l-2-5M6 20h4M14 20h4" />
              </svg>
              <span>暂无实拍图</span>
            </div>
            <span class="badge" :class="st.cls">{{ st.label }}</span>
          </div>

          <div v-if="images.length > 1" class="detail-thumbs">
            <div
              v-for="(img, i) in images"
              :key="img + i"
              class="detail-thumb"
              :class="{ active: i === activeIndex }"
              :title="'第 ' + (i + 1) + ' 张'"
              @click="activeIndex = i"
            >
              <img :src="img" :alt="item.name + ' 图片 ' + (i + 1)">
            </div>
          </div>
        </div>

        <!-- 标题 + 报价 -->
        <div class="detail-head">
          <div>
            <div class="detail-title">{{ item.name }}</div>
            <div class="detail-code">编号：{{ item.code }}</div>
          </div>
          <div class="detail-price">
            <span class="price-num">{{ priceText }}</span>
            <span class="price-unit">{{ item.priceUnit || '' }}</span>
          </div>
        </div>

        <!-- 参数表 -->
        <div class="detail-info">
          <div
            v-for="r in rows"
            :key="r.label"
            class="info-item"
            :class="{ wide: r.wide }"
          >
            <div class="info-label">{{ r.label }}</div>
            <div class="info-value" :class="{ muted: r.muted }">{{ r.value }}</div>
          </div>
        </div>

        <!-- 描述 -->
        <div v-if="item.description" class="detail-desc">{{ item.description }}</div>
      </div>

      <!-- 操作 -->
      <div class="detail-actions">
        <button class="btn btn-ghost" type="button" @click="emit('close')">关闭</button>
        <button
          class="btn btn-primary"
          type="button"
          :disabled="!hasCoord"
          :title="hasCoord ? '在地图中查看位置' : '该广告牌暂未标注坐标'"
          @click="emit('locate', item)"
        >在地图中查看位置</button>
      </div>
    </div>
  </div>
</template>
