# 医院预约挂号系统

> 秋招项目 2。与**合同智能审查平台**互补：前者证明"我不相信模型的输出"，
> 本项目证明"我不相信并发下的判断"——**都是"不相信，但用工程手段兜住"。**

---

## 一、项目简介

一个前后端分离的医院预约挂号系统：患者选择科室 → 医生 → 号源 → 提交挂号 → 收到通知 → 可取消。

**这个项目要证明三件事**（面试就讲这三个）：

| # | 亮点 | 说明 |
| --- | --- | --- |
| ① | **防止号源超卖** | 把"判断"和"扣减"压成一条**原子 UPDATE**，用受影响行数回答问题。不用分布式锁——单库能解决的事不引额外组件 |
| ② | **MQ 挂了挂号仍然成功** | 通知是"尽力而为"，外部依赖的失败不否定已经完成的业务动作 |
| ③ | **状态机穷举测试** | 全部状态对的合法性与预期表一致，且断言不可自环、终态无出边 |

> **这三项绝不砍。** 砍了项目就变成"又一个 CRUD"，不值得写进简历。

**与项目 1 的分工**（技术栈刻意不重叠）：

| | 项目 1：合同智能审查 | 项目 2：医院预约挂号 |
| --- | --- | --- |
| 定位 | 差异化亮点 | 主流能力证明 |
| 关键能力 | AI 工程化、证据对齐、只追加审计 | **并发控制**、异步消息、前后端分离、部署 |
| 外部依赖失败 | AI 挂了 → 规则与人工流程不受影响 | **MQ 挂了 → 挂号仍然成功** |
| 第二道防线 | 数据库触发器 + 哈希链 | **数据库唯一索引**（防重复提交） |
| 前端 | 零构建原生 HTML | **Vue 3 + Element Plus + axios** |

---

## 二、技术栈

| 层 | 选型 | 版本 / 说明 |
| --- | --- | --- |
| 后端框架 | Spring Boot | **3.3.5** |
| 语言 / 运行时 | Java | **21** |
| 持久层 | MyBatis-Plus | 3.5.7（另有 `mysql-connector-j`） |
| 数据库 | MySQL | **8**，库名 `hospital_appointment` |
| 缓存 / 锁 | Redis | 号源缓存、幂等辅助（备选方案的分布式锁未采用） |
| 消息队列 | RabbitMQ | **4.1.8**（Erlang/OTP 26.2.5.21）——异步通知 + TTL/DLX 延迟队列 |
| 鉴权 | Spring Security + JWT | jjwt 0.12.6；做法直接复用项目 1（决策 D-09） |
| 测试 | JUnit 5 + MockMvc + H2 | H2 内存库，**不依赖本机 MySQL/Redis** |
| 前端 | Vue 3 + Vite + Element Plus + axios + vue-router | 计划中（T-013 ~ T-016） |
| 部署 | Nginx | 计划中（T-017）；端口：后端 **8081**、前端 dev **5173**、Nginx **80** |

> ⚠️ `pom.xml` 里**刻意还没有** `spring-boot-starter-amqp`。
> 理由写在 pom 的注释里：本机 RabbitMQ 装机顺序在后，先加依赖会产生自动配置噪音；
> 而"先在没有 MQ 的环境下把业务跑通"正好就是需求 A-07（MQ 不可用不影响主流程）。
> **T-010 接入 MQ 时再加。**

### 数据模型（6 张表）

| 表 | 说明 |
| --- | --- |
| `sys_user` | 用户（患者） |
| `department` | 科室 |
| `doctor` | 医生 |
| **`schedule`** | **排班（号源）——防超卖发生在这里** |
| **`appointment`** | **挂号订单——状态机在这里** |
| `notification` | 通知记录（异步消费的产物） |

---

## 三、环境要求与版本（本机 D 盘实测值）

