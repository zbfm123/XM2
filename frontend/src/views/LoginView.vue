<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, auth } from '../api'

/**
 * 登录 / 注册页（T-014）。
 *
 * 一个页面承载两个动作，用 el-tabs 切换。
 * 这样"没账号的人"不必去找另一个入口——演示时少一次跳转。
 */

const router = useRouter()
const route = useRoute()

const activeTab = ref('login')
const loading = ref(false)

const loginForm = reactive({ phone: '', password: '' })
const registerForm = reactive({ phone: '', password: '', realName: '' })

async function doLogin() {
  if (!loginForm.phone || !loginForm.password) {
    ElMessage.warning('请填写手机号与口令')
    return
  }
  loading.value = true
  try {
    const res = await api.login(loginForm.phone, loginForm.password)
    // 后端返回 {token, tokenType, expiresIn, expiresAt, user}
    auth.save(res.token, res.user)
    ElMessage.success('登录成功')

    // 回到"原本想去的页面"（如果没有就回科室列表）
    const redirect = route.query.redirect
    router.push(redirect || { name: 'departments' })
  } catch (e) {
    // 后端已经给了可读的中文提示（如"手机号或口令错误"、"账号已锁定"），
    // 直接用，不要再自己编一句模糊的"登录失败"。
    ElMessage.error(e.friendlyMessage || '登录失败')
  } finally {
    loading.value = false
  }
}

async function doRegister() {
  if (!registerForm.phone || !registerForm.password) {
    ElMessage.warning('请填写手机号与口令')
    return
  }
  loading.value = true
  try {
    await api.register({
      phone: registerForm.phone,
      password: registerForm.password,
      realName: registerForm.realName || undefined
    })
    // ⚠️ 注册成功后**不自动登录**：后端刻意不返回令牌（注册 ≠ 登录），
    //    这里把手机号带到登录框，让用户少输一次，但不替他做决定。
    ElMessage.success('注册成功，请登录')
    loginForm.phone = registerForm.phone
    loginForm.password = ''
    activeTab.value = 'login'
  } catch (e) {
    ElMessage.error(e.friendlyMessage || '注册失败')
  } finally {
    loading.value = false
  }
}

/** 一键填入演示账号：演示时不用手输，也避免记错口令。 */
function fillDemo() {
  activeTab.value = 'login'
  loginForm.phone = '13800000001'
  loginForm.password = 'Demo@2026'
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1 class="brand-title">医院预约挂号系统</h1>
      <p class="muted">秋招项目 2 · 前后端分离演示</p>

      <el-tabs v-model="activeTab" class="tabs">
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent="doLogin">
            <el-form-item label="手机号">
              <el-input
                v-model="loginForm.phone"
                placeholder="11 位手机号"
                maxlength="11"
                @keyup.enter="doLogin"
              />
            </el-form-item>
            <el-form-item label="口令">
              <el-input
                v-model="loginForm.password"
                type="password"
                show-password
                placeholder="口令"
                @keyup.enter="doLogin"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="submit"
              :loading="loading"
              @click="doLogin"
            >
              登录
            </el-button>
          </el-form>

          <div class="demo-hint">
            <span class="muted">演示账号：13800000001 / Demo@2026</span>
            <el-button link type="primary" size="small" @click="fillDemo">
              一键填入
            </el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form label-position="top" @submit.prevent="doRegister">
            <el-form-item label="手机号">
              <el-input
                v-model="registerForm.phone"
                placeholder="11 位手机号"
                maxlength="11"
              />
            </el-form-item>
            <el-form-item label="口令">
              <el-input
                v-model="registerForm.password"
                type="password"
                show-password
                placeholder="至少 8 位"
              />
            </el-form-item>
            <el-form-item label="姓名（可选）">
              <el-input v-model="registerForm.realName" placeholder="不填则默认显示'患者'" />
            </el-form-item>
            <el-button
              type="primary"
              class="submit"
              :loading="loading"
              @click="doRegister"
            >
              注册
            </el-button>
          </el-form>
          <p class="muted tip">
            口令由后端用 BCrypt 加盐存储，接口不会回显任何哈希。
          </p>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(135deg, #eef2ff 0%, #f5f7fa 100%);
    padding: 1.5rem;
}

.login-card {
    width: 100%;
    max-width: 400px;
    background: #fff;
    border-radius: 10px;
    padding: 2rem;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
}

.brand-title {
    font-size: 1.3rem;
    margin: 0 0 0.25rem;
    color: #4338ca;
}

.tabs {
    margin-top: 1.25rem;
}

.submit {
    width: 100%;
}

.demo-hint {
    margin-top: 1rem;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    background: #f9fafb;
    border-radius: 6px;
    padding: 0.5rem 0.75rem;
}

.tip {
    margin-top: 1rem;
}
</style>
