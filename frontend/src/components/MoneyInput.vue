<script setup lang="ts">
/**
 * 金额输入框：v-model 始终是字符串，内部不出现 Number()/parseFloat。
 * - 只允许数字与一个小数点（可选负号，需 allow-negative）
 * - 失焦时按 scale 位小数格式化（默认 2 位，HALF_UP）
 * - 粘贴 Excel 内容时自动剔除千分位逗号、货币符号、空白
 */
import { ref, watch } from 'vue'
import { round } from '@/utils/money'

const props = withDefaults(
  defineProps<{
    modelValue: string
    scale?: number
    disabled?: boolean
    readonly?: boolean
    placeholder?: string
    allowNegative?: boolean
    allowEmpty?: boolean
    align?: 'left' | 'right'
    ariaLabel?: string
  }>(),
  {
    scale: 2,
    disabled: false,
    readonly: false,
    placeholder: '',
    allowNegative: false,
    allowEmpty: false,
    align: 'right',
    ariaLabel: '金额',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const draft = ref<string>(props.modelValue ?? '')

const VALID = /^-?\d+(?:\.\d+)?$/

/** 清洗草稿：只保留数字与一个小数点，负号仅允许出现在开头。 */
function sanitize(raw: string): string {
  let value = raw.replace(/[^\d.-]/g, '')
  const negative = props.allowNegative && value.includes('-')
  value = value.replace(/-/g, '')
  const dotIndex = value.indexOf('.')
  if (dotIndex >= 0) {
    value = value.slice(0, dotIndex + 1) + value.slice(dotIndex + 1).replace(/\./g, '')
  }
  if (negative) {
    value = `-${value}`
  }
  return value
}

function emitIfValid(value: string) {
  if (VALID.test(value)) {
    emit('update:modelValue', value)
  }
}

function onInput(event: Event) {
  const target = event.target as HTMLInputElement
  const cleaned = sanitize(target.value)
  draft.value = cleaned
  if (target.value !== cleaned) {
    target.value = cleaned
  }
  // 允许 "12." 这类输入中的中间态留在输入框里，但只在完整数字时同步 v-model
  emitIfValid(cleaned)
}

function commit() {
  const cleaned = sanitize(draft.value)
  if (cleaned === '' || cleaned === '-' || cleaned === '.') {
    if (props.allowEmpty) {
      draft.value = ''
      emit('update:modelValue', '')
    } else {
      const zero = round('0', props.scale)
      draft.value = zero
      emit('update:modelValue', zero)
    }
    emit('change', draft.value)
    return
  }
  const normalized = round(cleaned, props.scale)
  draft.value = normalized
  emit('update:modelValue', normalized)
  emit('change', normalized)
}

function onFocus(event: FocusEvent) {
  ;(event.target as HTMLInputElement).select()
}

// 外部（接口回填、撤销等）修改值时同步显示，避免在用数值覆盖用户正在输入的内容
watch(
  () => props.modelValue,
  (next) => {
    const incoming = next ?? ''
    if (incoming === '') {
      if (props.allowEmpty) draft.value = ''
      return
    }
    const current = draft.value
    if (current !== incoming && (!VALID.test(current) || !VALID.test(incoming) || round(current, props.scale) !== round(incoming, props.scale))) {
      draft.value = incoming
    }
  },
)
</script>

<template>
  <input
    class="money-input"
    :class="{ 'is-right': align === 'right' }"
    type="text"
    inputmode="decimal"
    autocomplete="off"
    :value="draft"
    :disabled="disabled"
    :readonly="readonly"
    :placeholder="placeholder"
    :aria-label="ariaLabel"
    @input="onInput"
    @blur="commit"
    @focus="onFocus"
    @keydown.enter.prevent="commit"
  />
</template>

<style scoped>
.money-input {
  width: 100%;
  box-sizing: border-box;
  padding: 0 11px;
  height: 32px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 4px;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
  background: #fff;
  color: var(--el-text-color-regular, #606266);
  outline: none;
  transition: border-color 0.2s;
}
.money-input.is-right {
  text-align: right;
}
.money-input:focus {
  border-color: var(--el-color-primary, #409eff);
}
.money-input:disabled,
.money-input[readonly] {
  background: var(--el-fill-color-light, #f5f7fa);
  cursor: not-allowed;
}
.money-input::placeholder {
  color: var(--el-text-color-placeholder, #a8abb2);
}
</style>
