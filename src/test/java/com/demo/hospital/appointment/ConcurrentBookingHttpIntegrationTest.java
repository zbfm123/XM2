package com.demo.hospital.appointment;

import com.demo.hospital.appointment.domain.AppointmentStatus;
import com.demo.hospital.appointment.mapper.AppointmentMapper;
import com.demo.hospital.department.domain.Department;
import com.demo.hospital.department.mapper.DepartmentMapper;
import com.demo.hospital.doctor.domain.Doctor;
import com.demo.hospital.doctor.mapper.DoctorMapper;
import com.demo.hospital.schedule.domain.Schedule;
import com.demo.hospital.schedule.mapper.ScheduleMapper;
import com.demo.hospital.support.RedisTestConfig;
import com.demo.hospital.user.domain.SysUser;
import com.demo.hospital.user.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>并发走完整 HTTP 挂号链路</b>（A-03 的第三处证据，也是最接近真实的一处）。
 *
 * <h2>它比 ScheduleConcurrencyTest 多覆盖了什么</h2>
 *
 * {@code ScheduleConcurrencyTest} 直接调 {@code scheduleMapper.tryDeduct}，
 * 验证的是**那一条 SQL 在数据库上是否原子**——这是技术内核，必须验。
 * 但它绕过了整条业务链路：
 *
 * <ul>
 *   <li>Service 层的事务边界（并发下 @Transactional 的行为与单线程不同）</li>
 *   <li>幂等键与 {@code (dedup_key, schedule_id)} 两个唯一索引</li>
 *   <li>"先查幂等键 → 查活跃订单 → 扣号源 → 建订单"这个**多步流程**在并发下的正确性</li>
 *   <li>唯一索引冲突时的补偿（归还号源）</li>
 * </ul>
 *
 * <p><b>单条 SQL 原子 ≠ 整条流程正确。</b> 中间任何一步在并发下出问题，
 * 都表现为"有些人没抢到号，但号源数字又不对"这类难以复现的现象。
 *
 * <h2>场景</h2>
 *
 * 20 个号源，**60 个不同用户**同时抢（每人一个幂等键，各挂一次）。
 * 预期：恰好 20 人成功、40 人被明确拒绝（409 NO_SLOTS_AVAILABLE）、
 * 号源恰好 0、订单恰好 20 条、无一 500。
 *
 * <p>⚠️ 这里用**不同用户**而不是同一用户重复提交——
 * 后者会先被"同一排班不可重复挂号"拦下，测不到号源竞争。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class ConcurrentBookingHttpIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";
    private static final int SLOTS = 20;
    private static final int USERS = 60;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private SysUserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private AppointmentMapper appointmentMapper;

    private final List<String> createdPhones = new ArrayList<>();
    private Long departmentId;
    private Long doctorId;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        createdPhones.clear();

        Department department = new Department();
        department.setCode("CHTTP-" + System.nanoTime());
        department.setName("并发HTTP测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("并发HTTP测试医生");
        doctor.setTitle("主任医师");
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();
    }

    @AfterEach
    void cleanUp() {
        for (String p : createdPhones) {
            SysUser u = userMapper.findByPhone(p);
            if (u != null) {
                appointmentMapper.deleteByUserId(u.getId());
            }
            userMapper.deleteByPhone(p);
        }
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
    @DisplayName("60 个用户并发抢 20 个号：恰好 20 单、号源恰好 0、40 个明确的'已约满'")
    void sixtyUsersCompeteForTwentySlotsThroughHttp() throws Exception {
        Long scheduleId = newSchedule(SLOTS);

        // 先造 60 个用户并各自登录，拿 60 个令牌（登录本身不并发，避免把噪声混进来）
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            String phone = uniquePhone();
            createdPhones.add(phone);
            SysUser u = new SysUser();
            u.setPhone(phone);
            u.setPasswordHash(passwordEncoder.encode(SECRET));
            u.setRealName("并发用户" + i);
            u.setEnabled(true);
            u.setFailedCount(0);
            userMapper.insert(u);
            tokens.add(login(phone));
        }
        assertThat(tokens).hasSize(USERS);

        // 闸门：让 60 个请求尽量同时发出
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<int[]>> futures = new ArrayList<>();

        for (String token : tokens) {
            Callable<int[]> task = () -> {
                gate.await();
                String body = objectMapper.writeValueAsString(Map.of(
                        "scheduleId", scheduleId,
                        "idempotencyKey", UUID.randomUUID().toString()));
                var resp = mockMvc.perform(post("/api/appointments")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse();
                return new int[]{resp.getStatus()};
            };
            futures.add(pool.submit(task));
        }
        gate.countDown();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger serverError = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (Future<int[]> f : futures) {
            int status = f.get(90, TimeUnit.SECONDS)[0];
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            } else if (status >= 500) {
                serverError.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
        }
        pool.shutdownNow();

        int remaining = scheduleMapper.selectById(scheduleId).getRemainingSlots();

        // ① 恰好 20 人成功
        assertThat(ok.get())
                .as("成功数必须恰好等于号源总数（HTTP 链路上也不能超卖）")
                .isEqualTo(SLOTS);

        // ② 其余全部是明确的"已约满"（409），而不是报错
        assertThat(conflict.get())
                .as("没抢到的人应当收到 409（明确告知已约满），而不是 500")
                .isEqualTo(USERS - SLOTS);

        // ③ 绝不能出现 5xx
        assertThat(serverError.get())
                .as("并发下不该有任何服务端错误")
                .isZero();
        assertThat(other.get())
                .as("不该出现预期之外的状态码（如 400/404）")
                .isZero();

        // ④ 号源恰好 0，且不为负
        assertThat(remaining).as("剩余号源必须恰好为 0").isZero();

        // ⑤ 订单恰好 20 条，且状态都是 PENDING_PAYMENT
        long totalOrders = 0;
        for (String p : createdPhones) {
            SysUser u = userMapper.findByPhone(p);
            totalOrders += appointmentMapper.countByUser(u.getId(), null);
        }
        assertThat(totalOrders)
                .as("订单总数必须恰好 20 条 —— 多了是超卖，少了是丢号")
                .isEqualTo(SLOTS);

        // ⑥ 总号源没有被改动
        assertThat(scheduleMapper.selectById(scheduleId).getTotalSlots()).isEqualTo(SLOTS);
    }

    @Test
    @DisplayName("并发抢完后，成功者能查到自己的订单、失败者一条都没有")
    void successfulBookersOwnExactlyOneOrderEach() throws Exception {
        Long scheduleId = newSchedule(5);

        List<String> phones = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String phone = uniquePhone();
            phones.add(phone);
            createdPhones.add(phone);
            SysUser u = new SysUser();
            u.setPhone(phone);
            u.setPasswordHash(passwordEncoder.encode(SECRET));
            u.setRealName("并发用户" + i);
            u.setEnabled(true);
            u.setFailedCount(0);
            userMapper.insert(u);
            tokens.add(login(phone));
        }

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String token : tokens) {
            futures.add(pool.submit(() -> {
                gate.await();
                String body = objectMapper.writeValueAsString(Map.of(
                        "scheduleId", scheduleId,
                        "idempotencyKey", UUID.randomUUID().toString()));
                return mockMvc.perform(post("/api/appointments")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        gate.countDown();

        int success = 0;
        for (Future<Integer> f : futures) {
            if (f.get(60, TimeUnit.SECONDS) == 200) {
                success++;
            }
        }
        pool.shutdownNow();

        assertThat(success).as("5 个号源只能有 5 人成功").isEqualTo(5);

        // 逐个核对：成功者恰好 1 单（状态 PENDING_PAYMENT），失败者 0 单
        int ownersWithOneOrder = 0;
        int ownersWithNoOrder = 0;
        for (String p : phones) {
            SysUser u = userMapper.findByPhone(p);
            long cnt = appointmentMapper.countByUser(u.getId(), null);
            if (cnt == 1) {
                ownersWithOneOrder++;
                assertThat(appointmentMapper.findByNoAndUser(
                        // 用该用户自己的订单核对状态
                        appointmentMapper.findPageByUser(u.getId(), null, 1, 0).get(0).getAppointmentNo(),
                        u.getId()).getStatus())
                        .as("成功者的订单状态应为待支付")
                        .isEqualTo(AppointmentStatus.PENDING_PAYMENT);
            } else if (cnt == 0) {
                ownersWithNoOrder++;
            } else {
                // 出现一个人有多单就是 bug，直接失败并说明
                assertThat(cnt)
                        .as("一个用户在同一排班上不可能有多单（phone=%s）", p)
                        .isLessThanOrEqualTo(1);
            }
        }
        assertThat(ownersWithOneOrder).as("恰好 5 人各持一单").isEqualTo(5);
        assertThat(ownersWithNoOrder).as("其余 10 人一单都没有").isEqualTo(10);
    }

    // ------------------------------------------------------------------

    private Long newSchedule(int slots) {
        Schedule s = new Schedule();
        s.setDoctorId(doctorId);
        s.setDepartmentId(departmentId);
        s.setWorkDate(LocalDate.now().plusDays(1));
        s.setPeriod("AM001");
        s.setTotalSlots(slots);
        s.setRemainingSlots(slots);
        s.setFee(new BigDecimal("50.00"));
        scheduleMapper.insert(s);
        return s.getId();
    }

    private String login(String phone) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "password", SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String uniquePhone() {
        // 用纳秒保证 60 个手机号互不冲突
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        String p = "130" + String.format("%08d", n);
        // 极端情况下仍可能撞（同一纳秒），重试到不冲突为止
        while (userMapper.findByPhone(p) != null) {
            n = Math.abs((n + 1) % 100_000_000L);
            p = "130" + String.format("%08d", n);
        }
        return p;
    }
}
