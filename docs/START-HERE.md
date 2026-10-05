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
| 测试 | **98 个，全绿**（`mvn test`，H2 内存库，默认不依赖本机 MySQL/Redis/RabbitMQ） |
| 当前进度 | **Day 2 里程碑已达成**（T-001 ~ T-012 完成，A-01 ~ A-08 已通过）<br>T-017 Nginx 配置已完成并验证（真前端待接） |
| **下一步** | **T-013 Vue 前端初始化**（Day 3） |

> 💡 **Day 2 的里程碑已经能演示**：挂号成功 → 异步通知落库 → 延迟消息到期自动取消并归还号源，
> 三条链路都在**真实 RabbitMQ** 上验证过；防超卖在 **H2 与真实 MySQL 两处**都有证据。

> **文档纪律**：这份文件与 `PROGRESS.md` 曾经落后于代码（写着"Day 0"时 T-001/T-002 已做完）。
> **文档落后比没有文档更糟**——它会让下一个会话按错误的前提开工。每完成一个任务就更新。

---

## 已完成

### T-010 / T-011 / T-012 RabbitMQ 异步链路 ✅

- **A-06（延迟队列自动取消）与 A-07（MQ 挂了挂号仍成功）已通过**
- 拓扑：通知队列 + **延迟队列（TTL + 死信交换机）**
- 全套 MQ 组件挂在 `app.mq.enabled` 下，**默认关闭**：
  没装 broker 的机器也能启动，`mvn test` 不依赖 broker（N-04）
- ⚠️ **延迟消息只能表达"该检查了"，不能表达"该执行了"**——
  15 分钟里用户可能已付款，无条件取消会造成"付了钱、号没了、号源还被卖给别人"。
  已写成测试：下单 → 推进到 `PAID` → 等超过 TTL → 断言仍是 `PAID`
- ⚠️ T-011 曾暴露一个真缺口：`Notifier` 契约说"绝不抛异常"，但那是约定。
  测试用"总是抛异常"的桩，挂号立刻被打挂 → 调用方补了第二道兜底

### T-007 / T-008 / T-009 挂号三件套 ✅

- 端点：`POST /api/appointments`（幂等）、`POST /api/appointments/{no}/cancel`、`GET /api/appointments`
- **A-04（幂等）与 A-05（归还号源）已通过**
- ⚠️ 抓到两个真 bug：重复取消返回 409（应幂等）；**取消后无法重挂**
  （原 `(user_id, schedule_id)` 唯一索引把已取消的单也算进去，已改用生成列实现"活跃订单唯一"）

### T-006 号源扣减（防超卖）✅ ← 技术内核

- `ScheduleMapper.tryDeduct` / `tryReturn`：各**一条**带条件的原子 SQL
- **A-03 已通过，两处独立证据**：
  - H2：1000 线程抢 20 号 → 成功恰好 20、剩余恰好 0
  - **真实 MySQL**：成功 20、被拒 980、剩余 0（`scripts/verify-concurrency-on-mysql.ps1`）
- ✅ **两次反向验证**：植入"先查再改"后，H2 测试报 `expected 20 but was 64`；
  MySQL 验证程序的对照组报超卖 63 个

### T-017 Nginx 部署配置 🟡（配置已完成并验证）

- `deploy/nginx/nginx.conf`（80）与 `nginx.8080.conf`（仅改端口），**完整可 `-c` 使用**
- **8 项链路验证全通过**（真实 nginx + 真实后端）：首页 / 深层路由回退 / `/api` 代理 /
  `/health` / 登录经代理 / 业务查询经代理 / 未认证 401 / 不存在接口 404
- ⚠️ **80 端口被 `Steam++.Accelerator` 占用** → 用 8080 版即可（两份只差一行）
- ⚠️ 这次验证还抓到一个真 bug：**请求不存在的路径返回 500**（已修为 404）
- ⬜ 等 T-013~T-016 的真前端接上后做最终验收

### T-005 挂号状态机 ✅

- 交付：`AppointmentStatus` 枚举 + **显式迁移表**（`EnumMap` + 不可变集合）
- **验收 A-08 已通过**：23 个测试，**穷举 16 个状态对**逐条断言
- 两条不变量：**不可自环**（否则"重复提交"被当成合法推进）、**终态无出边**
- 关键设计：迁移表写成**数据**而非散落的 `if`——这样它才成为可穷举断言的对象
- ⚠️ 穷举测试的预期表是**独立手写的第二份定义**，不是从枚举里读的（否则是同义反复）
- ✅ **做过反向验证**：故意植入"自环 + 终态可取消"，8 条测试立刻失败

