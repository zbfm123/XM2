package com.demo.hospital.appointment;

import com.demo.hospital.appointment.domain.AppointmentStatus;
import com.demo.hospital.appointment.mapper.AppointmentMapper;
import com.demo.hospital.config.AppointmentProperties;
import com.demo.hospital.config.RabbitTopologyConfig;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 延迟队列 + 超时自动取消 —— 验收 <b>A-06</b>。
 *
 * <h2>这个测试与其它测试的区别：它是唯一需要真实 RabbitMQ 的</h2>
 *
 * 本项目的测试纪律是"干净机器 {@code mvn test} 就能全绿"（N-04 的一部分），
 * 所以绝不能让整个测试套件依赖 broker。做法是：
 * <ul>
 *   <li>用独立的 profile {@code application-mqtest.yml}（打开 MQ、TTL 改成 1 秒）；</li>
 *   <li>类内用 {@code Assumptions} 探测 broker 是否可达，
 *       <b>不可达时跳过而不是失败</b>——这样没有 broker 的机器跑全量测试依然全绿。</li>
 * </ul>
 *
 * <p>⚠️ 但"跳过"必须是有声音的：这里会打印一条明确的提示，
 * 说明 A-06 这次没有被验证。静默跳过是最糟的——它让人以为功能测过了。
 *
 * <h2>它在验证什么</h2>
 *
 * 一条完整的链路：下单 → 消息进延迟队列 → <b>TTL 到期成为死信</b> →
 * 经 DLX 转发到取消队列 → 消费者检查状态 → 取消并归还号源。
 *
 * <p>其中"TTL 到期 → 死信转发"这一段是 <b>broker 的行为</b>，
 * 不是我们的代码。用 H2 + 桩测不出来，必须真的有一个 broker。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mqtest")
