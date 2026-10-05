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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交挂号与取消挂号 —— 验收 <b>A-04</b>（幂等）与 <b>A-05</b>（归还号源）。
 *
 * <p>与其它集成测试同样的纪律：<b>不加 {@code @Transactional}</b>，
 * 数据自己造、自己清。原因见 docs/PROGRESS.md——测试级事务会掩盖真实事务边界的 bug。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class AppointmentBookingIntegrationTest {

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
    private String phone2;
    private Long departmentId;
    private Long doctorId;

    /** 排班序号：period 列宽 VARCHAR(8)，用它保证同一医生同一天内不撞唯一索引。 */
    private int scheduleSeq = 0;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        phone = uniquePhone("137");
        phone2 = uniquePhone("138");
        createUser(phone, "患者甲");
        createUser(phone2, "患者乙");

        Department department = new Department();
        department.setCode("BOOK-" + System.nanoTime());
        department.setName("挂号测试科室");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("挂号测试医生");
        doctor.setTitle("主任医师");
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();
    }

    @AfterEach
    void cleanUp() {
        if (doctorId != null) {
            // 先删订单（订单引用排班与医生），再删排班与医生
            for (String p : new String[]{phone, phone2}) {
                SysUser u = userMapper.findByPhone(p);
                if (u != null) {
                    appointmentMapper.deleteByUserId(u.getId());
                }
            }
            scheduleMapper.deleteByDoctorId(doctorId);
            doctorMapper.deleteById(doctorId);
        }
        if (departmentId != null) {
            departmentMapper.deleteById(departmentId);
        }
        userMapper.deleteByPhone(phone);
        userMapper.deleteByPhone(phone2);
    }

    // ------------------------------------------------------------------
    // A-04 幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A-04 同键重复提交返回同一订单号，且只建一条订单、号源只扣一次")
    void sameIdempotencyKeyReturnsSameOrder() throws Exception {
        Long scheduleId = newSchedule(10);
        String token = login(phone);
        String key = UUID.randomUUID().toString();

        String first = book(token, scheduleId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn().getResponse().getContentAsString();
        String no1 = objectMapper.readTree(first).get("appointmentNo").asText();

        // 同键再提交 3 次
        for (int i = 0; i < 3; i++) {
            String again = book(token, scheduleId, key)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertThat(objectMapper.readTree(again).get("appointmentNo").asText())
                    .as("同键重放必须返回同一个订单号")
                    .isEqualTo(no1);
        }

        // 只建了一条订单
        assertThat(countOrders(phone)).as("同键重复提交只能建一条订单").isEqualTo(1);
        // 号源只扣了一次
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("同键重复提交不能重复扣号源")
                .isEqualTo(9);
        // 幂等键确实落库了
        assertThat(appointmentMapper.findByIdempotencyKey(key)).isNotNull();
    }

    @Test
    @DisplayName("A-04 不同键正常建新单（幂等只对同一个键生效，不能变成'一次只能挂一个号'）")
    void differentKeysCreateSeparateOrders() throws Exception {
        Long scheduleA = newSchedule(10);
        Long scheduleB = newSchedule(10);
        String token = login(phone);

        String no1 = bookNo(token, scheduleA, UUID.randomUUID().toString());
        String no2 = bookNo(token, scheduleB, UUID.randomUUID().toString());

        assertThat(no2).as("不同排班、不同键应当产生不同订单").isNotEqualTo(no1);
        assertThat(countOrders(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("同一患者对同一排班不可重复挂号（F-03-3，第二道防线）")
    void sameUserSameScheduleIsRejectedWithDifferentKey() throws Exception {
        Long scheduleId = newSchedule(10);
        String token = login(phone);

        book(token, scheduleId, UUID.randomUUID().toString())
                .andExpect(status().isOk());

        // 换一个幂等键再挂同一个排班 —— 这不是"重复提交"，是"想挂两个号"
        book(token, scheduleId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BOOKED"));

        // ⚠️ 关键：被拒绝的那次不能把号源吞掉
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("拒绝重复挂号时，多扣的号源必须归还")
                .isEqualTo(9);
        assertThat(countOrders(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("幂等键必填——不让'防重复提交'变成依赖客户端自觉的可选功能")
    void idempotencyKeyIsRequired() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login(phone);

        String body = objectMapper.writeValueAsString(Map.of("scheduleId", scheduleId));
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.idempotencyKey").exists());
    }

    // ------------------------------------------------------------------
    // 号源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("号源为 0 时返回 409 NO_SLOTS_AVAILABLE（明确报错，不是静默失败）")
    void noSlotsAvailableReturnsClearError() throws Exception {
        Long scheduleId = newSchedule(1);
        String token = login(phone);

        // 第一位挂掉唯一一个号
        book(token, scheduleId, UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isZero();

        // 第二位来挂 —— 必须明确告诉他是"约满"了，而不是笼统的失败
        book(login(phone2), scheduleId, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SLOTS_AVAILABLE"));

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("约满后号源必须停在 0，不能变成负数")
                .isZero();
    }

    @Test
    @DisplayName("挂号成功后号源 -1")
    void bookingDeductsOneSlot() throws Exception {
        Long scheduleId = newSchedule(20);
        String token = login(phone);

        book(token, scheduleId, UUID.randomUUID().toString()).andExpect(status().isOk());

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(19);
    }

    @Test
    @DisplayName("排班不存在返回 404，且不留下任何订单")
    void unknownScheduleReturns404() throws Exception {
        String token = login(phone);

        book(token, 99999999L, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(countOrders(phone)).isZero();
    }

    // ------------------------------------------------------------------
    // A-05 取消与归还
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A-05 取消后号源 +1，订单转 CANCELLED")
    void cancelReturnsSlot() throws Exception {
        Long scheduleId = newSchedule(10);
        String token = login(phone);
        String no = bookNo(token, scheduleId, UUID.randomUUID().toString());
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isEqualTo(9);

        mockMvc.perform(post("/api/appointments/{no}/cancel", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"临时有事\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("临时有事"));

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("取消后号源必须 +1")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("A-05 重复取消幂等：不报错，且号源不会超过总号源")
    void repeatedCancelIsIdempotentAndDoesNotOverReturn() throws Exception {
        Long scheduleId = newSchedule(10);
        String token = login(phone);
        String no = bookNo(token, scheduleId, UUID.randomUUID().toString());

        // 取消 5 次 —— 这是最容易造成"号源加超"的场景
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/appointments/{no}/cancel", no)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        int remaining = scheduleMapper.selectById(scheduleId).getRemainingSlots();
        assertThat(remaining)
                .as("重复取消必须幂等：号源恰好回到总号源，绝不超出")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("取消后可以重新挂同一排班（号源被正确归还，不是'锁死'了）")
    void canRebookAfterCancel() throws Exception {
        Long scheduleId = newSchedule(1);
        String token = login(phone);

        String no = bookNo(token, scheduleId, UUID.randomUUID().toString());
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isZero();

        mockMvc.perform(post("/api/appointments/{no}/cancel", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // 现在应当能重新挂上（换新键）
        String no2 = bookNo(token, scheduleId, UUID.randomUUID().toString());
        assertThat(no2).isNotEqualTo(no);
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots()).isZero();
    }

    @Test
    @DisplayName("取消不存在的单号返回 404")
    void cancelUnknownOrderReturns404() throws Exception {
        String token = login(phone);

        mockMvc.perform(post("/api/appointments/{no}/cancel", "AP-NOT-EXIST")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 用户隔离 —— 只能看到/操作自己的
    // ------------------------------------------------------------------

    @Test
    @DisplayName("看不到别人的挂号：列表只返回自己的")
    void listOnlyShowsOwnAppointments() throws Exception {
        Long scheduleId = newSchedule(10);
        String tokenA = login(phone);
        bookNo(tokenA, scheduleId, UUID.randomUUID().toString());

        // 乙没挂过号，列表必须为空
        mockMvc.perform(get("/api/appointments").header("Authorization", "Bearer " + login(phone2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));

        // 甲能看到自己的
        mockMvc.perform(get("/api/appointments").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].doctorName").value("挂号测试医生"))
                .andExpect(jsonPath("$.items[0].departmentName").value("挂号测试科室"));
    }

    @Test
    @DisplayName("无法取消别人的挂号——用别人的单号返回 404（不泄露单号是否存在）")
    void cannotCancelOthersAppointment() throws Exception {
        Long scheduleId = newSchedule(10);
        String tokenA = login(phone);
        String noA = bookNo(tokenA, scheduleId, UUID.randomUUID().toString());

        // 乙拿着甲的单号来取消
        mockMvc.perform(post("/api/appointments/{no}/cancel", noA)
                        .header("Authorization", "Bearer " + login(phone2))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 甲的订单必须没被动过
        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("越权取消不能生效，号源不该被归还")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("我的挂号需要登录；未登录一律 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/appointments")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleId\":1,\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/appointments/AP-X/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("响应体里不出现口令哈希")
    void bookingResponseHasNoSecrets() throws Exception {
        Long scheduleId = newSchedule(5);
        String body = book(login(phone), scheduleId, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain("idempotencyKey");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions book(String token, Long scheduleId, String key)
            throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("scheduleId", scheduleId, "idempotencyKey", key));
        return mockMvc.perform(post("/api/appointments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String bookNo(String token, Long scheduleId, String key) throws Exception {
        String body = book(token, scheduleId, key)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("appointmentNo").asText();
    }

    private long countOrders(String phone) {
        SysUser u = userMapper.findByPhone(phone);
        if (u == null) {
            return 0;
        }
        return appointmentMapper.countByUser(u.getId(), null);
    }

    private Long newSchedule(int slots) {
        Schedule s = new Schedule();
        s.setDoctorId(doctorId);
        s.setDepartmentId(departmentId);
        s.setWorkDate(LocalDate.now().plusDays(1));
        // period 必须在同一医生同一天内唯一，这里用递增的时段字符串区分不同排班
        s.setPeriod(String.format("AM%03d", ++scheduleSeq));
        s.setTotalSlots(slots);
        s.setRemainingSlots(slots);
        s.setFee(new BigDecimal("50.00"));
        scheduleMapper.insert(s);
        return s.getId();
    }

    private void createUser(String phone, String name) {
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(SECRET));
        user.setRealName(name);
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);
    }

    private String login(String phone) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "password", SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String uniquePhone(String prefix) {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return prefix + String.format("%08d", n);
    }
}
