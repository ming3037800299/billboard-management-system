<script setup>
/**
 * 广告牌扩展字段输入控件：
 * 由数据库维护模块的字段定义决定控件类型、分区与栏宽。
 * 放在现有表单 fieldset 的 .form-grid 中，只渲染字段本身。
 */
const props = defineProps({
  fields: { type: Array, default: () => [] },
  values: { type: Object, required: true },
})

function inputTypeOf(field) {
  if (field.inputType === 'DATE') return 'date'
  if (field.inputType === 'DATETIME') return 'datetime-local'
  return 'text'
}
</script>

<template>
  <div
    v-for="field in fields"
    :key="field.fieldName"
    class="form-field"
    :class="{ 'span-2': field.width === 'full' || field.inputType === 'TEXTAREA' }"
  >
    <label :for="'cf-' + field.fieldName">
      {{ field.label }}
      <em v-if="field.required || field.nullable === false">*</em>
    </label>

    <select
      v-if="field.inputType === 'SELECT'"
      :id="'cf-' + field.fieldName"
      v-model="values[field.fieldName]"
    >
      <option value="">请选择</option>
      <option v-for="opt in (field.options || [])" :key="opt" :value="opt">{{ opt }}</option>
    </select>

    <select
      v-else-if="field.inputType === 'BOOLEAN'"
      :id="'cf-' + field.fieldName"
      v-model="values[field.fieldName]"
    >
      <option value="">未选择</option>
      <option value="true">是</option>
      <option value="false">否</option>
    </select>

    <textarea
      v-else-if="field.inputType === 'TEXTAREA'"
      :id="'cf-' + field.fieldName"
      v-model="values[field.fieldName]"
      rows="3"
      :placeholder="field.placeholder || ''"
    ></textarea>

    <input
      v-else
      :id="'cf-' + field.fieldName"
      v-model="values[field.fieldName]"
      :type="inputTypeOf(field)"
      :placeholder="field.placeholder || ''"
    >

    <span v-if="field.placeholder && field.inputType !== 'TEXTAREA'" class="db-field-hint">
      {{ field.placeholder }}
    </span>
  </div>
</template>