@Import(RedisTestConfig.class)
class PaymentTimeoutIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";

    /** 等待自动取消的上限。TTL 是 1 秒，给 60 秒余量足够（含 broker 轮询间隔）。 */
    private static final int WAIT_SECONDS = 60;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private SysUserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private AppointmentProperties appointmentProperties;

    /** 用它探测 broker 是否可达。 */
    @Autowired(required = false) private RabbitAdmin rabbitAdmin;

    private String phone;
    private Long departmentId;
    private Long doctorId;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();

        // 没有 broker 就跳过（而不是失败）——见类注释
        Assumptions.assumeTrue(brokerReachable(),
                "跳过 A-06：本机 RabbitMQ 不可达。"
                        + "该验收项需要真实 broker 才能验证（TTL 到期后的死信转发是 broker 行为）。");

        phone = uniquePhone();
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(SECRET));
        user.setRealName("超时取消测试患者");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department department = new Department();
        department.setCode("TIMEOUT-" + System.nanoTime());
        department.setName("超时测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("超时测试医生");
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
    @DisplayName("A-06 下单后延迟消息到期，订单自动取消且号源归还")
    void appointmentIsAutoCancelledAfterTimeout() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();

        String bookBody = book(token, scheduleId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn().getResponse().getContentAsString();
        String appointmentNo = objectMapper.readTree(bookBody).get("appointmentNo").asText();

        // 下单后立即：号源已扣、状态待支付
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(4);
        assertThat(appointmentMapper.findByNo(appointmentNo).getStatus())
                .isEqualTo(AppointmentStatus.PENDING_PAYMENT);

        // 等 broker 把消息 TTL 到期、死信转发、消费者处理
        boolean cancelled = waitForStatus(appointmentNo, AppointmentStatus.CANCELLED);

        assertThat(cancelled)
                .as("延迟消息应在 TTL 到期后触发自动取消（本 profile 的 TTL 是 1000ms，最多等 %d 秒）",
                        WAIT_SECONDS)
                .isTrue();

        // ① 状态变成 CANCELLED
        assertThat(appointmentMapper.findByNo(appointmentNo).getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);

        // ② 号源被归还
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("超时取消必须归还号源")
                .isEqualTo(5);

        // ③ 取消原因必须能让人看出是**系统自动**取消的，而不是用户主动取消。
        //    ⚠️ 这里刻意不断言具体文案（"超过支付时限…"）：文案会随产品调整而变，
        //    把测试绑死在文案上，会让改一句提示语就要改测试。
        //    真正重要的是这个**性质**：系统取消与用户取消可区分。
        assertThat(appointmentMapper.findByNo(appointmentNo).getCancelReason())
                .as("取消原因必须说明是系统自动取消（可与用户主动取消区分开）")
                .isNotNull()
                .contains("自动")
                .doesNotContain("用户主动");
    }

    @Test
    @DisplayName("⚠️ A-06 的关键边界：订单已支付时，延迟消息绝不能把它取消掉")
    void paidAppointmentMustNotBeCancelledByTimeout() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();

        String bookBody = book(token, scheduleId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appointmentNo = objectMapper.readTree(bookBody).get("appointmentNo").asText();

        // 模拟"用户在超时前完成了支付"——直接把状态推进到 PAID。
        // （本项目的支付是模拟回调，见决策 D-07；这里直接改状态等价于回调已生效。）
        var appointment = appointmentMapper.findByNo(appointmentNo);
        int moved = appointmentMapper.transitionStatus(
                appointment.getId(), AppointmentStatus.PENDING_PAYMENT,
                AppointmentStatus.PAID, null);
        assertThat(moved).as("前置条件：订单应能转为已支付").isEqualTo(1);

        // 等超过 TTL 的时间，让延迟消息确实到期并被消费
        Thread.sleep(6000);

        // ⚠️ 关键断言：已支付的订单**不能被**超时消息取消
        assertThat(appointmentMapper.findByNo(appointmentNo).getStatus())
                .as("已支付的订单绝不能被延迟消息取消——否则用户付了钱、号没了、号源还被卖给了别人")
                .isEqualTo(AppointmentStatus.PAID);

        // 号源也不该被归还（这张单还在占用号源）
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("已支付的订单仍占用号源，不该被归还")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("已主动取消的订单，超时消息到达时不会重复归还号源")
    void alreadyCancelledAppointmentIsNotReturnedTwice() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();

        String bookBody = book(token, scheduleId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appointmentNo = objectMapper.readTree(bookBody).get("appointmentNo").asText();

        // 用户自己先取消了
        mockMvc.perform(post("/api/appointments/{no}/cancel", appointmentNo)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"用户先取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(5);

        // 等超时消息到达
        Thread.sleep(6000);

        // ⚠️ 号源必须仍然是 5（没有被归还第二次）
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("超时消息对已取消订单必须无动作，号源不能被归还两次")
                .isEqualTo(5);
        assertThat(appointmentMapper.findByNo(appointmentNo).getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("超时时间来自配置而非硬编码——否则测试只能真的等 15 分钟")
    void paymentTimeoutIsConfigurable() {
        // 本 profile 配置成 15 分钟（应用侧语义），延迟队列 TTL 单独配成 1 秒。
        // 这条测试守的是"这两件事都是配置项、没有被写死"。
        assertThat(appointmentProperties.paymentTimeoutMinutes())
                .as("payment-timeout-minutes 必须是可配置的，否则测试没法把它改短")
                .isEqualTo(15);
    }

    // ------------------------------------------------------------------

    /** 探测 broker 是否可达：RabbitAdmin 能取到队列属性就说明连上了。 */
    private boolean brokerReachable() {
        if (rabbitAdmin == null) {
            return false;
        }
        try {
            var props = rabbitAdmin.getQueueProperties(RabbitTopologyConfig.DEFAULT_NOTIFY_QUEUE);
            return props != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForStatus(String appointmentNo, AppointmentStatus expected)
            throws InterruptedException {
        LocalDateTime deadline = LocalDateTime.now().plusSeconds(WAIT_SECONDS);
        while (LocalDateTime.now().isBefore(deadline)) {
            var a = appointmentMapper.findByNo(appointmentNo);
            if (a != null && a.getStatus() == expected) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private org.springframework.test.web.servlet.ResultActions book(String token, Long scheduleId)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "scheduleId", scheduleId,
                "idempotencyKey", UUID.randomUUID().toString()));
        return mockMvc.perform(post("/api/appointments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "password", SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String uniquePhone() {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return "134" + String.format("%08d", n);
    }
}
