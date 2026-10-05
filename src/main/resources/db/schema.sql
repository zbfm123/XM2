-- ===================================================================
-- 医院预约挂号系统 · 表结构
--
-- 6 张表，够用且不臃肿（见 docs/02-architecture.md 的数据模型）：
--   sys_user      用户（患者）
--   department    科室
--   doctor        医生
--   schedule      排班（号源）    ← 防超卖发生在这里
--   appointment   挂号订单        ← 状态机在这里
--   notification  通知记录        ← 异步消费的产物
--
-- ⚠️ 纪律：脚本必须可重复执行（CREATE TABLE IF NOT EXISTS），
--    且只含虚构数据。真实姓名、真实手机号一律不得进入仓库。
-- ===================================================================

-- -------------------------------------------------------------------
-- 用户（患者）
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    phone         VARCHAR(20)  NOT NULL COMMENT '手机号，登录账号',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，绝不存明文',
    real_name     VARCHAR(50)  NOT NULL COMMENT '真实姓名（演示数据为虚构）',
    id_card       VARCHAR(32)  NULL COMMENT '证件号（演示数据为虚构）',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    failed_count  INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    locked_until  DATETIME     NULL COMMENT '锁定到期时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户（患者）';

-- -------------------------------------------------------------------
-- 科室
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS department (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32) NOT NULL COMMENT '科室编码',
    name        VARCHAR(64) NOT NULL COMMENT '科室名称',
    description VARCHAR(255) NULL COMMENT '简介',
    sort_order  INT         NOT NULL DEFAULT 0 COMMENT '展示顺序',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_department_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '科室';

-- -------------------------------------------------------------------
-- 医生
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS doctor (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    department_id BIGINT       NOT NULL COMMENT '所属科室',
    name          VARCHAR(50)  NOT NULL COMMENT '姓名（虚构）',
    title         VARCHAR(32)  NOT NULL COMMENT '职称：主任医师/副主任医师/主治医师',
    specialty     VARCHAR(255) NULL COMMENT '擅长',
    intro         VARCHAR(500) NULL COMMENT '简介',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_doctor_department (department_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '医生';

-- -------------------------------------------------------------------
-- 排班（号源）—— 本项目技术内核所在
--
-- remaining_slots 是并发扣减的对象。
-- 扣减**必须**用一条带条件的原子 UPDATE：
--     UPDATE schedule SET remaining_slots = remaining_slots - 1
--      WHERE id = ? AND remaining_slots > 0
-- 绝不能"先查再改"——那中间有窗口，一定会超卖。
--
-- uk_schedule_slot 唯一索引防止同一医生同一天同一时段被排两次班。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schedule (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    doctor_id       BIGINT       NOT NULL COMMENT '医生',
    department_id   BIGINT       NOT NULL COMMENT '冗余科室，便于按科室过滤',
    work_date       DATE         NOT NULL COMMENT '出诊日期',
    period          VARCHAR(8)   NOT NULL COMMENT '时段：AM / PM',
    total_slots     INT          NOT NULL COMMENT '总号源',
    remaining_slots INT          NOT NULL COMMENT '剩余号源，并发扣减对象',
    fee             DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '挂号费',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_slot (doctor_id, work_date, period),
    KEY idx_schedule_doctor_date (doctor_id, work_date),
    KEY idx_schedule_department (department_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '排班（号源）';

-- -------------------------------------------------------------------
-- 挂号订单 —— 状态机所在
--
-- 两个唯一索引是**第二道防线**（决策 D-04）：
--   uk_appointment_idem          防重复提交（幂等）
--   uk_appointment_user_schedule 防同一人对同一排班挂两个号
--
-- 应用层的判断可能被并发绕过，**唯一索引不会被绕过**。
-- 应用层负责给用户友好提示，唯一索引负责保证数据一定正确。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS appointment (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    appointment_no  VARCHAR(32)  NOT NULL COMMENT '业务单号，对外展示（不暴露自增 id）',
    user_id         BIGINT       NOT NULL COMMENT '患者',
    schedule_id     BIGINT       NOT NULL COMMENT '排班',
    doctor_id       BIGINT       NOT NULL COMMENT '冗余，便于查询展示',
    department_id   BIGINT       NOT NULL COMMENT '冗余，便于查询展示',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键，客户端提供',
    visit_date      DATE         NOT NULL COMMENT '就诊日期',
    period          VARCHAR(8)   NOT NULL COMMENT '就诊时段',
    fee             DECIMAL(10,2) NOT NULL COMMENT '挂号费',
    status          VARCHAR(32)  NOT NULL COMMENT 'PENDING_PAYMENT / PAID / COMPLETED / CANCELLED',
    expire_at       DATETIME     NULL COMMENT '待支付过期时间，配合延迟队列',
    cancel_reason   VARCHAR(255) NULL COMMENT '取消原因',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_appointment_no (appointment_no),
    UNIQUE KEY uk_appointment_idem (idempotency_key),
    UNIQUE KEY uk_appointment_user_schedule (user_id, schedule_id),
    KEY idx_appointment_user (user_id, id),
    KEY idx_appointment_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '挂号订单';

-- -------------------------------------------------------------------
-- 通知记录 —— 异步消费的产物
--
-- 本期不发真实短信（见 docs/01 的"不做清单"），
-- 但**消费者逻辑与真实场景完全一致**，替换实现即可。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL COMMENT '接收人',
    appointment_no VARCHAR(32)  NOT NULL COMMENT '关联订单',
    type           VARCHAR(32)  NOT NULL COMMENT 'BOOKED / CANCELLED / REMINDER',
    content        VARCHAR(500) NOT NULL COMMENT '通知内容',
    sent_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notification_user (user_id, id),
    KEY idx_notification_appointment (appointment_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '通知记录';