| 组件 | 版本 / 位置 | 备注 |
| --- | --- | --- |
| **JDK 21** | `D:\java\jdk-21` | ⚠️ **PATH 上的 `java` 是 1.8**，必须显式设 `JAVA_HOME`（`.mvn/jvm.config` 已兜一层） |
| Maven | **3.9.9** | 用 `JAVA_HOME` 指向的 JDK 跑 |
| MySQL | **8**，Windows 服务 `MySQL80` | `localhost:3306`，本机 `root` / `123456`；库 `hospital_appointment` |
| Redis | Windows 服务 `Redis` | `127.0.0.1:6379`，口令 `123456` |
| **Erlang** | **OTP 26.2.5.21**，`D:\erl-26.2.5.21` | zip 解压，不往 C 盘写东西 |
| **RabbitMQ** | **4.1.8**，`D:\rabbitmq` | 数据/日志在 `D:\rabbitmq\data`（`RABBITMQ_BASE` 挪离 C 盘）<br>管理台 <http://localhost:15672> — `guest` / `guest`（仅限本机）<br>端口 4369 / 5672 / 15672 / 25672 |
| Node.js | **v24.18.0** + npm | ⚠️ **必须用 `npm.cmd`**——`npm.ps1` 被执行策略拦住 |

> 版本之间不是随便配的：**RabbitMQ 4.1.x 要求 Erlang 26.2 ~ 27.x**，
> 选 26.2 是最低要求但最稳，避开 Erlang 27/28 在 Windows 上的已知问题。

**RabbitMQ 的形态注意**：当前是**独立进程**，重启机器后不会自动运行。
要它随机器自启，用管理员 PowerShell 执行一次（幂等，可重复执行）：

```powershell
powershell -ExecutionPolicy Bypass -File D:\xmdeepseek\hospital-appointment\scripts\install-rabbitmq-service.ps1
```

不装服务时的手动启停：

```powershell
D:\rabbitmq\sbin\rabbitmq-server.bat -detached   # 启动
D:\rabbitmq\sbin\rabbitmqctl.bat stop            # 停止
```

---

## 四、启动步骤

### 1. 先确认依赖服务在跑

```powershell
# 三个 Windows 服务：MySQL80 / Redis / RabbitMQ（RabbitMQ 服务化后才有）
Get-Service MySQL80, Redis | Select-Object Name, Status

# 库得先存在（表结构与数据由应用启动时自动建/补，见第五节）
mysql -uroot -p123456 -e "CREATE DATABASE IF NOT EXISTS hospital_appointment DEFAULT CHARSET utf8mb4;"
```

### 2. 设环境变量并启动后端（端口 8081）

