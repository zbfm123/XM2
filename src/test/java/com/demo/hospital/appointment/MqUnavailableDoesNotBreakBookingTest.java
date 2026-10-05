package com.demo.hospital.appointment;

import com.demo.hospital.appointment.mapper.AppointmentMapper;
import com.demo.hospital.department.domain.Department;
import com.demo.hospital.department.mapper.DepartmentMapper;
import com.demo.hospital.doctor.domain.Doctor;
import com.demo.hospital.doctor.mapper.DoctorMapper;
import com.demo.hospital.notification.mapper.NotificationMapper;
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
 * <b>验收 A-07：MQ 不可用时挂号仍然成功。</b>
 *
 * <h2>为什么这是"绝不砍"的三项之一</h2>
 *
 * 它对应需求 F-04-3 / N-06，也对应决策 D-06。它要证明的不是某个功能能用，
 * 而是一条<b>工程纪律</b>：
 *
 * <blockquote>
 * 外部依赖的失败，不应该否定已经完成的业务动作。
 * </blockquote>
 *
 * <p>很多人做 MQ 会把"发消息"和"主事务"绑在一起，结果 MQ 一抖动用户就挂不上号——
 * 而用户根本不在乎通知发没发出去，他只想挂上号。
 *
 * <p>这与项目 1 的"AI 挂了，规则结论与人工复核流程不受影响"是<b>同一种纪律</b>，
 * 面试时可以把两个项目串起来讲。
 *
 * <h2>怎么构造"MQ 不可用"</h2>
 *
 * 用一个<b>所有方法都抛异常</b>的 {@link ThrowingNotifierTestConfig} 替换真正的通知实现
 * （见那个类的注释：为什么不用"把 broker 停掉"的方式）。
 *
 * <p>这是<b>最坏情况</b>：不是"投递失败返回 false"，而是异常直接冒出来。
 * 最坏情况都能扛住，其它较轻的故障形态自然也能。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({RedisTestConfig.class, ThrowingNotifierTestConfig.class})
class MqUnavailableDoesNotBreakBookingTest {

    private static final String SECRET = "unit-test-placeholder-only";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private SysUserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private NotificationMapper notificationMapper;

    private String phone;
    private final java.util.List<String> extraPhones = new java.util.ArrayList<>();
    private Long departmentId;
    private Long doctorId;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        ThrowingNotifierTestConfig.reset();