### T-004 科室 / 医生 / 排班查询 ✅

- 端点：`GET /api/departments`、`GET /api/doctors?deptId=`、
  `GET /api/schedules?doctorId=&from=&to=&page=&size=`
- **验收 A-02 已通过**（13 个集成测试 + 真实 MySQL 手工验证）
- 要点：科室**不分页**（字典数据）、只有排班分页且 **`page` 从 1 开始**；
  "不存在"返回 **404**、与"没数据"的 **200 空列表**区分；
  排班用一条 JOIN + 读模型 record 取完，**无 N+1**；
  服务端返回 `soldOut` 供前端预禁用按钮
- ⚠️ **T-004 一上来就红了 3 条测试，3 条都是真缺陷**（JOIN 同名列串位、缺参数返回 500、
  `totalPages` 没被序列化）。详见 `PROGRESS.md`。

### T-003 用户注册登录（JWT）✅

- 端点：`POST /api/auth/register`、`POST /api/auth/login`、`GET /api/auth/me`
- **验收 A-01 已通过**（集成测试 + 真实 MySQL 手工验证）：
  注册 201 → 登录 200 拿 token → 带 token 访问 `/me` 200；
  无 token / 篡改 token / 过期 token → **401 + JSON 错误体**；
  未登记的新接口默认 401；重复手机号 409；连续失败 5 次 → **423 锁定**
- **`SecurityConfig` 的"全放行"已换成真实规则**（默认拒绝 + 白名单），
  T-001 留下的 `TODO(T-003)` 已完成使命
- 新增 `/api/health`（部署与 Nginx 探活用；任务书没写但必须有）
- ⚠️ **T-003 踩了两个"测试全绿但功能是坏的"的坑**，都记在 `PROGRESS.md` 里，
  **新会话务必读一遍**——它们代表了一整类错误。

### T-001 项目骨架 ✅

- `pom.xml`：Spring Boot 3.3.5 / Java 21 / MyBatis-Plus 3.5.7 / MySQL / Redis / JWT / H2(test)
- ⚠️ **刻意先不加 `spring-boot-starter-amqp`**：本机 RabbitMQ 未安装，
  加了会产生自动配置噪音。这也正好是需求 A-07（MQ 不可用不影响主流程）——**T-010 再加**
- `.mvn/jvm.config`：指到 `D:\java\jdk-21`（PATH 上的 `java` 是 1.8）
- `application.yml` / `application-dev.yml`：凭据走环境变量，端口 8081
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

预置数据实测（已修复"重启导致重复插入"的 bug，现在**重启多少次都稳定**）：
**5 科室 / 10 医生 / 140 条排班 / 1 个演示账号**

演示账号：**手机号 `13800000001`，口令 `Demo@2026`**

> 口令由 `DemoDataInitializer` 在启动时用 `PasswordEncoder` **现算**，
> 不硬编码 BCrypt 哈希。理由见那个类的注释——项目 1 硬编码哈希的代价是
> 哈希成了"无法验证的魔法字符串"，一旦对不上就是"登录不上"。

---

## 下一步：T-013 Vue 前端初始化（Day 3）

**这是新会话该做的第一件事。**

要点：
1. `frontend/`，Vite + Vue 3 + vue-router + axios + Element Plus
2. ⚠️ **必须用 `npm.cmd`**（`npm` 被执行策略拦住）
3. 验收：`npm.cmd run dev` 起得来，能看到默认页
4. Element Plus **只用 `el-table`**，其余原生（决策 D-08：只看过教程，能跑通 > 用全）

⚠️ 已经准备好的前提，**别重复造**：
- 后端接口全部可用（见 README 第六节），鉴权是**默认拒绝**，新接口无需改安全配置
- **Nginx 配置已写好并验证过**（`deploy/nginx/`）：`root` 指向 `frontend/dist`，
  所以构建产物放到那里就能直接被 Nginx 提供
- `frontend/dist/index.html` 现在是**占位页**，`npm.cmd run build` 会覆盖它
- ⚠️ 80 端口被 `Steam++.Accelerator` 占用，本地验证用 `nginx.8080.conf`（8080）

### 之后的任务顺序

T-014 登录页 + axios 封装 + 路由守卫 → T-015 科室/医生/排班页 →
T-016 挂号 + 我的挂号页（**A-10 浏览器闭环**）→ T-017 最终验收 → T-019 文档定稿

> 时间不够时的砍法顺序见 `04-tasks-and-acceptance.md` 末尾。
> **绝不能砍的三项（A-03 / A-07 / A-08）已经全部完成，可以放心按顺序推进前端。**
---