```powershell
cd D:\xmdeepseek\hospital-appointment

$env:JAVA_HOME     = "D:\java\jdk-21"       # ⚠️ 必设：PATH 上的 java 是 1.8
$env:DB_PASSWORD   = "123456"
$env:REDIS_PASSWORD = "123456"
$env:JWT_SECRET    = "dev-only-secret-must-be-at-least-32-bytes-long"

mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

看到 `Tomcat started on port 8081` 就是起来了。

> ⚠️ **上面三个值是"仅本地开发"的示例值**，抄自 `docs/START-HERE.md`。
> 真实环境请用各自独立的强随机值，且**绝不进仓库**——凭据只走环境变量。

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | **是** | **不设会直接启动失败**，这是刻意设计；**长度必须 ≥ 32 字节** |
| `DB_PASSWORD` | 是 | MySQL 口令（`application-dev.yml` 里默认值为空） |
| `REDIS_PASSWORD` | 是 | Redis 口令 |
| `JAVA_HOME` | 是 | 必须指向 `D:\java\jdk-21` |
| `DB_USERNAME` / `REDIS_HOST` / `REDIS_PORT` | 否 | 默认 `root` / `127.0.0.1` / `6379` |
| `SPRING_PROFILES_ACTIVE` | 否 | 默认 `dev` |

> **为什么没设 `JWT_SECRET` 就启动失败，而不是给个默认值？**
> 一个可预测的默认密钥意味着**任何人都能自己签一个令牌冒充任意用户**。
> 启动失败比"用默认密钥默默跑起来"安全得多——这也是刻意的取舍，不是配置疏漏。

### 3. 验证进程活着

```powershell
curl.exe -s http://localhost:8081/api/health
# {"status":"UP","app":"hospital-appointment","time":"..."}
```

---

## 五、数据库初始化

`dev` profile 下，`src/main/resources/db/schema.sql` 与 `data.sql` **在应用启动时自动执行**
（配置见 `application-dev.yml` 的 `spring.sql.init`，`mode: always`）。

**两条纪律写在脚本注释里，也是实测过的**：

| 纪律 | 做法 | 为什么 |
| --- | --- | --- |
| **可重复执行** | 建表全部 `CREATE TABLE IF NOT EXISTS`，预置数据用 `ON DUPLICATE KEY UPDATE` / `INSERT IGNORE` | 重启多少次都稳定，不需要手工清库 |
| **不碰业务状态** | 排班用 **`INSERT IGNORE`**，**不用 `ON DUPLICATE KEY UPDATE`** | 排班一旦被人挂过号，`remaining_slots` 就是业务状态。用 UPDATE 覆盖会**每次重启把已消耗号源重置回满** |

预置数据实测值（**反复重启稳定，不会膨胀**）：

| 数据 | 数量 |
| --- | --- |
| 科室 | **5** |
| 医生 | **10**（虚构姓名） |
| 排班 | **140** 条（每位医生"今天起未来 7 天"的上下午） |
| 演示账号 | **1**（手机号 `13800000001`） |

号源数与挂号费按职称区分：主任医师 20 个号 / 50 元，副主任医师 25 个号 / 30 元，主治医师 30 个号 / 20 元。
排班日期用 `CURDATE()` 相对计算，**保证演示时号源总是"未来"的**。

> ⚠️ 演示账号**不在 `data.sql` 里**：口令哈希由 `DemoDataInitializer` 启动时用 `PasswordEncoder` 现算。
> 把 BCrypt 哈希硬编码进脚本的代价是——哈希成了"无法验证的魔法字符串"，
> 一旦对不上，演示时就是"登录不上"，而错误现场只会告诉你"口令错误"。

**应用层是"让用户看到友好提示"，唯一索引是"保证数据一定正确"**——本项目两条第二道防线：

```sql
UNIQUE KEY uk_appointment_idem (idempotency_key)                -- 防重复提交（幂等）
UNIQUE KEY uk_appointment_user_schedule (user_id, schedule_id)  -- 防同一人抢两个号
UNIQUE KEY uk_schedule_slot (doctor_id, work_date, period)      -- 防重复排班
```

---

## 六、已实现接口

**当前可用**（T-003 已完成，验收 A-01 已通过）：

| 方法 | 路径 | 鉴权 | 成功状态码 | 说明 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/auth/register` | 匿名 | **201** | 手机号 + 口令注册，口令 BCrypt 存储；**不返回令牌** |
| `POST` | `/api/auth/login` | 匿名 | 200 | 返回 JWT |
| `GET` | `/api/auth/me` | **需 Bearer 令牌** | 200 | 当前用户信息 |
| `GET` | `/api/health` | 公开 | 200 | 只验证进程活着，**不含业务数据** |
| `GET` | `/api/departments` | 需令牌 | 200 | 科室列表（**数组**，字典数据不分页） |
| `GET` | `/api/doctors?deptId=` | 需令牌 | 200 | 该科室医生列表，**含 `departmentName`**；`deptId` 必填 |
| `GET` | `/api/schedules?doctorId=&from=&to=&page=&size=` | 需令牌 | 200 | 排班（号源）分页，**含 `remainingSlots` 与 `soldOut`** |

> `GET /api/schedules` 的分页 **`page` 从 1 开始**；`from` / `to` 为 `yyyy-MM-dd`，可选；
> `size` 默认 10、上限 100。返回 `{items, total, page, size, totalPages}`。

安全策略是**默认拒绝**（`SecurityConfig`）：白名单只有 `/api/auth/register`、`/api/auth/login`、
`/api/health`、`/error`，**其余新接口自动受保护**，未登记的路径默认 401。
（科室/医生/排班虽然不是敏感数据，也同样要求登录——**不做"只读数据就放行"的例外**，
那正是最容易在后续改动里漏掉的一块。）

**错误码补充分支**（T-004 新增）：

| 状态码 | code | 触发条件 |
| --- | --- | --- |
| 404 | `NOT_FOUND` | **科室 / 医生不存在**——与"存在但没有数据"的 **200 空列表**刻意区分开 |
| 400 | `INVALID_PARAMETER` | 缺少必填参数（如 `/api/doctors` 不带 `deptId`）、`page<1` |

**已知错误响应**（统一格式 `{code, message, path, time}`）：

