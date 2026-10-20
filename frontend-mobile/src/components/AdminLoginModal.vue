<script setup>
/**
 * 管理入口密码验证弹窗（前台"管理入口"按钮触发）。
 * 口令正确 → 写入访问令牌并抛出 success，由父组件跳转后台工作台。
 */
import { onMounted, ref } from 'vue'
import { api, setToken } from '../api'

const emit = defineEmits(['close', 'success'])

const password = ref('')
const error = ref('')
const loading = ref(false)
const inputRef = ref(null)

onMounted(() => inputRef.value?.focus())

async function submit() {
  if (loading.value) return
  if (!password.value) {
    error.value = '请输入管理密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await api.login(password.value)
    setToken(data.token)
    emit('success')
  } catch (e) {
    error.value = e.message || '密码错误，请重新输入'
    password.value = ''
    inputRef.value?.focus()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="modal" role="dialog" aria-modal="true" aria-label="管理入口验证">
    <div class="modal-mask" @click="emit('close')"></div>
    <div class="modal-card modal-card-sm">
      <div class="modal-head">
        <span class="modal-head-title">管理入口验证</span>
        <button class="modal-close" type="button" title="关闭" @click="emit('close')">×</button>
      </div>

      <form class="admin-login" @submit.prevent="submit">
        <p class="admin-login-tip">请输入管理密码，验证通过后进入后台工作台。</p>
        <p class="admin-login-hint">密码由部署环境的 ADMIN_PASSWORD 配置，不是代码里的初始默认值。</p>

        <label for="admin-entry-password">管理密码</label>
        <input
          id="admin-entry-password"
          ref="inputRef"
          v-model="password"
          type="password"
          placeholder="请输入管理密码"
          autocomplete="current-password"
          :disabled="loading"
        >
        <div v-if="error" class="admin-login-error">{{ error }}</div>

        <div class="admin-login-actions">
          <button class="btn btn-ghost" type="button" :disabled="loading" @click="emit('close')">
            取消
          </button>
          <button class="btn btn-primary" type="submit" :disabled="loading">
            {{ loading ? '验证中…' : '进入后台' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