## 环境事实（本机实测，别重复踩）

| 项 | 值 |
| --- | --- |
| JDK 21 | `D:\java\jdk-21`；⚠️ PATH 上的 `java` 是 **1.8**，必须设 `JAVA_HOME` |
| MySQL | Windows 服务 `MySQL80`，`root` / `123456` |
| Redis | Windows 服务 `Redis`，`127.0.0.1:6379`，口令 `123456` |
| **npm** | ⚠️ **必须用 `npm.cmd`**，`npm` 被执行策略拦住 |
| **Erlang** | ✅ `D:\erl-26.2.5.21`（OTP 26.2.5.21，zip 解压，不写 C 盘） |
| **RabbitMQ** | ✅ `D:\rabbitmq`（4.1.8）；⚠️ **服务化待执行**（见下） |
| **Nginx** | ✅ 已存在于 `D:\nginx`；用 **1.22.0**。⚠️ **80 端口被 Steam++ 占用** |
| PowerShell 控制台 | 显示中文会是乱码，**那是控制台 GBK 的问题，不是数据问题**（已用 `HEX()` 验证） |
| 启动命令 | 见下面 |

### RabbitMQ（已装好，管理台可用）

| 项 | 值 |
| --- | --- |
| 版本 | **RabbitMQ 4.1.8 + Erlang/OTP 26.2.5.21**（兼容矩阵内） |
| 目录 | `D:\rabbitmq`（sbin 与 etc 直接在下层，已拍平） |
| 数据/日志 | `D:\rabbitmq\data`（`RABBITMQ_BASE` 挪离 C 盘） |
| 节点 | `rabbit@LAPTOP-JTK1AI0C` |
| 管理台 | <http://localhost:15672> — **guest / guest**（仅限本机） |
| 端口 | 4369 / 5672 / 15672 / 25672 |
| ⚠️ 当前形态 | **独立进程**，重启机器后不会自动运行 |

**重启机器后要它自动起来，需执行一次提权脚本**（注册 Windows 服务需要管理员权限）：

```powershell
# 右键 PowerShell -> 以管理员身份运行，然后：
powershell -ExecutionPolicy Bypass -File D:\xmdeepseek\hospital-appointment\scripts\install-rabbitmq-service.ps1
```

> 执行后 RabbitMQ 与 MySQL80 / Redis 一样是**自启动服务**，日常只需 `net start|stop RabbitMQ`。
>
> 手动启动（不装服务时）：
> ```powershell
> D:\rabbitmq\sbin\rabbitmq-server.bat -detached   # 启动
> D:\rabbitmq\sbin\rabbitmqctl.bat stop            # 停止
> ```

### Nginx（已存在，待验证）

用 `D:\nginx\nginx-1.22.0-web\nginx-1.22.0-web`（1.22.0；1.18.0 也可用）。

⚠️ **`Steam++.Accelerator` 占着 80 端口**，`listen 80` 会直接启动失败。
演示前需关掉它，或把 `listen` 改成 8080（8080 目前空闲）。

### 启动后端

