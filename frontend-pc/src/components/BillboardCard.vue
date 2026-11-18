<script setup>
/**
 * 广告牌卡片：实拍图、状态徽章、路段、报价、参数摘要与操作按钮。
 */
import { computed } from 'vue'
import { fmtPrice, statusInfo } from '../utils/format'

const props = defineProps({
  item: { type: Object, required: true },
})

const emit = defineEmits(['detail', 'locate'])

const st = computed(() => statusInfo(props.item.status))
const cover = computed(() => props.item.images && props.item.images[0])
const chips = computed(() =>
  [
    props.item.size && '尺寸 ' + props.item.size,
    props.item.material,
    props.item.visibleArea,
  ].filter(Boolean),
)
const specText = computed(() => chips.value.join(' · '))
const hasCoord = computed(() =>
  props.item.longitude != null && props.item.latitude != null)
const priceText = computed(() =>
  props.item.price == null || props.item.price === '' ? '面议' : '¥' + fmtPrice(props.item.price))
</script>

<template>
  <article class="bb-card">
    <div class="bb-card-cover" title="查看详情" @click="emit('detail', item)">
      <img v-if="cover" :src="cover" :alt="item.name" loading="lazy">
      <div v-else class="cover-empty">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="11" rx="1" />
          <path d="M8 20l2-5M16 20l-2-5M6 20h4M14 20h4" />
        </svg>
        <span>暂无实拍图</span>
      </div>
      <span class="badge" :class="st.cls">{{ st.label }}</span>
    </div>
    <div class="bb-card-body">
      <div class="bb-card-title" @click="emit('detail', item)">{{ item.name }}</div>
      <div class="bb-card-road" :title="(item.road || '') + ' ' + (item.section || '')">
        <span class="bb-meta-label">路段</span>
        <span class="bb-meta-value">{{ item.road || '未知路段' }}<template v-if="item.section"> · {{ item.section }}</template></span>
      </div>
      <div v-if="specText" class="bb-card-spec" :title="specText">
        <span class="bb-meta-label">规格</span>
        <span class="bb-meta-value">{{ specText }}</span>
      </div>
      <div class="bb-card-price">
        <span class="price-label">租赁报价</span>
        <span class="price-num">{{ priceText }}</span>
        <span class="price-unit">{{ item.priceUnit || '' }}</span>
      </div>
    </div>
    <div class="bb-card-actions">
      <button class="btn btn-primary" type="button" @click="emit('detail', item)">查看详情</button>
      <button
        class="btn btn-ghost" type="button" title="在地图中定位"
        :disabled="!hasCoord" @click="emit('locate', item)"
      >地图定位</button>
    </div>
  </article>
</template>