| 状态码 | code | 触发条件 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 参数校验失败（`fields` 里一次性给出所有字段错误） |
| 400 | `INVALID_PARAMETER` | 参数格式不对（如 `?page=abc`） |
| 401 | `BAD_CREDENTIALS` | 口令错误 **或** 账号不存在（**不泄露账号是否存在**） |
| 401 | `TOKEN_INVALID` / `TOKEN_EXPIRED` | 令牌被篡改 / 已过期（**两者区分开**） |
| 401 | `UNAUTHENTICATED` | 无令牌访问受保护接口（**JSON 错误体，不是 HTML 错误页**） |
| 403 | `ACCOUNT_DISABLED` | 账号被停用 |
| 409 | `PHONE_ALREADY_REGISTERED` | 手机号已注册 |
| **423** | `ACCOUNT_LOCKED` | **连续失败 5 次**锁定 15 分钟（响应里带 `unlockAt`） |

### 调用示例：注册 → 登录拿 token → 带 token 访问 `/me`

**演示账号**：手机号 `13800000001`，口令 `Demo@2026`（仅本地演示库）。

以下的 PowerShell 版本整段可复制执行，用 `ConvertFrom-Json` 取字段，避免手工复制 token：

```powershell
$base = "http://localhost:8081"

# ① 注册（如该手机号已存在会返回 409，那就直接跳到第 ② 步）
curl.exe -s -X POST "$base/api/auth/register" `
  -H "Content-Type: application/json" `
  -d '{\"phone\":\"13900000002\",\"password\":\"Demo@2026\",\"realName\":\"演示患者\"}'
# → 201 {"id":2,"phone":"13900000002","realName":"演示患者"}

# ② 登录拿 token（返回体含 token / tokenType / expiresIn / expiresAt / user）
$login = curl.exe -s -X POST "$base/api/auth/login" `
  -H "Content-Type: application/json" `
  -d '{\"phone\":\"13800000001\",\"password\":\"Demo@2026\"}' | ConvertFrom-Json
$token = $login.token
"expiresIn=$($login.expiresIn)s"

# ③ 带 token 访问 /me
curl.exe -s "$base/api/auth/me" -H "Authorization: Bearer $token"
# → 200 {"id":1,"phone":"13800000001","realName":"演示患者"}

# ④ 反例：不带 token → 401 + JSON 错误体
curl.exe -s -i "$base/api/auth/me" | Select-Object -First 1
```

Bash / Git Bash 版本：

```bash
BASE=http://localhost:8081

# ① 注册
curl -s -X POST "$BASE/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13900000002","password":"Demo@2026","realName":"演示患者"}'

# ② 登录拿 token
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000001","password":"Demo@2026"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)

# ③ 带 token 访问 /me
curl -s "$BASE/api/auth/me" -H "Authorization: Bearer $TOKEN"

# ④ 健康检查（公开）
curl -s "$BASE/api/health"
```

> ⚠️ **PowerShell 控制台里中文可能显示成乱码**——那只是控制台 GBK 编码的问题，不是数据问题。
> 已用 `HEX()` 与 `curl` 验证过库内与响应体都是正确的 UTF-8。

### 计划中（尚未实现）

**T-004 科室 / 医生 / 排班查询**正在进行中：`DepartmentService` / `DoctorService` /
`DepartmentMapper` / `DoctorMapper` / `ScheduleMapper` 等已落地，
但**还没有对应的 `Controller`**，因此下面的接口**当前不可用**：

