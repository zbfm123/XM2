import axios from 'axios'

/**
 * axios 实例：全应用**唯一**的 HTTP 出口。
 *
 * 为什么要有这一层（而不是在每个组件里 `axios.get(...)`）：
 * 有三件事必须**每次都发生**，靠人记得就一定会漏：
 *   1. 请求自动带上 JWT
 *   2. 拿到 401 自动清登录态并跳登录页
 *   3. 错误信息统一转成"人能看懂的一句话"
 * 把这三件事放在拦截器里，组件就只管业务。
 */

const TOKEN_KEY = 'hospital_token'
const USER_KEY = 'hospital_user'

export const auth = {
  token() {
    return localStorage.getItem(TOKEN_KEY)
  },
  user() {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) {
      return null
    }
    try {
      return JSON.parse(raw)
    } catch {
      // ⚠️ 存储里是坏数据时要清理掉，否则每次刷新都会在这里抛异常，
      //    表现为"页面白屏"，而真正原因只是一条脏数据。
      localStorage.removeItem(USER_KEY)
      return null
    }
  },
  save(token, user) {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  },
  isLoggedIn() {
    return !!localStorage.getItem(TOKEN_KEY)
  }
}

const http = axios.create({
  // ⚠️ 用相对路径，不写死主机名：
  //    开发时由 Vite 的 proxy 转发，生产时由 Nginx 转发。
  //    写死 http://localhost:8081 会让"开发/生产"必须改代码。
  baseURL: '/api',
  timeout: 15000
})

// ---------------------------------------------------------------------
// 请求拦截：带上令牌
// ---------------------------------------------------------------------
http.interceptors.request.use((config) => {
  const token = auth.token()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ---------------------------------------------------------------------
// 响应拦截：统一错误处理
// ---------------------------------------------------------------------
http.interceptors.response.use(
  (response) => response.data,

  (error) => {
    const status = error.response?.status
    const body = error.response?.data

    // 后端所有错误都是 {code, message, path, time}，优先用它的 message
    const code = body?.code
    let message = body?.message || error.message || '请求失败'

    // 参数校验失败时，后端会在 fields 里一次性给出所有字段错误
    if (code === 'VALIDATION_FAILED' && body?.fields) {
      message = Object.values(body.fields).join('；')
    }

    if (status === 401) {
      // 令牌过期/无效/被篡改 —— 清掉本地登录态并回登录页。
      //
      // ⚠️ 这里不弹全局提示：401 是**预期内**的生命周期事件
      //    （令牌就是会过期），弹提示只会在用户正常操作时突然跳出一个红条。
      //    跳回登录页本身已经足够说明问题。
      auth.clear()
      if (window.location.hash !== '#/login') {
        window.location.hash = '#/login'
      }
    }

    // 把"人能看懂的 message"挂在 error 上，组件里 catch 后直接用它
    error.friendlyMessage = message
    error.errorCode = code
    return Promise.reject(error)
  }
)

// ---------------------------------------------------------------------
// 业务接口
//
// 刻意**不**在组件里裸写 http.get('/departments')：
// 把路径与参数集中在这里，接口一改只需要动一处，
// 而且组件里看不出"这个 URL 长什么样"，也就不会有人绕过这层去拼路径。
// ---------------------------------------------------------------------
export const api = {
  // 认证
  register(payload) {
    return http.post('/auth/register', payload)
  },
  login(phone, password) {
    return http.post('/auth/login', { phone, password })
  },
  me() {
    return http.get('/auth/me')
  },

  // 科室 / 医生 / 排班（只读）
  departments() {
    return http.get('/departments')
  },
  doctors(deptId) {
    return http.get('/doctors', { params: { deptId } })
  },
  schedules(doctorId, params = {}) {
    return http.get('/schedules', { params: { doctorId, ...params } })
  },

  // 挂号
  book(scheduleId, idempotencyKey) {
    return http.post('/appointments', { scheduleId, idempotencyKey })
  },
  cancel(appointmentNo, reason) {
    return http.post(`/appointments/${appointmentNo}/cancel`, { reason })
  },

  /**
   * 模拟支付（决策 D-07：不接真实支付）。
   *
   * ⚠️ 真实系统里这个动作由**支付平台的回调**触发，前端不会直接调它。
   *    这里做成显式按钮，是为了让状态机在界面上也能走完
   *    （否则 PAID 与 COMPLETED 只能靠改数据库才能到，演示时走不通）。
   */
  pay(appointmentNo) {
    return http.post(`/appointments/${appointmentNo}/pay`, { note: '模拟支付回调' })
  },

  /** 标记已就诊完成（真实系统里通常由 HIS 回调或定时任务推进）。 */
  complete(appointmentNo) {
    return http.post(`/appointments/${appointmentNo}/complete`, { note: '就诊完成' })
  },
  myAppointments(params = {}) {
    return http.get('/appointments', { params })
  }
}

/**
 * 生成幂等键。
 *
 * ⚠️ 为什么放在前端生成：后端要求"同一个键代表同一件事"，
 * 而"这两次点击是不是同一件事"只有客户端才知道
 * （用户双击、网络重试、前端自动重试）。
 *
 * 用法约定：**进入挂号页时生成一次，直到这次提交有确定结果之前不换**。
 * 每次点击都新生成一个键，等于没有幂等。
 */
export function newIdempotencyKey() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID()
  }
  // 兜底：老浏览器没有 randomUUID
  return 'key-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

export default http
