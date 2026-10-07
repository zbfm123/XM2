# 新技术学习清单

> **这份文件的存在理由**：你对 RabbitMQ、Vue 3、Nginx 都是"看过但没独立写过"。
> 3 天里不能指望边学边摸索，所以**每样只学"够用"的最小集**，
> 并且明确"什么暂时不用学"。

**学习原则**：**先让最小的东西跑起来，再逐步加。** 卡在配置上超过 30 分钟就换方案。

---

## 一、Nginx（风险最高，先装先验证）

> ✅ **已完成（2026-10-05）**：Nginx 早已存在于 `D:\nginx`（本机有 1.18.0 / 1.20.2 / 1.22.0），
> 配置写在 `deploy/nginx/`（`nginx.conf` 用 80、`nginx.8080.conf` 用 8080），
> 并用 `scripts/verify-e2e.ps1` 验证过 18 项。
> ⚠️ **本机 80 端口被 `Steam++.Accelerator` 占用**，所以默认走 8080。
> 下面这一节保留作原理参考（`try_files` 为什么必须写等），不必再照着装一遍。

### 只需理解 3 件事

| 概念 | 一句话 | 本项目怎么用 |
| --- | --- | --- |
| **静态资源服务** | 把某个目录当网站根目录 | `location /` → Vue 构建产物 `dist/` |
| **反向代理** | 把 `/api` 转发给后端 | `location /api/` → `proxy_pass http://127.0.0.1:8081` |
| **配置文件结构** | `nginx.conf` 里的 `server { location { } }` | 一个 server 块搞定 |

**不需要学**：负载均衡、HTTPS 证书、rewrite 规则、多站点、upstream 健康检查。

### 验证步骤（15 分钟，**先做这一步**）

```powershell
# Nginx 是解压即用，不是安装程序
# 下载 Windows 版 zip → 解压到 D:\nginx

cd D:\nginx
.\nginx.exe -v          # 显示版本 = 能跑
.\nginx.exe             # 启动（无输出即成功）
# 浏览器打开 http://localhost → 看到 "Welcome to nginx!" = 通了
.\nginx.exe -s stop     # 停止
```

**这一步通了，后面只是改配置。** 不通就走降级路线（见 00-project-index）。

### 本项目的配置（我会给你，照抄即可）

```nginx
server {
    listen 80;
    server_name localhost;

    # 前端静态资源
    location / {
        root   D:/xmdeepseek/hospital-appointment/frontend/dist;
        index  index.html;
        try_files $uri $uri/ /index.html;   # 关键：Vue history 路由必需
    }

    # 后端接口
    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

> `try_files ... /index.html` 这行是**必须的**：Vue 的 history 路由下，
> 刷新 `/appointment` 这种路径时 Nginx 会去找真实文件而 404，
> 交给 `index.html` 让前端路由接管。

---

## 二、RabbitMQ（只需 3 个概念）

### 只需理解 3 件事

| 概念 | 一句话 | 本项目怎么用 |
| --- | --- | --- |
| **生产者 / 消费者** | 一个发消息，一个收消息 | 挂号成功 → 发"已创建"消息 → 消费者写通知 |
| **队列** | 消息排队的容器 | 一个通知队列 |
| **死信交换机（DLX）** | 消息过期后转到另一个交换机 | **延迟 15 分钟自动取消** |

**不需要学**：集群、镜像队列、消息确认高级用法、事务消息、优先级队列。

### 关键认知：RabbitMQ 没有"延迟队列"

它只有 **TTL（消息存活时间）+ 死信交换机**，组合起来能实现延迟效果：

```
发送到 appointment.delay.queue（设 TTL = 15 分钟，且没有消费者）
        │
        │ 15 分钟后消息过期
        ▼
   转到 appointment.dlx（死信交换机）
        │
        ▼
   进入 appointment.cancel.queue
        │
        ▼
   消费者收到 → 检查订单是否仍未支付 → 是则取消并归还号源
```

**这就是"延迟队列"的标准实现**，也是面试常问的点。**要能画出来。**

### Spring Boot 里怎么用（最小写法）

```java
// 1. 配置队列与死信关系（一个 @Configuration 类）
// 2. 发送：rabbitTemplate.convertAndSend(exchange, routingKey, payload)
// 3. 消费：@RabbitListener(queues = "appointment.cancel.queue")
```

> **本项目的重点不是 MQ 用法，而是"MQ 挂了不能拖垮挂号"**（决策 D-06）。
> 发送那一步包在 try/catch 里，失败只记日志。

### 验证步骤

装好后打开管理台 <http://localhost:15672>（默认 `guest` / `guest`），
能看到队列列表 = 通了。

---

## 三、Vue 3（只看过教程，所以只用最基础的）

### 只需理解 4 件事

| 概念 | 一句话 | 本项目怎么用 |
| --- | --- | --- |
| **单文件组件 `.vue`** | 一个文件里写 `<template> / <script setup> / <style>` | 每个页面一个文件 |
| **`ref` / `reactive`** | 响应式数据 | 存列表、表单 |
| **`vue-router`** | 路由 | 5 个页面之间的跳转 |
| **`axios`** | 发 HTTP 请求 | 调后端接口 |

**明确不用**：Pinia（状态管理，5 个页面不需要）、TypeScript、
自定义指令、`<Suspense>`、组合式函数封装、组件库二次封装。

### 页面上只需要会 3 种写法

```vue
<script setup>
import { ref, onMounted } from 'vue'
import axios from 'axios'

