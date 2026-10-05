package com.demo.hospital.notification;

import com.demo.hospital.appointment.mapper.AppointmentMapper;
import com.demo.hospital.department.domain.Department;
import com.demo.hospital.department.mapper.DepartmentMapper;
import com.demo.hospital.doctor.domain.Doctor;
import com.demo.hospital.doctor.mapper.DoctorMapper;
import com.demo.hospital.notification.domain.Notification;
import com.demo.hospital.notification.domain.NotificationType;
import com.demo.hospital.notification.mapper.NotificationMapper;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>验证 MQ 打开时，消费者真的会把通知写进库</b>（需要一个真实的 broker）。
 *
 * <h2>为什么必须单独有这个测试（它补的是一个结构性缺口）</h2>
 *
 * 除本类与 {@code PaymentTimeoutIntegrationTest} 之外，**所有集成测试都用
 * {@code test} profile，而那个 profile 里 MQ 是关的**——
 * 也就是说挂号、取消这些业务测试跑的都是 {@code NoopNotifier}。
 *
 * <p>而 <b>dev 环境的默认是 MQ 打开</b>。于是存在一个真实的空白：
 * <blockquote>
 * "下单 -> 通过 RabbitMQ 投递 -> 消费者写通知表" 这条链路，
 * 在业务测试里从来没被走过。
 * </blockquote>
 *
 * <p>这与之前修掉的"假绿"问题**同源**：当时是消费者绑错了队列，
 * 消息没人消费而测试照样绿。根因都是"<b>MQ 相关的验证只在很窄的一条路上做过</b>"。
 *
 * <h2>为什么要探测 broker 而不是直接要求它</h2>
 *
 * 保留 {@code Assumptions} 是有意的：**"干净机器 clone 下来 mvn test 就能全绿"
 * 是 N-04 的硬要求**，不能让一个需要 broker 的测试破坏它。
 * 探测不到就跳过（报告里显示为 skipped，不是 pass）——
 * **跳过是诚实的，假装通过才是危险的**。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mqtest")
