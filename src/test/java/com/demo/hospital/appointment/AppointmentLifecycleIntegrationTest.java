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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模拟支付与就诊完成（决策 D-07）—— 验证**状态机在接口层真的能走完**。
 *
 * <h2>为什么这个测试是必要的</h2>
 *
 * {@code AppointmentStatusMachineTest} 证明了状态机**枚举内部自洽**：
 * 合法迁移恰好 4 条、不可自环、终态无出边。
 * 但它证明不了"订单真的能沿着状态机走完"——
 * 因为在补上这个端点之前，{@code PAID} 与 {@code COMPLETED}
 * <b>在接口层完全不可达</b>，只有测试手动改库才能到。
 *
 * <p>换句话说：**穷举测试保证了规则的形状，这个测试保证规则真的被用上。**
 * 两者缺一，都会出现"测试全绿但功能走不通"。
 *
 * <p>同样不加 {@code @Transactional}（理由见 docs/PROGRESS.md）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class AppointmentLifecycleIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";

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
    private int seq = 0;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        phone = uniquePhone();
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(SECRET));
        user.setRealName("生命周期测试");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department department = new Department();
        department.setCode("LIFE-" + System.nanoTime());
        department.setName("生命周期测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("生命周期测试医生");
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
    @DisplayName("状态机在接口层能走完全程：PENDING_PAYMENT → PAID → COMPLETED")
    void fullLifecycleIsReachableThroughApi() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();
        String no = book(token, scheduleId);

        // 起点
        assertThat(statusOf(no)).isEqualTo(AppointmentStatus.PENDING_PAYMENT);

        // ① 模拟支付
        mockMvc.perform(post("/api/appointments/{no}/pay", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"模拟支付\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(statusOf(no))
                .as("支付后状态应为 PAID —— 这证明 PAID 在接口层可达")
                .isEqualTo(AppointmentStatus.PAID);

        // ② 就诊完成
        mockMvc.perform(post("/api/appointments/{no}/complete", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(statusOf(no))
                .as("COMPLETED 同样必须在接口层可达")
                .isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    @DisplayName("已支付的订单不能取消（退号涉及退费，本项目不做）")
    void paidAppointmentCannotBeCancelled() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();
        String no = book(token, scheduleId);

        pay(token, no).andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/{no}/cancel", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(statusOf(no)).isEqualTo(AppointmentStatus.PAID);
    }

    @Test
    @DisplayName("终态不可被回调复活：COMPLETED 不能再支付、不能再取消")
    void terminalStateCannotBeRevived() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();
        String no = book(token, scheduleId);
        pay(token, no).andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/{no}/complete", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // 再支付一次：COMPLETED → PAID 是状态机的非法迁移
        pay(token, no)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        // 取消：终态无出边
        mockMvc.perform(post("/api/appointments/{no}/cancel", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(statusOf(no))
                .as("终态必须保持不变")
                .isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    @DisplayName("不能跳步：未支付的订单不能直接标记为已完成")
    void cannotSkipPayment() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();
        String no = book(token, scheduleId);

        mockMvc.perform(post("/api/appointments/{no}/complete", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        assertThat(statusOf(no)).isEqualTo(AppointmentStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("重复支付回调是幂等的（真实回调会被平台重投）")
    void repeatedPaymentCallbackIsIdempotent() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();
        String no = book(token, scheduleId);

        pay(token, no).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        // 第二次：目标状态已达成，应按幂等成功返回（replayed=true），而不是报错
        pay(token, no).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        assertThat(statusOf(no)).isEqualTo(AppointmentStatus.PAID);
    }

    @Test
    @DisplayName("无法操作别人的订单：用别人的单号支付返回 404")
    void cannotPayOthersAppointment() throws Exception {
        Long scheduleId = newSchedule(5);
        String tokenA = login();
        String noA = book(tokenA, scheduleId);

        // 造第二个用户来试着支付甲的单
        String phoneB = uniquePhone();
        SysUser userB = new SysUser();
        userB.setPhone(phoneB);
        userB.setPasswordHash(passwordEncoder.encode(SECRET));
        userB.setRealName("另一人");
        userB.setEnabled(true);
        userB.setFailedCount(0);
        userMapper.insert(userB);
        try {
            String tokenB = loginAs(phoneB);
            pay(tokenB, noA)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));

            assertThat(statusOf(noA))
                    .as("越权支付不能生效")
                    .isEqualTo(AppointmentStatus.PENDING_PAYMENT);
        } finally {
            appointmentMapper.deleteByUserId(userB.getId());
            userMapper.deleteByPhone(phoneB);
        }
    }

    @Test
    @DisplayName("支付与完成端点都需要登录")
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/appointments/AP-X/pay")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/appointments/AP-X/complete")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("支付不影响号源：已支付的订单仍占用号源，不会被归还")
    void paymentDoesNotReturnSlots() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();
        String no = book(token, scheduleId);
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(4);

        pay(token, no).andExpect(status().isOk());

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("支付不归还号源 —— 这张单还在正常占用一个号")
                .isEqualTo(4);
    }

    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions pay(String token, String no) throws Exception {
        return mockMvc.perform(post("/api/appointments/{no}/pay", no)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private AppointmentStatus statusOf(String appointmentNo) {
        SysUser u = userMapper.findByPhone(phone);
        return appointmentMapper.findByNoAndUser(appointmentNo, u.getId()).getStatus();
    }

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
        s.setPeriod(String.format("AM%03d", ++seq));
        s.setTotalSlots(slots);
        s.setRemainingSlots(slots);
        s.setFee(new BigDecimal("50.00"));
        scheduleMapper.insert(s);
        return s.getId();
    }

    private String login() throws Exception {
        return loginAs(phone);
    }

    private String loginAs(String p) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", p, "password", SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String uniquePhone() {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return "133" + String.format("%08d", n);
    }
}
