package com.demo.hospital.schedule;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在<b>真实 MySQL</b> 上验证号源防超卖（验收 A-03 的第二处证据）。
 *
 * <h2>为什么已经有了 H2 的测试，还要再写一个</h2>
 *
 * 测试套件里的 {@code ScheduleConcurrencyTest} 跑在 H2（MODE=MySQL）上，
 * 好处是任何人 {@code mvn test} 都能复现，不需要本机装 MySQL。
 * 但<b>本项目的防超卖依赖的是 InnoDB 的行锁</b>，而 H2 的锁实现与它不同：
 * H2 用的是 MVStore 引擎，锁粒度与加锁时机都不保证与 InnoDB 一致。
 *
 * <p>所以纪律是：<b>并发正确性必须在真实 MySQL 上再验一次</b>。
 * 两处都通过，"不超卖"这句话才站得住；只跑 H2 就宣称防超卖成立，
 * 是在用一个近似实现的行为替代真实实现的行为——<b>而这两者恰恰可能在锁上不同</b>。
 *
 * <h2>为什么是"程序"而不是 JUnit 测试</h2>
 *
 * 为了<b>不引入一个"必须有 MySQL 才能跑"的测试</b>。
 * 如果把它写成 {@code @SpringBootTest} 并挂在 test profile 上，
 * 那么"干净机器 clone 下来 {@code mvn test} 就能全绿"（N-04 的一部分）就破了。
 * 所以它被做成一个可以按需运行的独立程序，由脚本
 * {@code scripts/verify-concurrency-on-mysql.ps1} 调用。
 *
 * <p>它<b>不依赖 Spring、不依赖项目代码</b>：只用 JDBC 打真实库，
 * 执行与 {@code ScheduleMapper.tryDeduct} <b>逐字一致</b>的那条 SQL。
 * 这样测的就是"这条 SQL 在 InnoDB 上是否真的原子"，而不掺入任何框架行为。
 *
 * <h2>它往哪里写数据</h2>
 *
 * 用<b>独立的库</b> {@code hospital_appointment_conc}，不碰开发库
 * {@code hospital_appointment}（那里有预置的演示数据）。
 * 程序结束时把自己的测试行删干净，库本身保留（便于重复运行）。
 */
public final class MySqlConcurrencyVerifier {

    private static final String HOST = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
    private static final String PORT = System.getenv().getOrDefault("DB_PORT", "3306");
    private static final String USER = System.getenv().getOrDefault("DB_USERNAME", "root");
    private static final String PASS = System.getenv().getOrDefault("DB_PASSWORD", "");
    private static final String CONC_DB = "hospital_appointment_conc";

    private static final int THREADS = 1000;
    private static final int SLOTS = 20;
    private static final int POOL = 64;

    /**
     * 与 {@code ScheduleMapper.tryDeduct} 逐字一致的 SQL。
     *
     * <p>刻意复制而不是引用项目代码：这个程序的目的是验证
     * "<b>这条语句在 InnoDB 上是否原子</b>"。如果它反而依赖项目代码，
     * 那么"项目代码写错了"这件事就会被同一个错误掩盖掉。
     */
    private static final String TRY_DEDUCT =
            "UPDATE schedule SET remaining_slots = remaining_slots - 1, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND remaining_slots > 0";

    /** 对照组：故意的"先查再改"错误写法，用来证明这个验证程序真的能发现超卖。 */
    private static final String NAIVE_READ = "SELECT remaining_slots FROM schedule WHERE id = ?";
    private static final String NAIVE_WRITE =
            "UPDATE schedule SET remaining_slots = remaining_slots - 1, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ?";

    private MySqlConcurrencyVerifier() {
    }

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");

        System.out.println("=== 真实 MySQL 并发验证（A-03）===");
        System.out.printf("MySQL: %s:%s  用户: %s%n", HOST, PORT, USER);

        createDatabaseIfAbsent();
        String url = jdbcUrl(CONC_DB);
        createTableIfAbsent(url);

        int failures = 0;
        failures += verifyAtomicDeduct(url);
        failures += verifyNaiveImplementationOversells(url);
        failures += verifyReturnUpperBound(url);

