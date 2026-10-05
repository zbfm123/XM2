# 会话开始请先读这里

> **这份文件是给"新会话里第一次开口前的你"和"第一次接手的 AI"看的。**
> 项目 1（合同智能审查平台）用的是同一套做法，见
> `..\contract-review-platform\docs\RESTART-HERE.md`。

---

## 给 AI 的第一条指令（把这段话贴给新会话）

```
我们在做第二个秋招项目「医院预约挂号系统」，位于
D:\xmdeepseek\hospital-appointment。

请先读这几个文件把状态捡回来：
  1. docs\START-HERE.md        （本文件，一分钟回到状态）
  2. docs\PROGRESS.md          （进度与踩坑）
  3. docs\04-tasks-and-acceptance.md （任务与验收标准）

工作区是 D:\xmdeepseek，但它下面有两个项目，**这个会话只做 hospital-appointment**，
另一个 contract-review-platform 是已经完成的项目 1，不要动它。
```

> **为什么要贴这段话**：新会话读不到之前的对话。工作区 `D:\xmdeepseek`
> 下面有两个项目，不指明的话可能被做错。

---

## 一分钟状态

| 项 | 值 |
| --- | --- |
| 项目 | 医院预约挂号系统（秋招项目 2） |
| 位置 | `D:\xmdeepseek\hospital-appointment` |
| 到期 | **2026-10-07** |
| 后端端口 | **8081**（项目 1 用 8080，两个可以同时跑） |
| 数据库 | **`hospital_appointment`**（独立库，不碰项目 1 的 `contract_review`） |
| 测试 | 尚未开始（骨架阶段） |
| 当前任务 | **T-003 用户注册登录**（T-001 骨架、T-002 数据模型已完成） |

---

## 已完成

### T-001 项目骨架 ✅

- `pom.xml`：Spring Boot 3.3.5 / Java 21 / MyBatis-Plus 3.5.7 / MySQL / Redis / JWT / H2(test)
- ⚠️ **刻意先不加 `spring-boot-starter-amqp`**：本机 RabbitMQ 未安装，
  加了会产生自动配置噪音。这也正好是需求 A-07（MQ 不可用不影响主流程）——**T-010 再加**
- `.mvn/jvm.config`：指到 `D:\java\jdk-21`（PATH 上的 `java` 是 1.8）
- `application.yml` / `application-dev.yml`：凭据走环境变量，端口 8081
- `SecurityConfig`：**T-001 阶段是"全放行"的临时版本**，代码里有 `TODO(T-003)` 标注
- 启动验证：**Tomcat started on port 8081** ✅

### T-002 数据模型 ✅

6 张表，全部 `CREATE TABLE IF NOT EXISTS`：

| 表 | 说明 |
| --- | --- |
| `sys_user` | 用户（患者） |
| `department` | 科室 |
| `doctor` | 医生 |
| **`schedule`** | **排班（号源）—— 防超卖发生在这里** |
| **`appointment`** | **挂号订单 —— 状态机在这里** |
| `notification` | 通知记录（异步消费的产物） |

预置数据实测：**5 科室 / 10 医生 / 140 条排班 / 1 个演示账号**

演示账号：**手机号 `13800000001`，口令 `Demo@2026`**

> 口令由 `DemoDataInitializer` 在启动时用 `PasswordEncoder` **现算**，
> 不硬编码 BCrypt 哈希。理由见那个类的注释——项目 1 硬编码哈希的代价是
> 哈希成了"无法验证的魔法字符串"，一旦对不上就是"登录不上"。

---

## 下一步：T-003 用户注册登录

**这是新会话该做的第一件事。** 详细验收标准见
[04-tasks-and-acceptance.md](04-tasks-and-acceptance.md) 的 T-003。

