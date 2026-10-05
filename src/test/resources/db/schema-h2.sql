-- ===================================================================
-- 医院预约挂号系统 · 表结构（H2 测试版）
--
-- ⚠️ 这是一份**镜像**，不是另一份"测试用的简化模型"。
--    它必须与 db/schema.sql 保持**表名、列名、类型、约束完全一致**，
--    唯一允许的差别是 MySQL 特有的装饰（COMMENT / ENGINE / CHARSET）。
--
-- 为什么不用 Testcontainers 跑真 MySQL：
--   本机没有 Docker（与项目 1 相同的取舍）。代价是 H2 与 MySQL 的方言差异，
--   所以涉及 MySQL 特有写法的地方（例如 T-006 的原子 UPDATE、T-007 的唯一索引冲突）
--   必须在真实 MySQL 上再手工验证一次。
--
-- 为什么不用 H2 的 MODE=MySQL 直接跑 db/schema.sql：
--   那样确实能少一份文件，但会让"测试通过"依赖一堆兼容模式开关，
--   而且真实脚本里任何 MySQL 专有语法都会在测试里变成难懂的启动失败。
--   分成两份、把差异写明白，比藏着差异更可靠。
-- ===================================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    phone         VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    real_name     VARCHAR(50)  NOT NULL,
    id_card       VARCHAR(32)  NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    failed_count  INT          NOT NULL DEFAULT 0,
    locked_until  TIMESTAMP    NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_phone UNIQUE (phone)
);

CREATE TABLE IF NOT EXISTS department (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_department_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS doctor (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    department_id BIGINT       NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    title         VARCHAR(32)  NOT NULL,
    specialty     VARCHAR(255) NULL,
    intro         VARCHAR(500) NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_doctor_dept_name UNIQUE (department_id, name)
);
CREATE INDEX IF NOT EXISTS idx_doctor_department ON doctor (department_id);

CREATE TABLE IF NOT EXISTS schedule (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    doctor_id       BIGINT        NOT NULL,
    department_id   BIGINT        NOT NULL,
    work_date       DATE          NOT NULL,
    period          VARCHAR(8)    NOT NULL,
    total_slots     INT           NOT NULL,
    remaining_slots INT           NOT NULL,
    fee             DECIMAL(10,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_schedule_slot UNIQUE (doctor_id, work_date, period)
);
CREATE INDEX IF NOT EXISTS idx_schedule_doctor_date ON schedule (doctor_id, work_date);
CREATE INDEX IF NOT EXISTS idx_schedule_department ON schedule (department_id);

CREATE TABLE IF NOT EXISTS appointment (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    appointment_no  VARCHAR(32)   NOT NULL,
    user_id         BIGINT        NOT NULL,
    schedule_id     BIGINT        NOT NULL,
    doctor_id       BIGINT        NOT NULL,
    department_id   BIGINT        NOT NULL,
    idempotency_key VARCHAR(128)  NOT NULL,
    visit_date      DATE          NOT NULL,
    period          VARCHAR(8)    NOT NULL,
    fee             DECIMAL(10,2) NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    expire_at       TIMESTAMP     NULL,
    cancel_reason   VARCHAR(255)  NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_appointment_no UNIQUE (appointment_no),
    CONSTRAINT uk_appointment_idem UNIQUE (idempotency_key),
    CONSTRAINT uk_appointment_user_schedule UNIQUE (user_id, schedule_id)
);
CREATE INDEX IF NOT EXISTS idx_appointment_user ON appointment (user_id, id);
CREATE INDEX IF NOT EXISTS idx_appointment_status ON appointment (status);

CREATE TABLE IF NOT EXISTS notification (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    appointment_no VARCHAR(32)  NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    content        VARCHAR(500) NOT NULL,
    sent_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_notification_user ON notification (user_id, id);
CREATE INDEX IF NOT EXISTS idx_notification_appointment ON notification (appointment_no);
