import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MoneyInput from '../MoneyInput.vue'

describe('MoneyInput', () => {
  test('v-model 始终是字符串，失焦按两位小数格式化', async () => {
    const wrapper = mount(MoneyInput, { props: { modelValue: '' } })
    const input = wrapper.get('input')
    await input.setValue('abc12.345')
    await input.trigger('blur')

    const emitted = wrapper.emitted('update:modelValue')!
    expect(emitted.at(-1)).toEqual(['12.35'])
    expect(typeof emitted.at(-1)![0]).toBe('string')
  })

  test('粘贴千分位与货币符号时只保留数字和一个小数点', async () => {
    const wrapper = mount(MoneyInput, { props: { modelValue: '' } })
    const input = wrapper.get('input')
    await input.setValue('￥1,234.5.6')
    expect((input.element as HTMLInputElement).value).toBe('1234.56')
  })

  test('负号默认不允许，开启后保留在开头', async () => {
    const positive = mount(MoneyInput, { props: { modelValue: '' } })
    await positive.get('input').setValue('-100')
    expect((positive.get('input').element as HTMLInputElement).value).toBe('100')

    const negative = mount(MoneyInput, { props: { modelValue: '', allowNegative: true } })
    await negative.get('input').setValue('1-00')
    expect((negative.get('input').element as HTMLInputElement).value).toBe('-100')
  })

  test('清空后失焦回填 0.00，保持字符串', async () => {
    const wrapper = mount(MoneyInput, { props: { modelValue: '100.00' } })
    const input = wrapper.get('input')
    await input.setValue('')
    await input.trigger('blur')
    expect(wrapper.emitted('update:modelValue')!.at(-1)).toEqual(['0.00'])
  })
})