要点：
1. 注册（手机号 + 口令，BCrypt）
2. 登录返回 JWT
3. `GET /api/auth/me`
4. **把 `SecurityConfig` 的"全放行"换成真实规则**（那里有 TODO 标注）
5. 连续失败锁定（可参考项目 1 的 `LoginAttemptGuard`，**建议直接复用其思路**）

> 项目 1 的 JWT 实现可以直接借鉴：
> `..\contract-review-platform\src\main\java\com\demo\contract\security\`

---

## 环境事实（本机实测，别重复踩）

| 项 | 值 |
| --- | --- |
| JDK 21 | `D:\java\jdk-21`；⚠️ PATH 上的 `java` 是 **1.8**，必须设 `JAVA_HOME` |
| MySQL | Windows 服务 `MySQL80`，`root` / `123456` |
| Redis | Windows 服务 `Redis`，`127.0.0.1:6379`，口令 `123456` |
| **npm** | ⚠️ **必须用 `npm.cmd`**，`npm` 被执行策略拦住 |
| **RabbitMQ** | ❌ 待安装（用户负责） |
| **Nginx** | ❌ 待安装（用户负责，解压即用） |
| 启动命令 | 见下面 |

### 启动后端

```powershell
cd D:\xmdeepseek\hospital-appointment
$env:JAVA_HOME = "D:\java\jdk-21"
$env:DB_PASSWORD = "123456"
$env:REDIS_PASSWORD = "123456"
$env:JWT_SECRET = "dev-only-secret-must-be-at-least-32-bytes-long"
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

> 将来会做一个 `run-dev.ps1` 把这几行收起来（参考项目 1 的脚本，
> ⚠️ 那个脚本**必须带 UTF-8 BOM**，否则 PowerShell 5.1 按 GBK 解码会报语法错）。

---

## 这个项目要证明什么（面试就讲这三个）

| # | 亮点 | 对应验收 |
| --- | --- | --- |
| ① | **防止号源超卖** —— 原子 UPDATE，不用分布式锁 | **A-03** |
| ② | **MQ 挂了挂号仍然成功** —— 外部依赖失败不否定已完成的业务动作 | **A-07** |
| ③ | 状态机穷举测试 —— 工程纪律 | **A-08** |

**这三项绝不砍。** 砍了项目就变成"又一个 CRUD"。

> 与项目 1 的关系：项目 1 说"我不相信模型的输出"，
> 本项目说"我不相信并发下的判断"——**都是"不相信，但用工程手段兜住"。**

---

## 已踩的坑（新会话不要重复）

| 坑 | 症状 | 处理 |
| --- | --- | --- |
| `INSERT ... SELECT ... JOIN(派生表) ... ON DUPLICATE KEY UPDATE` | MySQL 报 **1064 语法错误** | `ON` 歧义。改用**纯 SQL + `INSERT IGNORE`** |
| 用存储过程 `DELIMITER $$` | Spring 脚本执行器不认 | `DELIMITER` 是 **MySQL 客户端命令**，不是 SQL |
| 排班用 `ON DUPLICATE KEY UPDATE` 覆盖 | **每次重启会把已消耗号源重置回满** | 必须用 `INSERT IGNORE`——只补缺失，不碰业务状态 |
| 仓库目录 | 两个项目同在工作区 | 工作区是 `D:\xmdeepseek`，项目各自独立 git 仓库 |

---

## 两个项目的关系（同一工作区下的两个独立会话）

```
D:\xmdeepseek\                        ← DSH 工作区（开发环境）
├── contract-review-platform\         ← 项目 1（已完成，280 测试）→ github.com/zbfm123/xm
│   └── docs\RESTART-HERE.md
└── hospital-appointment\             ← 项目 2（进行中）→ 待建 GitHub 仓库
    └── docs\START-HERE.md            ← 你正在读的文件
```

**两个目录各自是独立 git 仓库**，互不影响。
在 DSH 侧栏里它们同属 `xmdeepseek` 工作区，靠**不同会话**区分工作内容。