        System.out.println();
        if (failures == 0) {
            System.out.println("=== 结论：全部通过 —— 防超卖在真实 MySQL 上成立 ===");
        } else {
            System.out.println("=== 结论：" + failures + " 项失败 —— 防超卖不成立 ===");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 验证 1：原子扣减
    // ------------------------------------------------------------------

    private static int verifyAtomicDeduct(String url) throws Exception {
        System.out.println();
        System.out.printf("--- 验证 1：%d 个线程抢 %d 个号（原子 UPDATE）---%n", THREADS, SLOTS);
        long scheduleId = seedSchedule(url, SLOTS);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(POOL);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try (Connection c = open(url);
                     PreparedStatement ps = c.prepareStatement(TRY_DEDUCT)) {
                    gate.await();
                    ps.setLong(1, scheduleId);
                    if (ps.executeUpdate() == 1) {
                        ok.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        boolean finished = done.await(180, TimeUnit.SECONDS);
        pool.shutdownNow();

        int remaining = readRemaining(url, scheduleId);
        System.out.printf("  成功: %d   被拒: %d   剩余号源: %d%n", ok.get(), rejected.get(), remaining);

        int bad = 0;
        if (!finished) {
            System.out.println("  [失败] 线程未在超时内完成");
            bad++;
        }
        if (ok.get() != SLOTS) {
            System.out.printf("  [失败] 成功数应为 %d，实际 %d（多了即超卖）%n", SLOTS, ok.get());
            bad++;
        }
        if (remaining != 0) {
            System.out.printf("  [失败] 剩余号源应为 0，实际 %d%n", remaining);
            bad++;
        }
        if (remaining < 0) {
            System.out.println("  [失败] 剩余号源为负数");
            bad++;
        }
        if (bad == 0) {
            System.out.println("  [通过] 恰好 " + SLOTS + " 人抢到，号源恰好为 0，未超卖");
        }

        cleanUpSchedule(url, scheduleId);
        return bad;
    }

    // ------------------------------------------------------------------
    // 验证 2：对照组 —— 证明"先查再改"在真实 MySQL 上确实会超卖
    // ------------------------------------------------------------------

    /**
     * 这一项是<b>反向验证</b>，测的不是项目代码，而是"验证程序本身有没有检测能力"。
     *
     * <p>如果这个程序对错误实现也报"通过"，那它给出的一切绿灯都不可信。
     * 所以刻意跑一遍已知会错的写法，<b>要求它必须查出超卖</b>——
     * 查不出来才叫失败。
     */
    private static int verifyNaiveImplementationOversells(String url) throws Exception {
        System.out.println();
        System.out.printf("--- 验证 2（对照组）：同样的 %d 线程，但用'先查再改'的错误写法 ---%n", THREADS);
        long scheduleId = seedSchedule(url, SLOTS);

        AtomicInteger ok = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(POOL);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try (Connection c = open(url)) {
                    gate.await();
                    int remaining;
                    try (PreparedStatement ps = c.prepareStatement(NAIVE_READ)) {
                        ps.setLong(1, scheduleId);
                        try (ResultSet rs = ps.executeQuery()) {
                            remaining = rs.next() ? rs.getInt(1) : 0;
                        }
                    }
                    if (remaining > 0) {
                        try (PreparedStatement ps = c.prepareStatement(NAIVE_WRITE)) {
                            ps.setLong(1, scheduleId);
                            if (ps.executeUpdate() == 1) {
                                ok.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 忽略
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        done.await(180, TimeUnit.SECONDS);
        pool.shutdownNow();

        int remaining = readRemaining(url, scheduleId);
        System.out.printf("  成功: %d   剩余号源: %d%n", ok.get(), remaining);

        int bad = 0;
        if (ok.get() <= SLOTS) {
            System.out.printf("  [失败] 错误写法居然没超卖（成功 %d，应大于 %d）——"
                    + "说明这个验证程序检测不出超卖，它的绿灯不可信%n", ok.get(), SLOTS);
            bad++;
        } else {
            System.out.printf("  [符合预期] 错误写法超卖了：%d > %d。"
                    + "这反证了验证程序确实能发现问题%n", ok.get(), SLOTS);
        }
        if (remaining >= 0) {
            System.out.printf("  [符合预期] 号源被扣成 %d（负数即超卖的证据）%n", remaining);
        }

        cleanUpSchedule(url, scheduleId);
        return bad;
    }

    // ------------------------------------------------------------------
    // 验证 3：归还号源的上界
    // ------------------------------------------------------------------

    private static int verifyReturnUpperBound(String url) throws Exception {
        System.out.println();
        System.out.println("--- 验证 3：归还号源不能超过总号源（重复取消的防线）---");
        long scheduleId = seedSchedule(url, SLOTS);

        String returnSql = "UPDATE schedule SET remaining_slots = remaining_slots + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND remaining_slots < total_slots";

        // 扣 5 个 -> 剩 15
        try (Connection c = open(url); PreparedStatement ps = c.prepareStatement(TRY_DEDUCT)) {
            for (int i = 0; i < 5; i++) {
                ps.setLong(1, scheduleId);
                ps.executeUpdate();
            }
        }
        int afterDeduct = readRemaining(url, scheduleId);

        // 归还 20 次，但只有 5 次应当成功
        int returned = 0;
        try (Connection c = open(url); PreparedStatement ps = c.prepareStatement(returnSql)) {
            for (int i = 0; i < 20; i++) {
                ps.setLong(1, scheduleId);
                returned += ps.executeUpdate();
            }
        }
        int finalRemaining = readRemaining(url, scheduleId);
        System.out.printf("  扣减后: %d   归还成功次数: %d   最终: %d（总号源 %d）%n",
                afterDeduct, returned, finalRemaining, SLOTS);

        int bad = 0;
        if (returned != 5) {
            System.out.printf("  [失败] 归还成功次数应为 5，实际 %d%n", returned);
            bad++;
        }
        if (finalRemaining != SLOTS) {
            System.out.printf("  [失败] 最终号源应恰好回到 %d，实际 %d（超出即上界判断失效）%n", SLOTS, finalRemaining);
            bad++;
        }
        if (finalRemaining > SLOTS) {
            System.out.println("  [失败] 号源超过总数——重复取消会把号源加超");
            bad++;
        }
        if (bad == 0) {
            System.out.println("  [通过] 归还带上界判断，号源恰好回到 " + SLOTS + "，不会超过");
        }

        cleanUpSchedule(url, scheduleId);
        return bad;
    }

    // ------------------------------------------------------------------
    // 数据库辅助
    // ------------------------------------------------------------------

    private static String jdbcUrl(String db) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private static Connection open(String url) throws Exception {
        return DriverManager.getConnection(url, USER, PASS);
    }

    private static void createDatabaseIfAbsent() throws Exception {
        String url = "jdbc:mysql://" + HOST + ":" + PORT
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";
        try (Connection c = open(url); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS " + CONC_DB
                    + " DEFAULT CHARACTER SET utf8mb4");
        }
        System.out.println("独立验证库已就绪: " + CONC_DB + "（不碰开发库 hospital_appointment）");
    }

    private static void createTableIfAbsent(String url) throws Exception {
        // 与 db/schema.sql 的 schedule 表结构一致（只保留验证需要的列与约束）
        String ddl = "CREATE TABLE IF NOT EXISTS schedule ("
                + "  id BIGINT NOT NULL AUTO_INCREMENT,"
                + "  doctor_id BIGINT NOT NULL,"
                + "  department_id BIGINT NOT NULL,"
                + "  work_date DATE NOT NULL,"
                + "  period VARCHAR(8) NOT NULL,"
                + "  total_slots INT NOT NULL,"
                + "  remaining_slots INT NOT NULL,"
                + "  fee DECIMAL(10,2) NOT NULL DEFAULT 0,"
                + "  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "  PRIMARY KEY (id),"
                + "  UNIQUE KEY uk_schedule_slot (doctor_id, work_date, period)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Connection c = open(url); Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
        }
    }

    private static long seedSchedule(String url, int slots) throws Exception {
        String sql = "INSERT INTO schedule (doctor_id, department_id, work_date, period,"
                + " total_slots, remaining_slots, fee) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = open(url);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            long stamp = System.nanoTime();
            ps.setLong(1, stamp);                       // 用时间戳当 doctor_id，避免撞唯一索引
            ps.setLong(2, 1L);
            ps.setDate(3, java.sql.Date.valueOf(LocalDate.now()));
            ps.setString(4, "AM");
            ps.setInt(5, slots);
            ps.setInt(6, slots);
            ps.setBigDecimal(7, new BigDecimal("50.00"));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static int readRemaining(String url, long scheduleId) throws Exception {
        try (Connection c = open(url);
             PreparedStatement ps = c.prepareStatement("SELECT remaining_slots FROM schedule WHERE id = ?")) {
            ps.setLong(1, scheduleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : Integer.MIN_VALUE;
            }
        }
    }

    private static void cleanUpSchedule(String url, long scheduleId) throws Exception {
        try (Connection c = open(url);
             PreparedStatement ps = c.prepareStatement("DELETE FROM schedule WHERE id = ?")) {
            ps.setLong(1, scheduleId);
            ps.executeUpdate();
        }
    }
}
