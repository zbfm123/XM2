package com.demo.hospital.schedule;

import com.demo.hospital.department.domain.Department;
import com.demo.hospital.department.mapper.DepartmentMapper;
import com.demo.hospital.doctor.domain.Doctor;
import com.demo.hospital.doctor.mapper.DoctorMapper;
import com.demo.hospital.schedule.domain.Schedule;
import com.demo.hospital.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 号源扣减的并发正确性 —— 验收 <b>A-03</b>。
 *
 * <h2>这是全项目最重要的一条测试</h2>
 *
 * "1000 个线程抢 20 个号，最终只能有 20 个人抢到、号源恰好为 0、且不为负"——
 * 这一条断言直接对应本项目的立身之本（需求 N-01 / 决策 D-03）。
 * 它写不出来，项目就只是"又一个 CRUD"。
 *
 * <h2>三个刻意的设计决定</h2>
 *
 * <p><b>① 不加 {@code @Transactional}。</b>
 * 这是最关键的一点。测试级事务会把整个测试方法包在<b>一个</b>事务里，
 * 于是多个线程实际上在同一个连接/事务上操作，"并发"被人为地串行化了——
 * 测试通过了，但它验证的是"单线程连续执行 1000 次"，不是并发。
 * <b>那种测试比没有测试更糟：它给出的是虚假的安全感。</b>
 *
 * <p><b>② 用 {@link CountDownLatch} 让所有线程尽量同时起跑。</b>
 * 如果只是把任务丢进线程池，先提交的线程早就跑完了、后面的还没启动，
 * 竞争窗口被人为缩小，测试的"并发压力"远低于它声称的水平。
 * 闸门的目的就是让它们挤在同一瞬间。
 *
 * <p><b>③ 断言的是"恰好"。</b>
 * {@code remaining == 0}、{@code 成功数 == 20}、{@code 无负数}。
 * 只断言"不多于 20"是不够的——那漏掉了"扣多了/少了"这一类错误。
 *
 * <h2>⚠️ 已知取舍：H2 与 MySQL 的行锁语义并不完全相同</h2>
 *
 * 本测试跑在 H2（MODE=MySQL）上，任何人 {@code mvn test} 都能复现，这是它的价值。
 * 但 H2 的锁实现与 InnoDB 不同，<b>因此这条测试还必须在真实 MySQL 上再跑一次</b>，
 * 见 {@code scripts/verify-concurrency-on-mysql.ps1}。
 * 两处都通过，才能说"防超卖成立"。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduleConcurrencyTest {

    /** 抢号线程数。 */
    private static final int THREADS = 1000;

    /** 号源总数。 */
    private static final int SLOTS = 20;

    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private DepartmentMapper departmentMapper;

    private Long departmentId;
    private Long doctorId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        Department department = new Department();
        department.setCode("CONC-" + System.nanoTime());
        department.setName("并发测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("并发测试医生");
        doctor.setTitle("主任医师");
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();

        scheduleId = insertSchedule(SLOTS);
    }

    @AfterEach
    void cleanUp() {
        if (doctorId != null) {
            scheduleMapper.deleteByDoctorId(doctorId);
            doctorMapper.deleteById(doctorId);
        }
        if (departmentId != null) {
            departmentMapper.deleteById(departmentId);
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("A-03 1000 线程抢 20 个号：恰好 20 个成功、剩余恰好 0、且不为负")
    void thousandThreadsCompeteForTwentySlots() throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(64);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();                       // 等闸门：让所有线程挤在同一瞬间
                    int affected = scheduleMapper.tryDeduct(scheduleId);
                    if (affected == 1) {
                        succeeded.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();                               // 开闸
        boolean finished = doneGate.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("所有线程必须在超时前完成").isTrue();

        Schedule after = scheduleMapper.selectById(scheduleId);

        // ① 成功数恰好等于号源总数
        assertThat(succeeded.get())
                .as("抢到的线程数必须恰好等于号源总数——多了就是超卖")
                .isEqualTo(SLOTS);
        assertThat(rejected.get())
                .as("其余线程必须全部被拒绝")
                .isEqualTo(THREADS - SLOTS);

        // ② 剩余号源恰好为 0
        assertThat(after.getRemainingSlots())
                .as("剩余号源必须恰好为 0")
                .isZero();

        // ③ 绝不为负（负数是超卖在数据上的直接证据）
        assertThat(after.getRemainingSlots())
                .as("号源绝不能为负数")
                .isNotNegative();

        // ④ 总号源没有被改动过
        assertThat(after.getTotalSlots()).isEqualTo(SLOTS);
    }

    @Test
    @DisplayName("A-03 补充：号源扣光后继续扣必须全部失败，且数量停在 0 而不是负数")
    void deductingWhenEmptyMustNotGoNegative() {
        // 先老老实实把 20 个号扣完
        for (int i = 0; i < SLOTS; i++) {
            assertThat(scheduleMapper.tryDeduct(scheduleId)).as("第 %d 次扣减应当成功", i + 1).isEqualTo(1);
        }
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isZero();

        // 再扣 50 次，必须全部失败
        int unexpectedSuccess = 0;
        for (int i = 0; i < 50; i++) {
            if (scheduleMapper.tryDeduct(scheduleId) == 1) {
                unexpectedSuccess++;
            }
        }

        assertThat(unexpectedSuccess).as("号源为 0 后不该有任何一次成功").isZero();
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("剩余号源必须停在 0，不能变成负数")
                .isZero();
    }

    @Test
    @DisplayName("对不存在的排班扣减返回 0（受影响行数），不抛异常也不误判为成功")
    void deductingNonExistentScheduleReturnsZero() {
        assertThat(scheduleMapper.tryDeduct(-1L)).isZero();
    }

    // ------------------------------------------------------------------
    // 归还号源（T-008 的核心，但上界判断在这里一并锁定）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("归还不能超过总号源——这是重复取消最容易造成的 bug")
    void returnMustNotExceedTotalSlots() {
        // 扣掉 5 个，剩 15
        for (int i = 0; i < 5; i++) {
            scheduleMapper.tryDeduct(scheduleId);
        }
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(15);

        // 归还 5 次：回到 20
        for (int i = 0; i < 5; i++) {
            assertThat(scheduleMapper.tryReturn(scheduleId)).isEqualTo(1);
        }
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(SLOTS);

        // ⚠️ 关键：已经满了，再归还必须失败，不能变成 21
        for (int i = 0; i < 10; i++) {
            assertThat(scheduleMapper.tryReturn(scheduleId))
                    .as("号源已满时归还必须返回 0，否则号源会超过总数")
                    .isZero();
        }
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("归还带上界判断后，剩余号源最多等于总号源")
                .isEqualTo(SLOTS);
    }

    @Test
    @DisplayName("并发归还也不会超过上界——上界判断在原子语句里同样成立")
    void concurrentReturnMustNotExceedTotal() throws Exception {
        // 扣掉 10 个，剩 10
        for (int i = 0; i < 10; i++) {
            scheduleMapper.tryDeduct(scheduleId);
        }

        int returners = 200;
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(returners);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        for (int i = 0; i < returners; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    if (scheduleMapper.tryReturn(scheduleId) == 1) {
                        ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 归失败不计入
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(ok.get()).as("恰好只能归还 10 个（把号源补回满）").isEqualTo(10);
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("并发归还后剩余号源必须等于总号源，绝不超过")
                .isEqualTo(SLOTS);
    }

    // ------------------------------------------------------------------

    private Long insertSchedule(int slots) {
        Schedule schedule = new Schedule();
        schedule.setDoctorId(doctorId);
        schedule.setDepartmentId(departmentId);
        schedule.setWorkDate(LocalDate.now().plusDays(1));
        schedule.setPeriod("AM");
        schedule.setTotalSlots(slots);
        schedule.setRemainingSlots(slots);
        schedule.setFee(new BigDecimal("50.00"));
        scheduleMapper.insert(schedule);
        return schedule.getId();
    }
}