@Import(RedisTestConfig.class)
class NotificationDeliveryIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";

    /** 与 application-mqtest.yml 一致；必须与 QueueNameConfig 读到的值相同。 */
    private static final String TEST_NOTIFY_QUEUE = "appointment.notify.queue.test";
    private static final String TEST_DELAY_QUEUE = "appointment.delay.queue.test";
    private static final String TEST_CANCEL_QUEUE = "appointment.cancel.queue.test";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RabbitAdmin rabbitAdmin;

    @Autowired private SysUserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private NotificationMapper notificationMapper;

    private String phone;
    private Long departmentId;
    private Long doctorId;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        Assumptions.assumeTrue(brokerReachable(),
                "RabbitMQ 不可达，跳过（N-04 要求没有 broker 时 mvn test 仍能全绿）");

        phone = uniquePhone();
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(SECRET));
        user.setRealName("通知投递测试");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department department = new Department();
        department.setCode("NOTIF-" + System.nanoTime());
        department.setName("通知投递测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("通知投递测试医生");
        doctor.setTitle("主任医师");
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();
        // ⚠️ 清理队列放在 **@BeforeEach**，不是只在 @AfterEach。
        //
        // 原因：TTL 对齐到 2 秒之后，延迟消息会在**测试类结束后的间隙**里
        // 死信转发（测试上下文已销毁、消费者不在），于是它滞留在队列里。
        // @AfterEach 已经跑完了，挡不住这种"测试结束后才到达"的消息。
        //
        // 放在 @BeforeEach 才是可靠的：**保证每次开始都是干净状态**，
        // 而"上轮残留"与"本轮结束后的残留"都被下一次开始时清掉。
        // 这也让失败重跑不会受到上一轮残留消息的干扰。
        purgeTestQueues();
    }

    @AfterEach
    void cleanUp() {
        if (phone != null) {
            SysUser u = userMapper.findByPhone(phone);
            if (u != null) {
                appointmentMapper.deleteByUserId(u.getId());
            }
            userMapper.deleteByPhone(phone);
        }
        if (doctorId != null) {
            scheduleMapper.deleteByDoctorId(doctorId);
            doctorMapper.deleteById(doctorId);
        }
        if (departmentId != null) {
            departmentMapper.deleteById(departmentId);
        }

        // ⚠️ 还要清掉队列里的消息。
        //
        // 本类用的是与 PaymentTimeoutIntegrationTest 相同的 *.test 队列，
        // 而消费者只在测试上下文存活期间存在。**不清就会永久堆积**——
        // 上一个类清了队列，这个类又把消息留下，下一个类之前都没人清。
        //
        // 为什么是 purge 而不是 delete：见 PaymentTimeoutIntegrationTest 上的说明——
        // 删除队列会制造"删除 → 重新声明"的窗口，此时投递的消息
        // 路由不到队列、被静默丢弃（那个坑已经踩过一次）。
        purgeTestQueues();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("挂号 -> 经 MQ -> NotificationConsumer 写出 BOOKED 通知（真实链路）")
    void bookingDeliversBookedNotificationThroughMq() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();

        String res = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId,
                                "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appointmentNo = objectMapper.readTree(res).get("appointmentNo").asText();

        // ⚠️ 必须**轮询等待**，不能下单后立刻断言。
        //    投递是异步的：下单接口返回时，消息可能还在 broker 里。
        //    写成同步断言会随机失败，然后被人当成"flaky"忽略掉——
        //    而这恰恰是"MQ 相关验证最容易被做假"的地方。
        Notification n = waitForNotification(appointmentNo, NotificationType.BOOKED);

        assertThat(n)
                .as("MQ 打开时，挂号通知必须由消费者写进库（走完 投递->消费->落库 三段）")
                .isNotNull();
        assertThat(n.getContent())
                .as("通知内容必须能让人看懂（含日期与医生）")
                .isNotBlank()
                .contains("2026");
    }

    @Test
    @DisplayName("取消 -> 经 MQ -> 写出 CANCELLED 通知；挂号那条也仍在")
    void cancelDeliversCancelledNotificationThroughMq() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();

        String res = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId,
                                "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appointmentNo = objectMapper.readTree(res).get("appointmentNo").asText();

        mockMvc.perform(post("/api/appointments/{no}/cancel", appointmentNo)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"通知测试\"}"))
                .andExpect(status().isOk());

        assertThat(waitForNotification(appointmentNo, NotificationType.CANCELLED))
                .as("取消也要有一条通知（用户得知道号被取消了）")
                .isNotNull();
        assertThat(waitForNotification(appointmentNo, NotificationType.BOOKED))
                .as("先前的 BOOKED 通知不该被覆盖掉 —— 两条是独立记录")
                .isNotNull();
    }

    // ------------------------------------------------------------------

    /**
     * 轮询等待某类通知出现。
     *
     * @return 找到的通知；超时返回 {@code null}（由调用方断言，失败信息更清楚）
     */
    private Notification waitForNotification(String appointmentNo, NotificationType type)
            throws Exception {
        for (int i = 0; i < 60; i++) {
            var list = notificationMapper.findByAppointmentNo(appointmentNo);
            for (Notification n : list) {
                // ⚠️ 这里必须用**枚举**比较。
                //
                // 第一版写的是 type.equals(n.getType())，而 type 是 String、
                // n.getType() 是 NotificationType 枚举 —— String.equals(枚举)
                // **永远返回 false**。于是测试白等 30 秒后超时失败，
                // 而日志里消费者明明已经打过"通知已记录"。
                //
                // 教训：**断言里类型不匹配时，失败信息会把人引向错误的方向**——
                // "没找到通知"看起来像投递/落库出了问题，
                // 实际只是比较方式写错了。所以断言出错时要先怀疑断言本身。
                if (type == n.getType()) {
                    return n;
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    /** 探测 broker 是否可达：能取到队列属性就说明连上了。 */
    private boolean brokerReachable() {
        try {
            return rabbitAdmin.getQueueProperties(TEST_NOTIFY_QUEUE) != null;
        } catch (Exception e) {
            return false;
        }
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
        return "139" + String.format("%08d", n);
    }
/**
     * 清空测试用的三个队列。
     *
     * <p><b>为什么 before 和 after 都要调</b>：TTL 对齐到 2 秒后，延迟消息可能在
     * <b>测试类结束后的间隙</b>里才死信转发（那时测试上下文已销毁、消费者不在，
     * 于是一直滞留）。{@code @AfterEach} 挡不住这种"测试结束后才到达"的消息。
     * 放在 {@code @BeforeEach} 才可靠——**保证每次开始都是干净状态**，
     * 而且失败重跑不会受上一轮残留消息干扰。
     *
     * <p><b>为什么是 purge 而不是 delete</b>：删除队列会制造
     * "删除 → 重新声明"的窗口，此时投递的消息在 exchange 上找不到绑定、
     * 会被<b>静默丢弃</b>。这个坑已经踩过一次（整个测试类一起跑必定失败）。
     * 清空内容不动实体，是安全的做法。
     */
    private void purgeTestQueues() {
        for (String q : new String[]{TEST_NOTIFY_QUEUE, TEST_DELAY_QUEUE, TEST_CANCEL_QUEUE}) {
            try {
                rabbitAdmin.purgeQueue(q);
            } catch (Exception e) {
                // 队列不存在（例如 broker 不可达、整个测试被跳过）——忽略
            }
        }
    }
}
