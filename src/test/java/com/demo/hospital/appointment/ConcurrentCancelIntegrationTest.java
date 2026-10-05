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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 取消的并发竞态：**用户主动取消**与**超时自动取消**同时发生。
 *
 * <h2>这是在防什么</h2>
 *
 * 两条取消路径最终都会调用 {@code transitionStatus(PENDING_PAYMENT → CANCELLED)}
 * 然后归还号源。如果两条路径都"成功地"归还了号源，号源就会**被归还两次**——
 * 而号源多出来比少掉更危险：它意味着数据库里的数字已经不可信，
 * 而"不超卖"这个保证整个建立在"`remaining_slots` 恰好等于剩余量"之上。
 *
 * <p>防线有两层，各自独立：
 * <ol>
 *   <li><b>状态迁移是带起始状态的原子 UPDATE</b>——
 *       两条路径只有一个能把状态从 {@code PENDING_PAYMENT} 改走，
 *       另一个受影响行数为 0，于是<b>不会去归还号源</b></li>
 *   <li><b>归还语句自带 {@code remaining < total} 上界</b>——
 *       即使第一层被绕过，也不可能把号源加超总数</li>
 * </ol>
 *
 * <p>本测试同时验证这两层：断言"恰好归还一次"（第一层生效），
 * 以及"号源不超过总数"（第二层兜底）。
 *
 * <p>⚠️ 与其它测试一样不加 {@code @Transactional}：测试级事务会把并发串行化，
 * 那样这条测试就完全失去意义了。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class ConcurrentCancelIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";
    private static final int CONCURRENT_CANCELS = 6;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private SysUserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private AppointmentMapper appointmentMapper;

    private String phone;
    private Long departmentId;
    private Long doctorId;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        phone = uniquePhone();
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(SECRET));
        user.setRealName("并发取消测试");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department department = new Department();
        department.setCode("CCAN-" + System.nanoTime());
        department.setName("并发取消测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("并发取消测试医生");
        doctor.setTitle("主任医师");
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();
    }

    @AfterEach
    void cleanUp() {
        SysUser u = userMapper.findByPhone(phone);
        if (u != null) {
            appointmentMapper.deleteByUserId(u.getId());
        }
        if (doctorId != null) {
            scheduleMapper.deleteByDoctorId(doctorId);
            doctorMapper.deleteById(doctorId);
        }
        if (departmentId != null) {
            departmentMapper.deleteById(departmentId);
        }
        userMapper.deleteByPhone(phone);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("用户取消与超时取消同时发生：状态只变一次，号源恰好归还一次")
    void concurrentUserAndTimeoutCancelReturnSlotExactlyOnce() throws Exception {
        Long scheduleId = newSchedule(10);
        String token = login();
        String no = book(token, scheduleId);

        int afterBooking = scheduleMapper.selectById(scheduleId).getRemainingSlots();
        assertThat(afterBooking).as("下单后号源应为 9").isEqualTo(9);

        // 让 N 个"取消"请求尽量同时发出。
        // 一半走 HTTP（模拟用户点取消），一半直接调 service（模拟延迟队列消费者）。
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CANCELS);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_CANCELS; i++) {
            final boolean viaHttp = (i % 2 == 0);
            Callable<String> task = () -> {
                gate.await();
                try {
                    if (viaHttp) {
                        // 用户路径：HTTP 取消。
                        // ⚠️ 用它返回的 replayed 字段判断"这次到底有没有真的执行迁移"：
                        //    replayed=false 才说明状态是我改的（于是归还了号源）；
                        //    replayed=true  说明状态已被别人改过，我只是幂等重放。
                        String res = mockMvc.perform(post("/api/appointments/{no}/cancel", no)
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"reason\":\"用户取消\"}"))
                                .andReturn().getResponse().getContentAsString();
                        boolean reallyCancelled = !objectMapper.readTree(res)
                                .path("replayed").asBoolean(true);
                        return "{\"reallyCancelled\":" + reallyCancelled + "}";
                    }
                    // 超时路径：消费者调用（系统侧，无登录上下文）。
                    // cancelOnTimeout 的返回值就是"这次真的取消了吗"。
                    boolean cancelled = appointmentService.cancelOnTimeout(no, "超时自动取消");
                    return "{\"reallyCancelled\":" + cancelled + "}";
                } catch (Exception e) {
                    return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
                }
            };
            futures.add(pool.submit(task));
        }
        gate.countDown();
        int reallyCancelled = 0;
        for (Future<String> f : futures) {
            String res = f.get(60, TimeUnit.SECONDS);
            if (objectMapper.readTree(res).path("reallyCancelled").asBoolean(false)) {
                reallyCancelled++;
            }
        }
        pool.shutdownNow();

        int afterAll = scheduleMapper.selectById(scheduleId).getRemainingSlots();

        // ① 状态恰好变成 CANCELLED（且只变一次 —— 这是"受影响行数只有一次为 1"的间接证据）
        SysUser u = userMapper.findByPhone(phone);
        var saved = appointmentMapper.findByNoAndUser(no, u.getId());
        assertThat(saved.getStatus())
                .as("并发取消后状态应为 CANCELLED")
                .isEqualTo(AppointmentStatus.CANCELLED);

        // ② ⚠️ 关键且精确：**只有一个**请求真的执行了状态迁移。
        //    这一条比"号源恰好 10"更精确 —— 因为号源有上界判断兜底，
        //    即使两次归还也只显示 10（被截断），仅看号源数字区分不出来。
        //    在这里数"谁真的改了状态"，才能证明**第一层防线（条件 UPDATE）生效**，
        //    而不是只靠第二层（上界）侥幸没超。
        assertThat(reallyCancelled)
                .as("并发取消中只能有一个请求真的把状态从 PENDING_PAYMENT 改走；"
                        + "多于一个说明条件 UPDATE 失效了")
                .isEqualTo(1);

        // ③ 号源恰好归还一次：从 9 回到 10
        assertThat(afterAll)
                .as("号源必须恰好归还一次（回到 10）")
                .isEqualTo(10);

        // ④ 号源不超过总数（上界判断兜底）
        assertThat(afterAll)
                .as("号源绝不能超过总号源")
                .isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("已支付的订单面对超时取消：状态不变、号源不归还")
    void paidOrderIsUntouchedByConcurrentTimeoutCancel() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();
        String no = book(token, scheduleId);
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(4);

        // 支付
        mockMvc.perform(post("/api/appointments/{no}/pay", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // 多个"超时取消"同时到达（真实场景：延迟消息重投）
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> fs = new ArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            fs.add(pool.submit(() -> {
                gate.await();
                return appointmentService.cancelOnTimeout(no, "超时自动取消");
            }));
        }
        gate.countDown();
        for (Future<Boolean> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        SysUser u = userMapper.findByPhone(phone);
        assertThat(appointmentMapper.findByNoAndUser(no, u.getId()).getStatus())
                .as("已支付的订单绝不能被超时取消改动")
                .isEqualTo(AppointmentStatus.PAID);

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("已支付的订单仍占用号源，不该被归还")
                .isEqualTo(4);
    }

    // ------------------------------------------------------------------

    @Autowired private AppointmentService appointmentService;

    private String book(String token, Long scheduleId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "scheduleId", scheduleId,
                "idempotencyKey", UUID.randomUUID().toString()));
        String res = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).get("appointmentNo").asText();
    }

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

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "password", SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String uniquePhone() {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return "131" + String.format("%08d", n);
    }
}
