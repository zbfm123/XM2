# 医院预约挂号系统

> 秋招第二个项目。与已有的**合同智能审查平台**互补：
> 前者展示**工程判断力**（AI 工程化、证据对齐、只追加审计），
> 本项目展示**主流企业开发能力**（前后端分离、RabbitMQ 异步、Nginx 部署、并发防超卖）。

---

## 一分钟状态

| 项 | 值 |
| --- | --- |
| 位置 | `D:\xmdeepseek\hospital-appointment` |
| 期限 | **2026-10-07 前**（3 天） |
| 技术栈 | Spring Boot 3.3.5 / Java 21 / MyBatis-Plus / MySQL 8 / Redis / **RabbitMQ 4.1.8** / **Vue 3**（未开始）/ **Nginx 1.22.0**（未配置） |
| 核心亮点 | **防止号源超卖**（并发扣减） |
| 部署方式 | 前后端分离，Nginx 托管前端 + 反向代理后端 |
| **当前进度** | **T-001 ~ T-017 已完成**，**108 个测试全绿**；验收 **A-01 ~ A-10 全部通过** |
| **下一步** | T-019 文档定稿（面试问答已写好）；演示前注意 **80 端口被 Steam++ 占用** |

> ⚠️ 详细的实时状态只看 [START-HERE.md](START-HERE.md) 与 [PROGRESS.md](PROGRESS.md)，
> 本节的"当前进度"是快照。

---

## 文档地图

| 想知道什么 | 看哪份 |
| --- | --- |
| **从这里开始** | 本文件 |
| 做什么、不做什么、为什么 | [01-requirements-and-scope.md](01-requirements-and-scope.md) |
| 怎么设计、每个决策的理由 | [02-architecture.md](02-architecture.md) |
| 任务拆分与验收标准 | [04-tasks-and-acceptance.md](04-tasks-and-acceptance.md) |
| **新技术学习清单** | [05-learning-plan.md](05-learning-plan.md) |
| 进度与踩坑 | [PROGRESS.md](PROGRESS.md) |
| **面试问答（带着这个去面试）** | [06-interview-qa.md](06-interview-qa.md) |
| **并发验证结果（T-018）** | [07-concurrency-results.md](07-concurrency-results.md) |

---

## 与项目 1 的分工（简历上不要重复）

| | 项目 1：合同智能审查 | 项目 2：医院预约挂号 |
| --- | --- | --- |
| 定位 | 差异化亮点 | 主流能力证明 |
| 关键能力 | AI 工程化、证据对齐、只追加审计 | **并发控制**、异步消息、前后端分离、部署 |
| 数据库 | 6 张表 | 6 张表 |
| 前端 | 零构建原生 HTML | **Vue 3 + Element Plus + axios** |
| 部署 | Spring Boot 直接托管 | **Nginx 反向代理** |
| 测试 | 280 个 | **108 个**（后端；前端为浏览器闭环验证，见 A-10 与 `scripts/verify-e2e.ps1`） |
| 面试怎么讲 | "我会给不确定的东西加约束" | "我能独立完成一个业务系统并部署上线" |

> **两个项目技术栈不重叠、业务不重叠**，这是刻意的：
> 面试官看两个项目时，想知道的是**广度**，不是"同一个技术做了两遍"。

---

## 核心亮点：防止号源超卖

这是本项目**唯一需要深挖的技术点**，对齐项目 1 的"证据对齐"。

**问题**：一个专家号只有 20 个号源，100 个人同时抢。

**朴素的错误写法**：

```java
// ❌ 经典的超卖
var schedule = scheduleMapper.findById(id);      // 读到 remaining = 1
if (schedule.getRemaining() > 0) {               // 两个线程都通过了
    scheduleMapper.decrement(id);                // 两个都减了 → remaining = -1
}
```

**本项目怎么做**（方案与取舍见 [02-architecture.md](02-architecture.md)）：

```sql
-- 把"判断"和"扣减"合并成一条原子 SQL，交给数据库保证
UPDATE schedule
   SET remaining = remaining - 1
 WHERE id = ? AND remaining > 0;
-- 影响行数 = 0 说明没抢到，直接失败，不做任何补偿
```

**并用并发测试证明它不超卖**（1000 线程抢 20 个号，断言最终 `remaining = 0` 且订单恰好 20 条）。

> 面试时这样讲：**"我没有先查再改，因为那中间有窗口。我把判断和扣减压成一条 SQL，
> 让受影响行数来回答'我到底抢到没有'。"**

---

## 环境事实（本机实测，踩过的坑）

| 项 | 状态 |
| --- | --- |
| JDK 21 | ✅ `D:\java\jdk-21`（⚠️ PATH 上的 `java` 是 1.8，必须设 `JAVA_HOME`） |
| Maven | ✅ 3.9.9 |
| MySQL 8 | ✅ Windows 服务 `MySQL80`，`localhost:3306`，`root` / `123456` |
| Redis | ✅ Windows 服务 `Redis`，`127.0.0.1:6379`，口令 `123456` |
| Node.js | ✅ v24.18.0 |
| **npm** | ⚠️ **必须用 `npm.cmd`** —— `npm.ps1` 被执行策略拦住 |
| **Erlang** | ✅ `D:\erl-26.2.5.21`（OTP 26.2.5.21，zip 解压，不写 C 盘） |
| **RabbitMQ** | ✅ `D:\rabbitmq`（4.1.8），管理台 <http://localhost:15672> 可用<br>⚠️ **服务化待执行**（需管理员权限，脚本见 `scripts/`） |
| **Nginx** | ✅ 已存在于 `D:\nginx`（用 1.22.0）<br>⚠️ **80 端口被 `Steam++.Accelerator` 占用**（T-017 的硬阻塞） |
| 端口占用 | 8080/8081/5173/5672/15672 **空闲**；**80 被占**；3306/6379 为服务 |

---

## 三条纪律（沿用项目 1，不重新发明）

1. **规格不存在，不动工。** 新功能先写进 01 与 04。
2. **错误分支必须有明确错误码**，禁止宽泛 `catch` 吞掉根因，禁止用默认值兜底。
3. **小步提交**：每完成一个任务即跑全量测试 → 更新文档 → 推送。

---

## 风险与降级路线（提前写，不是事后找借口）

| 风险 | 降级方案 |
| --- | --- |
| **Nginx 配不通** | 前端用 `vite preview` 起，或 Spring Boot 托管 `dist`；文档如实写部署方式 |
| **RabbitMQ 装不上** | 通知改为同步写库 + `@Scheduled` 扫描延迟任务；**架构不变，实现替换** |
| 时间不够 | 按 [04](04-tasks-and-acceptance.md) 的"砍法"顺序砍，**管理端最先砍** |

> ⚠️ **绝不做的降级**：防超卖的并发测试不能省。
> 那是本项目的技术内核，省了就等于没做。
