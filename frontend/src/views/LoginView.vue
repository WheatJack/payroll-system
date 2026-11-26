<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { isApiError } from '@/api/client'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const errorMessage = ref('')
const submitting = ref(false)

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function submit() {
  errorMessage.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await auth.login(form.username, form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = isApiError(error) ? error.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-title">工资核算系统</div>
        <div class="brand-sub">Payroll M1</div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" size="large" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            size="large"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon class="login-error" />

        <el-button type="primary" size="large" class="login-submit" :loading="submitting" @click="submit">
          登 录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef4ff 0%, #f7f9fc 45%, #eaf3f0 100%);
}
.login-card {
  width: 380px;
  padding: 36px 32px 30px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 18px 48px rgba(24, 43, 79, 0.12);
}
.brand {
  margin-bottom: 26px;
}
.brand-title {
  font-size: 22px;
  font-weight: 600;
  color: #1f2d3d;
  letter-spacing: 1px;
}
.brand-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  letter-spacing: 0.5px;
}
.login-error {
  margin-bottom: 16px;
}
.login-submit {
  width: 100%;
}
</style>
