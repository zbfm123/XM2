import { createRouter, createWebHashHistory } from 'vue-router'
import { auth } from '../api'

/**
 * 路由表。
 *
 * ⚠️ 用 **hash 模式**（`createWebHashHistory`）而不是 history 模式：
 * history 模式需要服务端把"所有未知路径"回退到 index.html，
 * 否则用户在 /appointments 按刷新就是 404。
 *
 * Nginx 那边确实已经写了 `try_files $uri $uri/ /index.html`（所以两种模式都能跑），
 * 但 hash 模式让**前端在任何环境下都不会因为刷新而 404**——
 * 包括直接用 `file://` 打开、或者用 Vite 的 preview，
 * 少一个"必须先配对服务端"的前置条件。
 *
 * 代价是 URL 里有个 `#`。对这个项目，这个取舍是划算的：
 * 演示时最怕的就是"刷新一下页面就 404"。
 */

const routes = [
  { path: '/', redirect: '/departments' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录 / 注册' }
  },
  {
    path: '/departments',
    name: 'departments',
    component: () => import('../views/DepartmentsView.vue'),
    meta: { title: '选择科室' }
  },
  {
    path: '/doctors/:deptId',
    name: 'doctors',
    component: () => import('../views/DoctorsView.vue'),
    meta: { title: '选择医生' }
  },
  {
    path: '/schedules/:doctorId',
    name: 'schedules',
    component: () => import('../views/SchedulesView.vue'),
    meta: { title: '选择号源' }
  },
  {
    path: '/my',
    name: 'my',
    component: () => import('../views/MyAppointmentsView.vue'),
    meta: { title: '我的挂号' }
  },
  // 兜底：未知路径回首页（而不是留一个空白页）
  { path: '/:pathMatch(.*)*', redirect: '/departments' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

/**
 * 路由守卫：未登录访问受保护页面 → 跳登录页。
 *
 * 说明它与后端鉴权的关系（重要，别误解）：
 * **它只是体验优化，不是安全边界。**
 * 真正拦住越权的是后端 —— `/api/**` 默认拒绝，没有合法令牌一律 401。
 * 前端守卫可以被绕过（改 localStorage 就行），所以它挡不住任何恶意用户；
 * 它挡的是"用户随手输了个地址，然后看到一个 401 报错页"这种困惑。
 *
 * ⚠️ 演示时如果想验证"后端确实在拦"，把 localStorage 里的 token 留着但改坏它，
 *    页面依然会被 401 踢回登录页 —— 那才是真正的防线。
 */
router.beforeEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} · 医院预约挂号系统`
    : '医院预约挂号系统'

  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn()) {
    // 记住原本要去哪，登录后跳回去 —— 否则用户登录完总是回到首页，还得再点一遍
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
