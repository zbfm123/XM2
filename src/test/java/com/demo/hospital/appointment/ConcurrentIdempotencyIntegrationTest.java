package com.demo.hospital.appointment;

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
 * 幂等的<b>并发</b>边界：同一个幂等键同时到达多次。
 *
 * <h2>为什么这条路径必须单独测</h2>
 *
 * 已有测试覆盖的是**串行**重复提交（第一次成功，之后同键直接命中"幂等快路径"）。
 * 但真实的重试往往是**并发**的：用户双击、或者前端超时后重发，
 * 两个请求几乎同时到达，**都会走到"快路径查不到"这一分支**，
 * 然后一起去扣号源、一起去插入订单。
 *
 * <p>代码里为此专门写了兜底（`catch (DuplicateKeyException)` 后归还号源、
 * 返回先到者的订单）。<b>但这条路径只用单线程测过——等于没测。</b>
 * 并发代码里最典型的错误就是"单线程跑得好好的"。
 *
 * <h2>三条断言，最后一条最关键</h2>
 *
 * <ol>
 *   <li>只产生 <b>1 条</b>订单——唯一索引守住了</li>
 *   <li>所有成功响应返回<b>同一个单号</b>——对用户就是"重试拿到了原来那一单"</li>
 *   <li><b>号源只扣 1 个</b>——这条最关键：失败的那些请求必须把多扣的号源还回去。
 *       漏了归还，号源就会凭空少一个，表现为"系统性地丢号"，
 *       而且**不会有任何报错**</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class ConcurrentIdempotencyIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";
    private static final int CONCURRENT = 8;

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
        user.setRealName("并发幂等测试");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department department = new Department();
        department.setCode("CIDEM-" + System.nanoTime());
        department.setName("并发幂等测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("并发幂等测试医生");
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
    @DisplayName("同一个幂等键并发到达 8 次：只建 1 单、单号相同、**号源只扣 1 个**")
    void sameKeyConcurrentlyOnlyDeductsOneSlot() throws Exception {
        Long scheduleId = newSchedule(10);
        String token = login();
        final String key = UUID.randomUUID().toString();

        int before = scheduleMapper.selectById(scheduleId).getRemainingSlots();

        // 用闸门让 8 个请求尽量同时发出
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        List<Future<String>> futures = new ArrayList<>();

        Callable<String> task = () -> {
            gate.await();
            String body = objectMapper.writeValueAsString(
                    Map.of("scheduleId", scheduleId, "idempotencyKey", key));
            try {
                String res = mockMvc.perform(post("/api/appointments")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getContentAsString();
                return res;
            } catch (Exception e) {
                return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        };

        for (int i = 0; i < CONCURRENT; i++) {
            futures.add(pool.submit(task));
        }
        gate.countDown();

        List<String> orderNos = new ArrayList<>();
        int failures = 0;
        for (Future<String> f : futures) {
            String res = f.get(60, TimeUnit.SECONDS);
            var node = objectMapper.readTree(res);
            if (node.has("appointmentNo")) {
                orderNos.add(node.get("appointmentNo").asText());
            } else {
                failures++;
            }
        }
        pool.shutdownNow();

        int after = scheduleMapper.selectById(scheduleId).getRemainingSlots();

        // ① 只建了一条订单
        SysUser u = userMapper.findByPhone(phone);
        assertThat(appointmentMapper.countByUser(u.getId(), null))
                .as("同一个幂等键并发到达，只能建一条订单（唯一索引守住）")
                .isEqualTo(1);

        // ② 所有成功的响应都是同一个单号
        assertThat(orderNames(orderNos)).as("成功响应必须返回同一个单号").hasSize(1);

        // ③ ⚠️ 最关键：号源只扣 1 个
        assertThat(after)
                .as("号源只能扣 1 个 —— 失败的那些请求**必须把多扣的号源还回去**，"
                        + "否则会凭空丢号且没有任何报错")
                .isEqualTo(before - 1);

        assertThat(failures)
                .as("并发重复提交不该以 500 收场（要么成功、要么明确的业务错误）")
                .isZero();

        assertThat(orderNos).as("8 个请求都应当拿到订单号（幂等重放）").hasSize(CONCURRENT);
    }

    @Test
    @DisplayName("不同幂等键但同一排班并发到达：只建 1 单、号源只扣 1 个")
    void differentKeysSameScheduleConcurrentlyDeductsOneSlot() throws Exception {
        Long scheduleId = newSchedule(10);
        String token = login();

        int before = scheduleMapper.selectById(scheduleId).getRemainingSlots();

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        List<Future<String>> futures = new ArrayList<>();

        Callable<String> task = () -> {
            gate.await();
            // 每个请求带**不同**的幂等键 —— 这不是"重复提交"，是"想挂两个号"
            String body = objectMapper.writeValueAsString(
                    Map.of("scheduleId", scheduleId, "idempotencyKey", UUID.randomUUID().toString()));
            try {
                return mockMvc.perform(post("/api/appointments")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        };

        for (int i = 0; i < CONCURRENT; i++) {
            futures.add(pool.submit(task));
        }
        gate.countDown();
        for (Future<String> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        int after = scheduleMapper.selectById(scheduleId).getRemainingSlots();

        SysUser u = userMapper.findByPhone(phone);
        assertThat(appointmentMapper.countByUser(u.getId(), null))
                .as("同一人同一排班即使键不同，也只能有一单（活跃订单唯一索引守住）")
                .isEqualTo(1);

        assertThat(after)
                .as("号源只能扣 1 个 —— 被拒绝的那些请求也要把号源还回去")
                .isEqualTo(before - 1);
    }

    // ------------------------------------------------------------------

    private java.util.Set<String> orderNames(List<String> list) {
        return new java.util.HashSet<>(list);
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
        return "132" + String.format("%08d", n);
    }
}