| 方法 | 计划路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/departments` | 科室列表 |
| `GET` | `/api/doctors?deptId=` | 按科室查医生列表 |
| `GET` | `/api/schedules?doctorId=` | 医生排班列表，需返回 `remainingSlots` |

更远的计划（见 `docs/04-tasks-and-acceptance.md`）：
`POST /api/appointments`（幂等挂号）、`GET /api/appointments`（我的挂号）、
`POST /api/appointments/{no}/cancel`（取消并归还号源）。

---

## 七、测试

```powershell
cd D:\xmdeepseek\hospital-appointment
$env:JAVA_HOME = "D:\java\jdk-21"
mvn test
```

| 测试类 | 用例数 |
| --- | --- |
| `AuthIntegrationTest` | 22 |
| `AuthServiceTest` | 7 |
| `SysUserMapperTest` | 4 |
| **合计** | **33 个，全绿** |

**不依赖本机 MySQL / Redis**：测试用 H2 内存库（`MODE=MySQL`）+ 内存版 Redis 实现，
任何人 clone 下来 `mvn test` 就能跑。这是"干净机器可复现"（N-04）的一部分。

已验证的行为（对应验收 A-01）：注册 201 → 登录 200 拿 token → 带 token 访问 `/me` 200；
无 token / 篡改 token / 过期 token → 401 + JSON 错误体；未登记的新接口默认 401；
重复手机号 409；连续失败 5 次 → 423 锁定；账号停用后 `/me` 立刻 401。

> **测试刻意不加类级 `@Transactional`**，改成 `@AfterEach` 自己清理数据。
> 原因见 `docs/PROGRESS.md`：测试级事务会**掩盖真实事务边界的 bug**——
> 它曾经把"失败计数被回滚、账号永远不会被锁定"这个 bug 藏了整整一轮。

**H2 与 MySQL 有方言差异**，涉及 MySQL 特有写法的地方（原子 UPDATE、唯一索引冲突）
必须**在真实 MySQL 上再手工验证一次**。

---

## 八、已知限制

**提前写出来，面试主动交代**（摘自 `docs/02-architecture.md` 第八节）：

| 限制 | 说明 |
| --- | --- |
| **通知不是真实短信** | 写库 + 日志。消费者逻辑与真实场景一致，替换实现即可 |
| **支付是模拟的** | 没有第三方对接 |
| **没有管理端** | 数据靠 SQL 预置，属主动范围决策（管理端最耗时、零技术深度） |
| **未做鉴权细分** | 只有"登录用户"一种角色，**没有 RBAC** |
| **未做压测** | 并发测试验证的是**正确性**（不超卖），**不是吞吐量。不要声称高并发** |
| **单体部署** | 无多实例，因此没验证过分布式场景下的锁行为（因为压根没用锁） |

> ⚠️ 最后两条尤其要注意：**并发测试证明了"不超卖"，但没有证明"能扛多少 QPS"。**
> 面试时不要把它说成性能数据。

其他当前状态：

- **RabbitMQ 未服务化**：当前是独立进程，重启机器后不会自动运行（脚本已备好，见第三节）。
- **Nginx 未配通**：`D:\nginx` 下已有 1.22.0 可用，但 **80 端口被 `Steam++.Accelerator` 占用**，
  演示前需关闭它或把 `listen` 改到 8080（T-017 的硬阻塞）。
- **前端未开始**：Vue 3 项目为 T-013 ~ T-016。

---

## 九、文档索引

| 想知道什么 | 看哪份 |
| --- | --- |
| **从这里开始**（一分钟状态、环境事实、启动命令） | [docs/START-HERE.md](docs/START-HERE.md) |
| 项目索引与文档地图 | [docs/00-project-index.md](docs/00-project-index.md) |
| 做什么、不做什么、为什么（需求 F-01 ~ F-05、验收 A-01 ~ A-10、"不做清单"） | [docs/01-requirements-and-scope.md](docs/01-requirements-and-scope.md) |
| 怎么设计、每个决策的理由（D-01 ~ D-10、防超卖方案对比、已知限制） | [docs/02-architecture.md](docs/02-architecture.md) |
| 任务拆分与验收标准（T-001 ~ T-019 + 砍法顺序） | [docs/04-tasks-and-acceptance.md](docs/04-tasks-and-acceptance.md) |
| 新技术学习清单（三样新技术的最小集） | [docs/05-learning-plan.md](docs/05-learning-plan.md) |
| **进度与踩坑**（真实状态，不美化） | [docs/PROGRESS.md](docs/PROGRESS.md) |

---

## 十、三条纪律（沿用项目 1，不重新发明）

1. **规格不存在，不动工。** 新功能先写进 `docs/01` 与 `docs/04`。
2. **错误分支必须有明确错误码**，禁止宽泛 `catch` 吞掉根因，禁止用默认值兜底。
3. **小步提交**：每完成一个任务即跑全量测试 → 更新文档 → 推送。

> **文档落后比没有文档更糟**——它会让下一个会话按错误的前提开工。每完成一个任务就更新。