```powershell
cd D:\xmdeepseek\hospital-appointment
$env:JAVA_HOME = "D:\java\jdk-21"
$env:DB_PASSWORD = "123456"
$env:REDIS_PASSWORD = "123456"
$env:JWT_SECRET = "dev-only-secret-must-be-at-least-32-bytes-long"
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

> ⚠️ **`JWT_SECRET` 不设会直接启动失败**，报错信息会明确说"secret 至少需要 32 字节"。
> 这是刻意设计：一个可预测的默认密钥意味着任何人都能自己签一个令牌冒充任意用户。
> **启动失败比"用默认密钥默默跑起来"安全得多。**

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

### ⚠️ 最重要的一类：代码看起来对、测试全绿，但功能是坏的

这两个坑都发生在 T-003，**详细的症状/根因/修复见 `PROGRESS.md`**。
它们代表一整类错误，比记住具体修法更重要：

| 坑 | 症状 | 教训 |
| --- | --- | --- |
| **`@Transactional` 把登录失败计数一起回滚了** | 连续输错 5 次，`failed_count` 始终是 0，**账号永远不会被锁定** | 业务异常默认触发回滚。而"失败计数"恰恰是在抛异常的那条路径上写的。修法：`@Transactional(noRollbackFor = AuthException.class)` |
| **集成测试自己加了 `@Transactional`** | 上面的 bug 被**藏了整整一轮**：MockMvc 的请求跑在测试事务里，业务侧"回滚"只回滚到 savepoint，计数看得见 | **测试级事务会掩盖真实事务边界的 bug。** 认证这条链路刻意不加它，改成 `@AfterEach` 清理数据 |
| **`ON DUPLICATE KEY UPDATE` 没有唯一索引可触发** | 文档说 10 医生 / 140 排班，实际是 30 / 420，**每重启一次多一整套** | 写"防重复"之前先确认**唯一约束真的存在**。`doctor` 表缺 `uk_doctor_dept_name`，而排班按 `doctor_id` 生成，于是连带膨胀 |
| **JOIN 里两个表都有 `name` 列** | 科室名那一栏显示的是**医生名**，而且**不报错** | 同名列 + 构造器按位置映射 = 静默串位。修法：每个列显式起别名，别名 = record 字段名，让映射变成按名字 |
| **少一个 `@ExceptionHandler`** | 缺 `deptId` 时返回 **500**，让调用方去查服务端日志 | 状态码要反映**谁该负责**。漏写处理器不会编译报错，只会静默返回错的状态码 |
| **record 里的派生方法不会被序列化** | 分页响应没有 `totalPages`，前端分页器只能显示"上一页/下一页" | Jackson 只认 `getXxx()`。`record` 内自定义的 `totalPages()` 要加 `@JsonProperty` |
| **`push.ps1` 的 BOM 守卫不递归** | `scripts/` 下的 `.ps1` 完全不受保护，而守卫自己不报错 | **保护措施没覆盖到新位置 = 没有保护。** 修法：`-Recurse` |

> **动手前问自己一句**：我写的这道防线，**真的接上了吗**？覆盖范围和我想的一样吗？
> 上面这一整列坑，**都是同一个主题的五次重复**：
> 事务边界没接上、唯一索引没接上、列别名没接上、异常处理器没接上、连防 BOM 的守卫自己也没接上。
> **每一次的代码都"看起来是对的"。**
> 所以真正的习惯不是"记得写守卫"，而是**写完守卫之后去验证它覆盖了什么**。

### 其他

| 坑 | 症状 | 处理 |
| --- | --- | --- |
| **`RABBITMQ_LOGS` 指向目录** | broker 直接崩溃：`cannot_log_to_file, "d:/rabbitmq/data/log", eisdir` | 它必须是**日志文件路径**，不是目录。改成 `...\data\log\rabbit.log` |
| **Erlang 在 Windows 不认 `HOME`** | 报 `Failed to create cookie file 'd:/Users/tianliang/.erlang.cookie': enoent`——路径被拼坏了 | Erlang 读的是 `USERPROFILE`（`init:get_argument(home)` 可验证），不是 `HOME`。**更稳的做法是不依赖它**：用 `RABBITMQ_ERLANG_COOKIE` 显式固定 cookie |
| **服务与 CLI 的用户配置文件不同** | 服务以 LocalSystem 跑（cookie 在 `systemprofile`），你在自己终端跑 CLI（cookie 在 `C:\Users\你`）→ CLI 报连不上，而服务其实好好的 | 同上：`RABBITMQ_ERLANG_COOKIE` 固定同一个值，两边都不再依赖配置文件路径 |
| **`escript` 遇到 UTF-8 BOM 会挂** | `syntax error before ':'` | escript 要**无 BOM**——和 PowerShell 脚本**必须带 BOM** 正好相反，两者别搞混 |
| **Erlang 崩溃转储污染工作目录** | 工作区里出现 `erl_crash.dump`（1MB+） | 设 `ERL_CRASH_DUMP` 指到固定位置。**项目 1 也踩过这个** |
| `INSERT ... SELECT ... JOIN(派生表) ... ON DUPLICATE KEY UPDATE` | MySQL 报 **1064 语法错误** | `ON` 歧义。改用**纯 SQL + `INSERT IGNORE`** |
| 用存储过程 `DELIMITER $$` | Spring 脚本执行器不认 | `DELIMITER` 是 **MySQL 客户端命令**，不是 SQL |
| 排班用 `ON DUPLICATE KEY UPDATE` 覆盖 | **每次重启会把已消耗号源重置回满** | 必须用 `INSERT IGNORE`——只补缺失，不碰业务状态 |
| `CREATE INDEX IF NOT EXISTS` | MySQL 报 1064（**H2 支持，MySQL 不支持**） | 先查 `information_schema.STATISTICS`，或直接建 |
| 没设 `JWT_SECRET` | 应用启动失败 | **刻意如此**：可预测的默认密钥 = 谁都能伪造令牌 |
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
