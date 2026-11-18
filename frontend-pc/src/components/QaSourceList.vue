<script setup>
import { fmtPrice, statusInfo } from '../utils/format'

defineProps({
  sources: { type: Array, default: () => [] },
  showTitle: { type: Boolean, default: false },
})

defineEmits(['select'])
</script>

<template>
  <div class="qa-source-list">
    <div v-if="showTitle" class="qa-sources-title">参考广告牌</div>
    <div
      v-for="s in sources"
      :key="s.id"
      class="qa-source"
      @click="$emit('select', s.id)"
    >
      <div class="qa-source-main">
        <span class="qa-source-name">{{ s.name }}</span>
        <span class="badge" :class="statusInfo(s.status).cls">{{ statusInfo(s.status).label }}</span>
      </div>
      <div class="qa-source-meta">
        {{ s.code }} · {{ s.road || '未知路段' }}<template v-if="s.section"> {{ s.section }}</template>
        · {{ s.price == null || s.price === '' ? '面议' : '¥' + fmtPrice(s.price) }} {{ s.priceUnit || '' }}
      </div>
      <div class="qa-source-score">
        <template v-if="s.matchType === 'RECOMMEND'">初选推荐</template>
        <template v-else-if="s.matchType === 'GUESS'">猜你喜欢</template>
        <template v-else>
          {{ s.matchType === 'IMAGE' ? '视觉相似度' : '相关度' }}
          {{ (s.score * 100).toFixed(1) }}%
        </template>
      </div>
    </div>
  </div>
</template>
