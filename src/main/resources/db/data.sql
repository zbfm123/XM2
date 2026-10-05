-- ===================================================================
-- 医院预约挂号系统 · 预置数据
--
-- ⚠️ 纪律：
--   1. 只含**虚构**数据。真实姓名、真实手机号一律不得进入仓库。
--   2. 脚本可重复执行（INSERT ... ON DUPLICATE KEY UPDATE）。
--
-- 关于用户账号：**不在这里插入**。
--   口令哈希由应用启动时的 DemoDataInitializer 生成，
--   避免把 BCrypt 哈希硬编码进脚本（见该类的注释说明）。
--
-- 关于排班日期：用 CURDATE() 相对计算，**保证演示时号源总是"未来"的**。
--   写死日期的话，过几天演示就变成"只能看历史排班"，挂不了号。
-- ===================================================================

-- -------------------------------------------------------------------
-- 科室（5 个，覆盖常见场景）
-- -------------------------------------------------------------------
INSERT INTO department (code, name, description, sort_order) VALUES
    ('DEP-IM',  '内科',   '常见内科疾病诊治', 1),
    ('DEP-SUR', '外科',   '外科疾病诊治与手术', 2),
    ('DEP-PED', '儿科',   '儿童常见病诊治', 3),
    ('DEP-ENT', '耳鼻喉科', '耳鼻咽喉疾病诊治', 4),
    ('DEP-DERM','皮肤科', '皮肤病与性病诊治', 5)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

-- -------------------------------------------------------------------
-- 医生（10 位，虚构姓名）
-- -------------------------------------------------------------------
-- ⚠️ 这里的 ON DUPLICATE KEY UPDATE 依赖 doctor 上的唯一索引
--    uk_doctor_dept_name (department_id, name)。
--    起初漏建了这个索引，后果不是"多几行"这么轻：
--    每位医生重复插入，而排班是按 doctor_id 生成的，
--    于是**每次重启都连带多出一整套号源**——演示库会随重启不断膨胀。
--    排班的时间维度也无法兜住它：唯一键是 (doctor_id, work_date, period)，
--    而重复插入拿到的是新的 doctor_id。
-- -------------------------------------------------------------------
INSERT INTO doctor (department_id, name, title, specialty, intro)
SELECT d.id, t.name, t.title, t.specialty, t.intro
  FROM department d
  JOIN (
      SELECT 'DEP-IM'   AS code, '张伟民' AS name, '主任医师'   AS title, '高血压、糖尿病' AS specialty, '从事内科临床工作三十年' AS intro UNION ALL
      SELECT 'DEP-IM',   '李静',   '副主任医师', '呼吸系统疾病', '擅长慢性咳嗽与哮喘诊治' UNION ALL
      SELECT 'DEP-SUR',  '王建国', '主任医师',   '普外科手术',   '腹腔镜手术经验丰富' UNION ALL
      SELECT 'DEP-SUR',  '赵鹏',   '主治医师',   '骨科创伤',     '擅长骨折复位与康复指导' UNION ALL
      SELECT 'DEP-PED',  '陈秀兰', '主任医师',   '小儿呼吸道感染', '儿科临床三十年' UNION ALL
      SELECT 'DEP-PED',  '刘敏',   '主治医师',   '新生儿护理',   '新生儿常见问题诊疗' UNION ALL
      SELECT 'DEP-ENT',  '孙立',   '副主任医师', '鼻炎、中耳炎', '耳鼻喉内镜检查经验丰富' UNION ALL
      SELECT 'DEP-ENT',  '周芳',   '主治医师',   '咽喉疾病',     '慢性咽炎与声带疾病' UNION ALL
      SELECT 'DEP-DERM', '吴海涛', '主任医师',   '湿疹、银屑病', '皮肤病中西医结合治疗' UNION ALL
      SELECT 'DEP-DERM', '郑丽',   '主治医师',   '痤疮与皮炎',   '青少年痤疮规范化治疗'
  ) t ON t.code = d.code
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- -------------------------------------------------------------------
-- 排班：为每位医生生成"今天起未来 7 天"的上下午排班
--
-- 号源数与挂号费按职称区分，演示时能看出差异：
--   主任医师  20 个号 / 50 元
--   副主任医师 25 个号 / 30 元
--   主治医师  30 个号 / 20 元
--
-- 用数字辅助表生成日期序列，避免手写 140 条 INSERT。
--
-- ⚠️ 这里踩过两个坑，都值得记下来：
--
-- 【坑 1】最初写的是 `INSERT ... SELECT ... JOIN (派生表) ... ON DUPLICATE KEY UPDATE`，
--   MySQL 报 1064 语法错误。因为 `JOIN ... ON` 与 `ON DUPLICATE KEY` 的 ON 产生歧义。
--   **SQL 越"聪明"，越容易在解析器边界上翻车。**
--
-- 【坑 2】改用存储过程（`DELIMITER $$ ... $$`）也不行：
--   `DELIMITER` 是 **MySQL 命令行客户端**的指令，不是 SQL 语法，
--   Spring 的 ScriptUtils 不认它。
--
-- 最终方案：纯 SQL + **INSERT IGNORE**。
--
-- ⚠️ 为什么是 INSERT IGNORE 而不是 ON DUPLICATE KEY UPDATE：
--   排班一旦有人挂过号，remaining_slots 就是业务状态了。
--   如果用 UPDATE 覆盖，**每次重启都会把已消耗的号源重置回满**——
--   这在演示时是灾难（明明挂满了，重启后又变成全空）。
--   INSERT IGNORE 只补缺失的排班，不碰已存在的。
-- -------------------------------------------------------------------
INSERT IGNORE INTO schedule (doctor_id, department_id, work_date, period,
                             total_slots, remaining_slots, fee)
SELECT doc.id,
       doc.department_id,
       DATE_ADD(CURDATE(), INTERVAL seq.n DAY),
       p.period,
       CASE doc.title WHEN '主任医师' THEN 20 WHEN '副主任医师' THEN 25 ELSE 30 END,
       CASE doc.title WHEN '主任医师' THEN 20 WHEN '副主任医师' THEN 25 ELSE 30 END,
       CASE doc.title WHEN '主任医师' THEN 50.00 WHEN '副主任医师' THEN 30.00 ELSE 20.00 END
  FROM doctor doc
  CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) seq
  CROSS JOIN (SELECT 'AM' AS period UNION ALL SELECT 'PM') p;
