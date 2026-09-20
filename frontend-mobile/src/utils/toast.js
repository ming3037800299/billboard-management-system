/**
 * 轻量全局 toast（响应式数组，App.vue 中渲染宿主）。
 */
import { reactive } from 'vue'

let seed = 0

export const toasts = reactive([])

export function toast(message, type) {
  const id = ++seed
  toasts.push({ id, message, type })
  setTimeout(() => {
    const idx = toasts.findIndex((t) => t.id === id)
    if (idx >= 0) toasts.splice(idx, 1)
  }, 2600)
}
