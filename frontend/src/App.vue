<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, api } from './api'

/**
 * 应用外壳：顶部导航 + 路由出口。
 *
 * 这里只做两件事：导航、以及显示"当前登录的是谁"。
 * 所有业务界面都在各自的路由组件里。
 */

const route = useRoute()
const router = useRouter()

// 用 ref 存用户信息，登录/登出后要能立刻反映到界面上
const currentUser = ref(auth.user())

const isLoginPage = computed(() => route.name === 'login')
const isLoggedIn = computed(() => !!currentUser.value)

function logout() {
  // ⚠️ 本项目的"登出"就是前端删掉令牌。
  //    后端没有登出接口，因为 JWT 是无状态的：签发之后服务端不再持有它，
  //    "让令牌立刻失效"需要额外的黑名单（Redis），而本期 F-01 没要求登出。
  //    这是主动的范围决策，不是遗漏 —— 见 docs/PROGRESS.md 的范围决策记录。
  auth.clear()
  currentUser.value = null
  router.push({ name: 'login' })
}

/**
 * 刷新页面后，用 /api/auth/me 确认令牌是否还有效。
 *
 * ⚠️ 为什么不能只看 localStorage 里有没有 token：
 *    令牌可能已过期、已被篡改、或者账号已被停用。
 *    后端每次都会回库确认（见 AuthService.currentUser），
 *    所以这一次调用是"当前登录态到底还算不算数"的唯一可信答案。
 *
 * 失败时不需要自己处理：axios 拦截器会清登录态并跳登录页。
 */
onMounted(async () => {
  if (!auth.isLoggedIn()) {
    return
  }
  try {
    const user = await api.me()
    currentUser.value = user
    auth.save(auth.token(), user)
  } catch {
    currentUser.value = null
  }
})
</script>

<template>
  <div>
    <!-- 登录页不显示导航：那时还没有身份，显示一个空导航栏没有意义 -->
    <header v-if="!isLoginPage" class="topbar">
      <div class="topbar-inner">
        <div class="brand" @click="router.push({ name: 'departments' })">
          医院预约挂号系统
        </div>

        <nav class="nav">
          <router-link :to="{ name: 'departments' }">科室</router-link>
          <router-link :to="{ name: 'my' }">我的挂号</router-link>
        </nav>

        <div class="user-box">
          <template v-if="isLoggedIn">
            <span class="muted">{{ currentUser?.realName || currentUser?.phone }}</span>
            <el-button size="small" @click="logout">退出</el-button>
          </template>
          <template v-else>
            <el-button size="small" type="primary" @click="router.push({ name: 'login' })">
              登录
            </el-button>
          </template>
        </div>
      </div>
    </header>

    <router-view />
  </div>
</template>

<style scoped>
.topbar {
    background: #fff;
    border-bottom: 1px solid #e5e7eb;
    margin-bottom: 1.5rem;
}

.topbar-inner {
    max-width: 1000px;
    margin: 0 auto;
    padding: 0.75rem 1.5rem;
    display: flex;
    align-items: center;
    gap: 1.5rem;
}

.brand {
    font-weight: 600;
    font-size: 1.05rem;
    cursor: pointer;
    color: #4338ca;
    white-space: nowrap;
}

.nav {
    display: flex;
    gap: 1rem;
    flex: 1;
}

.nav a {
    text-decoration: none;
    color: #4b5563;
    font-size: 0.95rem;
}

/* router-link 自动加上的激活类名：让当前位置一眼可见 */
.nav a.router-link-active {
    color: #4338ca;
    font-weight: 600;
}

.user-box {
    display: flex;
    align-items: center;
    gap: 0.6rem;
}
</style>
