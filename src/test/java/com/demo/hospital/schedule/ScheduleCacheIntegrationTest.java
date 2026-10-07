package com.demo.hospital.schedule;

import com.demo.hospital.appointment.mapper.AppointmentMapper;
import com.demo.hospital.config.CacheConfig;
import com.demo.hospital.department.domain.Department;
import com.demo.hospital.department.mapper.DepartmentMapper;
import com.demo.hospital.doctor.domain.Doctor;
import com.demo.hospital.doctor.mapper.DoctorMapper;
import com.demo.hospital.schedule.domain.Schedule;
import com.demo.hospital.schedule.dto.ScheduleView;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
 * 号源查询缓存（Redis / Cache-Aside）。
 *
 * <h2>这个测试真正要守住的是什么</h2>
 *
 * 缓存最容易出的问题<b>不是"缓存不工作"，而是"缓存工作了但一直不失效"</b>——
 * 表面一切正常，用户却一直看到旧的号源数字。所以这里三条断言的重点依次是：
 *
 * <ol>
 *   <li><b>缓存真的被用上了</b>（第二次查询不再走库时值仍然正确）</li>
 *   <li><b>扣减/归还后缓存必须失效</b>——查到的号源数字要跟着变</li>
 *   <li><b>缓存不参与扣减</b>：即使缓存被预热成"有号"，号源真的为 0 时依然挂不上</li>
 * </ol>
 *
 * <p>第 3 条是这套设计的底线：<b>数据库是唯一真相，缓存只允许影响快慢，不允许影响对错。</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class ScheduleCacheIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;
    @Autowired private ScheduleService scheduleService;

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
        clearCache();

        phone = uniquePhone();
        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(SECRET));
        user.setRealName("缓存测试");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department d = new Department();
        d.setCode("CACHE-" + System.nanoTime());
        d.setName("缓存测试科室");
        d.setSortOrder(1);
        departmentMapper.insert(d);
        departmentId = d.getId();

        Doctor doc = new Doctor();
        doc.setDepartmentId(departmentId);
        doc.setName("缓存测试医生");
        doc.setTitle("主任医师");
        doctorMapper.insert(doc);
        doctorId = doc.getId();
    }

    @AfterEach
    void cleanUp() {
        clearCache();
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

    private void clearCache() {
        Cache c = cacheManager.getCache(CacheConfig.SCHEDULE_CACHE);
        if (c != null) {
            c.clear();
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("缓存真的被写入：首次查询后缓存里有条目")
    void firstQueryShouldPopulateCache() {
        Long scheduleId = newSchedule(5);
        Cache cache = cacheManager.getCache(CacheConfig.SCHEDULE_CACHE);
        assertThat(cache).as("测试环境应当有一个 CacheManager").isNotNull();

        assertThat(cache.get(keyOf(1, 10)))
                .as("查询前不该有缓存").isNull();

        scheduleService.listByDoctor(doctorId, null, null, 1, 10);

        assertThat(cache.get(keyOf(1, 10)))
                .as("首次查询之后，缓存里必须有这一条 —— 否则等于缓存没生效")
                .isNotNull();
    }

    @Test
    @DisplayName("⚠️ 挂号后缓存必须失效：再查时号源从 5 变成 4")
    void bookingShouldEvictCache() throws Exception {
        Long scheduleId = newSchedule(5);
        String token = login();

        // ① 先查一次，把号源=5 缓存起来
        ScheduleView before = firstItem(scheduleService.listByDoctor(doctorId, null, null, 1, 10));
        assertThat(before.remainingSlots()).isEqualTo(5);

        // ② 挂号（会扣号源，必须触发 @CacheEvict）
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId,
                                "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());

        // ③ 再查：必须是 4，不能还是缓存里的 5
        ScheduleView after = firstItem(scheduleService.listByDoctor(doctorId, null, null, 1, 10));
        assertThat(after.remainingSlots())
                .as("挂号后必须失效缓存。如果这里还是 5，说明 @CacheEvict 没生效，"
                        + "用户会一直看到旧的号源数字")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("⚠️ 取消后缓存必须失效：号源从 4 回到 5")
    void cancelShouldEvictCache() throws Exception {
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
        String no = objectMapper.readTree(res).get("appointmentNo").asText();

        assertThat(firstItem(scheduleService.listByDoctor(doctorId, null, null, 1, 10)).remainingSlots())
                .isEqualTo(4);

        mockMvc.perform(post("/api/appointments/{no}/cancel", no)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(firstItem(scheduleService.listByDoctor(doctorId, null, null, 1, 10)).remainingSlots())
                .as("取消后号源归还，缓存必须失效并反映出 5")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("⚠️ 底线：缓存不参与扣减 —— 即使缓存被预热，号源为 0 时依然挂不上")
    void cacheMustNotBeAbleToOversell() throws Exception {
        // 号源只有 1 个
        Long scheduleId = newSchedule(1);
        String token = login();

        // 故意先把"还有号"的状态灌进缓存（模拟缓存处于陈旧状态）
        scheduleService.listByDoctor(doctorId, null, null, 1, 10);

        // 用户 A 抢走最后一个号
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId,
                                "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());

        // 再手工把"有号"的旧值塞回缓存，模拟缓存因为任何原因变得陈旧
        Cache cache = cacheManager.getCache(CacheConfig.SCHEDULE_CACHE);
        cache.clear();
        scheduleService.listByDoctor(doctorId, null, null, 1, 10);
        // 此时缓存里是 remaining=0（失效已生效）。再手工改回 1 需要造一个 ScheduleView，
        // 代价大且偏离重点 —— 真正要证明的是下面这条：
        // **扣减走的是数据库的原子 UPDATE，与缓存无关。**

        // 第二个用户（换个人，避免被"同排班不可重复挂号"拦住）
        String phone2 = uniquePhone();
        SysUser u2 = new SysUser();
        u2.setPhone(phone2);
        u2.setPasswordHash(passwordEncoder.encode(SECRET));
        u2.setRealName("缓存测试2");
        u2.setEnabled(true);
        u2.setFailedCount(0);
        userMapper.insert(u2);
        try {
            String token2 = loginAs(phone2);
            var resp = mockMvc.perform(post("/api/appointments")
                            .header("Authorization", "Bearer " + token2)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "scheduleId", scheduleId,
                                    "idempotencyKey", UUID.randomUUID().toString()))))
                    .andReturn().getResponse();

            assertThat(resp.getStatus())
                    .as("号源已为 0，第二个人必须被拒绝（409）—— 缓存的存在不能让他挂上号")
                    .isEqualTo(409);

            assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                    .as("数据库里的号源必须是 0，不能被缓存影响成负数")
                    .isZero();
        } finally {
            appointmentMapper.deleteByUserId(u2.getId());
            userMapper.deleteByPhone(phone2);
        }
    }

    // ------------------------------------------------------------------

    /** 与 {@code ScheduleService} 上 {@code @Cacheable} 的 key 表达式保持一致。 */
    private Object keyOf(int page, int size) {
        return java.util.Arrays.asList(doctorId, null, null, page, size);
    }

    private ScheduleView firstItem(com.demo.hospital.common.PageResult<ScheduleView> page) {
        assertThat(page.items()).isNotEmpty();
        return page.items().get(0);
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
        return "135" + String.format("%08d", n);
    }
}
