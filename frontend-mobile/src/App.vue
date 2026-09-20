<script setup>
// 根组件：路由视图 + 全局 toast 提示宿主
import { toasts } from './utils/toast'
</script>

<template>
  <router-view />
  <div class="toast-host">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="{ 'toast-error': t.type === 'error' }">
      {{ t.message }}
    </div>
  </div>
</template>