        phone = uniquePhone();
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(SECRET));
        user.setRealName("MQ 故障测试患者");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department department = new Department();
        department.setCode("MQFAIL-" + System.nanoTime());
        department.setName("MQ故障测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("MQ故障测试医生");
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
        // 乙（约满场景造出来的）也要清掉，否则开发库会积累测试数据
        for (String p : extraPhones) {
            SysUser other = userMapper.findByPhone(p);
            if (other != null) {
                appointmentMapper.deleteByUserId(other.getId());
            }
            userMapper.deleteByPhone(p);
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("A-07 通知投递抛异常时，挂号依然成功：订单建立、号源扣减、状态正常")
    void bookingSucceedsWhenNotifierThrows() throws Exception {
        Long scheduleId = newSchedule(5, "AM001");
        String token = login();

        String body = book(token, scheduleId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentNo").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn().getResponse().getContentAsString();

        String appointmentNo = objectMapper.readTree(body).get("appointmentNo").asText();

        // ① 订单确实建了
        SysUser user = userMapper.findByPhone(phone);
        assertThat(appointmentMapper.findByNoAndUser(appointmentNo, user.getId()))
                .as("MQ 故障不能阻止订单建立")
                .isNotNull();

        // ② 号源确实扣了（这是"挂号真的成功了"的硬证据）
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("MQ 故障不能阻止号源扣减")
                .isEqualTo(4);

        // ③ 业务确实尝试过投递通知 —— 区分"没尝试"与"尝试了但失败"
        assertThat(ThrowingNotifierTestConfig.callCount())
                .as("业务应当尝试过投递通知（只是失败了）")
                .isGreaterThanOrEqualTo(1);

        // ④ 没有通知记录 —— 因为投递真的失败了。
        //    这一条很重要：它证明"A-07 通过"不是因为通知其实发成功了，
        //    而是因为**投递确实失败、但业务照常**。
        assertThat(notificationMapper.findByAppointmentNo(appointmentNo))
                .as("通知投递失败，因此不该有通知记录")
                .isEmpty();
    }

    @Test
    @DisplayName("A-07 取消挂号也不受 MQ 故障影响：状态转为 CANCELLED 且号源归还")
    void cancelSucceedsWhenNotifierThrows() throws Exception {
        Long scheduleId = newSchedule(5, "AM001");
        String token = login();

        String bookBody = book(token, scheduleId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appointmentNo = objectMapper.readTree(bookBody).get("appointmentNo").asText();

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(4);

        mockMvc.perform(post("/api/appointments/{no}/cancel", appointmentNo)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"MQ 故障下取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("MQ 故障不能阻止号源归还")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("A-07 MQ 全挂时，连续多单全部成功——不是'恰好第一单能过'")
    void multipleBookingsAllSucceedWhenNotifierThrows() throws Exception {
        // ⚠️ 每单必须用**不同的排班**：同一患者对同一排班本来就不允许挂两次
        //    （ALREADY_BOOKED），那会先于号源判断拦下来，测的就不是 MQ 故障了。
        int orders = 5;
        Long[] scheduleIds = new Long[orders];
        for (int i = 0; i < orders; i++) {
            scheduleIds[i] = newSchedule(10, String.format("AM%03d", i + 1));
        }
        String token = login();

        for (Long scheduleId : scheduleIds) {
            book(token, scheduleId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        }

        for (Long scheduleId : scheduleIds) {
            assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                    .as("每单都应扣掉 1 个号源")
                    .isEqualTo(9);
        }
        assertThat(ThrowingNotifierTestConfig.callCount())
                .as("每一单都尝试过投递（每单 2 次：通知 + 超时调度）")
                .isGreaterThanOrEqualTo(orders * 2);
    }

    @Test
    @DisplayName("A-07 号源约满时的拒绝理由仍然是 NO_SLOTS_AVAILABLE（MQ 故障没有掩盖真实原因）")
    void realBusinessErrorStillSurfacesWhenMqIsDown() throws Exception {
        Long scheduleId = newSchedule(1, "AM010");
        String tokenA = login();

        // 甲占掉唯一一个号
        book(tokenA, scheduleId).andExpect(status().isOk());

        // 乙来挂同一个排班：必须因"约满"被拒，而不是因为 MQ 故障报 500，
        // 也不能因为"乙也挂过"（他没挂过）而报 ALREADY_BOOKED。
        String phoneB = createUser(uniquePhone("135"), "MQ故障测试患者乙");
        extraPhones.add(phoneB);
        String tokenB = loginAs(phoneB);
        book(tokenB, scheduleId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SLOTS_AVAILABLE"));
    }

    // ------------------------------------------------------------------

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

    private Long newSchedule(int slots, String period) {
        Schedule s = new Schedule();
        s.setDoctorId(doctorId);
        s.setDepartmentId(departmentId);
        s.setWorkDate(LocalDate.now().plusDays(1));
        s.setPeriod(period);
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

    /** 造一个测试用户并返回手机号。 */
    private String createUser(String p, String name) {
        SysUser u = new SysUser();
        u.setPhone(p);
        u.setPasswordHash(passwordEncoder.encode(SECRET));
        u.setRealName(name);
        u.setEnabled(true);
        u.setFailedCount(0);
        userMapper.insert(u);
        return p;
    }

    /** 用指定手机号登录，返回令牌。 */
    private String loginAs(String p) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", p, "password", SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String uniquePhone() {
        return uniquePhone("136");
    }

    private String uniquePhone(String prefix) {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return prefix + String.format("%08d", n);
    }
}