const list = ref([])            // ① 响应式数据

onMounted(async () => {         // ② 加载时请求
  const { data } = await axios.get('/api/departments')
  list.value = data
})
</script>

<template>
  <div v-for="d in list" :key="d.id">{{ d.name }}</div>   <!-- ③ 列表渲染 -->
</template>
```

**Element Plus 只用一个组件起步**：`el-table`。其余用原生 HTML。

### 只有 5 个页面

| 路由 | 页面 |
| --- | --- |
| `/login` | 登录 / 注册 |
| `/departments` | 科室列表 |
| `/doctors/:deptId` | 该科室的医生列表 |
| `/schedule/:doctorId` | 医生排班 + 挂号按钮 |
| `/my` | 我的挂号（含取消） |

> **5 个页面是硬上限。** 加页面就是加时间，而面试看的是"有没有闭环"，
> 不是"页面多不多"。

---

## 四、Redisson（可能不需要）

**先不学。** 因为防超卖用的是**原子 UPDATE**（决策 D-03），不需要分布式锁。

**但要知道"它是什么、为什么我没用"**——面试很可能问：

> "分布式锁用于跨进程互斥。我考虑过用 Redisson 的锁来保护号源扣减，
> 但**单库的原子 UPDATE 已经能保证正确性**，引入 Redisson 只是多一个组件、
> 多一个故障点。**我更愿意把复杂度花在真的需要的地方。**

**这个回答比"我用了 Redisson"更有说服力。**

---

## 五、学习时间预算

| 项 | 预算 | 超时怎么办 |
| --- | --- | --- |
| Nginx 装 + 起欢迎页 | 15 分钟 | 超 30 分钟 → 走降级路线，先不碰 |
| RabbitMQ 装 + 管理台可访问 | 30 分钟 | 超 1 小时 → 通知改同步 + `@Scheduled` 扫描 |
| RabbitMQ TTL + DLX 跑通 | 1 小时 | 同上 |
| Vue 3 项目起 + 一个页面调通接口 | 1 小时 | 超 2 小时 → 页面数量减到 3 个 |
| axios 拦截器 + 路由守卫 | 30 分钟 | 可选，先不做 |

**总学习预算 ≈ 3.5 小时**，占 3 天里的大约半天。**这是必须付的成本。**

---

## 六、给"没写过"的人的三个提醒

1. **配置类问题不要自己猜**。报错信息原样贴出来（或告诉我），
   比反复试快得多。
2. **每一步都验证"它真的跑起来了"**，不要写完一堆再一起测。
   （Nginx 先看欢迎页、RabbitMQ 先看管理台、Vue 先看默认页）
3. **卡住超过 30 分钟就换方案**。3 天里，"换个简单做法"远好过"死磕配置"。

---

## 七、事后回看：这份清单准不准（2026-10-07 补）

项目做完了，回头对一遍当初的判断——**这比清单本身更有用**，
因为它记录的是一次真实的「预判 vs 实际」。

### 判断对的

| 当初的判断 | 实际情况 |
| --- | --- |
| **Redisson 先不学**：防超卖用原子 UPDATE，不需要分布式锁 | ✅ 完全正确，全程没用锁 |
| **Vue 明确不用 Pinia / TypeScript** | ✅ 5 个页面确实用不上，省下的时间花在了别处 |
| **RabbitMQ 只需 3 个概念**，不用学集群 / 事务消息 | ✅ 够用；真正花时间的是 TTL + DLX 的**队列参数固化**这个坑，不在清单里 |
| **Nginx 风险最高，先装先验证** | ✅ 方向对；实际卡点是 **80 端口被占**，不是 Nginx 本身 |
| **卡住超过 30 分钟就换方案** | ✅ 有效；本项目的做法是「换方案 + 把取舍写进文档」 |

### 没预料到的（这才是重点）

| 实际花掉不少时间的事 | 为什么清单里没有 |
| --- | --- |
| **Redis 缓存：Cache-Aside、键设计、失效时机、故障降级** | 清单判断「不需要 Redis」——**对锁没用是对的，但漏了缓存**。缓存真正难的不是 API，是**键怎么设计、什么时候失效、挂了怎么办** |
| **缓存键的命名空间** | 两个功能（extract / review）共用一个键会互相覆盖。这类问题只有真接了缓存才会遇到 |
| **缓存从来没生效这类静默问题** | 组件的 put/get 有测试、配置也开着，但**没有任何生产代码调用它们** |
| **测试里假绿** | 消费者没接上测试队列，测试照样绿。这与「缓存没生效」是同一个主题 |

> **一句话总结**：
> 这份清单对「**不需要学什么**」的判断基本准确，对「**需要学什么**」漏了缓存。
> 原因是当时按「技术点清单」来列的（Nginx / MQ / Vue / 锁），
> 而实际难点出在**「接上之后」**——缓存接上了怎么失效、消费者接上了怎么保证真的在消费。
>
> **「把组件引进来」和「让它真正生效」是两件事**，而后者才是花时间的地方。
