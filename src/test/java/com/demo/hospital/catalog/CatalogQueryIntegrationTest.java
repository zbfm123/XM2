package com.demo.hospital.catalog;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 科室 / 医生 / 排班查询的集成测试 —— 验收 <b>A-02</b>。
 *
 * <p>测试环境刻意<b>不加载 {@code data.sql}</b>（见 {@code application-test.yml}）：
 * 用生产预置数据跑测试，会让断言依赖"恰好有 5 个科室"这类无关数字，
 * 哪天预置数据改成 6 个科室，一堆测试就会莫名其妙地红。
 * 所以这里<b>自己造可控的最小数据</b>。
 *
 * <p>与认证测试同样的纪律：<b>不加 {@code @Transactional}</b>。
 * 测试级事务会掩盖真实的事务边界问题（T-003 就因此漏掉了一个真 bug，
 * 见 docs/PROGRESS.md），所以数据自己清理。
 *
 * <p>本类覆盖的重点不是"能查出数据"，而是那些<b>不写测试就永远发现不了</b>的约定：
 * 未登录必须 401、分页是 1 基、空区间返回空而不是报错、
 * 以及"已约满"这个状态真的送到了前端。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class CatalogQueryIntegrationTest {

    private static final String TEST_SECRET = "unit-test-placeholder-only";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired private SysUserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private ScheduleMapper scheduleMapper;

    private String phone;
    private Long departmentId;
    private Long doctorId;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        phone = uniquePhone();

        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(TEST_SECRET));
        user.setRealName("目录查询测试");
        user.setEnabled(true);
        user.setFailedCount(0);
        userMapper.insert(user);

        Department department = new Department();
        department.setCode("IT-DEPT-" + System.nanoTime());
        department.setName("集成测试科室");
        department.setDescription("测试用");
        department.setSortOrder(1);
        departmentMapper.insert(department);
        departmentId = department.getId();

        Doctor doctor = new Doctor();
        doctor.setDepartmentId(departmentId);
        doctor.setName("测试医生");
        doctor.setTitle("主任医师");
        doctor.setSpecialty("测试擅长");
        doctor.setIntro("测试简介");
        doctorMapper.insert(doctor);
        doctorId = doctor.getId();
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
        userMapper.deleteByPhone(phone);
    }

    // ------------------------------------------------------------------
    // A-02：登录后能查到科室 / 医生 / 排班
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A-02 完整链路：登录 → 查科室 → 查医生 → 查排班")
    void fullCatalogChain() throws Exception {
        String token = loginAndGetToken();

        // ① 科室列表
        mockMvc.perform(get("/api/departments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + departmentId + ")].name")
                        .value("集成测试科室"));

        // ② 该科室的医生（应带出科室名，前端列表要显示"哪个科的医生"）
        mockMvc.perform(get("/api/doctors").param("deptId", String.valueOf(departmentId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("测试医生"))
                .andExpect(jsonPath("$[0].title").value("主任医师"))
                .andExpect(jsonPath("$[0].departmentName").value("集成测试科室"));

        // ③ 该医生的排班
        insertSchedule(LocalDate.now().plusDays(1), "AM", 20, 20, new BigDecimal("50.00"));

        mockMvc.perform(get("/api/schedules").param("doctorId", String.valueOf(doctorId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[0].remainingSlots").value(20))
                .andExpect(jsonPath("$.items[0].period").value("AM"))
                // JOIN 出来的展示字段也要在
                .andExpect(jsonPath("$.items[0].doctorName").value("测试医生"))
                .andExpect(jsonPath("$.items[0].doctorTitle").value("主任医师"))
                .andExpect(jsonPath("$.items[0].departmentName").value("集成测试科室"))
                .andExpect(jsonPath("$.items[0].soldOut").value(false));
    }

    @Test
    @DisplayName("排班必须返回 remainingSlots（需求 F-03-1）——这是防超卖的直接观测对象")
    void scheduleMustExposeRemainingSlots() throws Exception {
        insertSchedule(LocalDate.now().plusDays(2), "PM", 30, 7, new BigDecimal("20.00"));
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/schedules").param("doctorId", String.valueOf(doctorId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].totalSlots").value(30))
                .andExpect(jsonPath("$.items[0].remainingSlots").value(7))
                .andExpect(jsonPath("$.items[0].fee").value(20.00));
    }

    @Test
    @DisplayName("号源为 0 时 soldOut=true——让前端能提前禁用按钮，而不是等用户点了才报错")
    void soldOutShouldBeReported() throws Exception {
        insertSchedule(LocalDate.now().plusDays(3), "AM", 20, 0, new BigDecimal("50.00"));
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/schedules").param("doctorId", String.valueOf(doctorId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].remainingSlots").value(0))
                .andExpect(jsonPath("$.items[0].soldOut").value(true));
    }

    // ------------------------------------------------------------------
    // 分页：1 基（项目 1 在这踩过坑）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分页是 1 基：page=1 就是第一页，且不重不漏")
    void paginationIsOneBased() throws Exception {
        // 造 5 条排班，每页 2 条
        for (int i = 0; i < 5; i++) {
            insertSchedule(LocalDate.now().plusDays(10 + i), "AM", 10, 10, new BigDecimal("10.00"));
        }
        String token = loginAndGetToken();

        // 第 1 页：应该是两天里最早的两条
        mockMvc.perform(get("/api/schedules")
                        .param("doctorId", String.valueOf(doctorId))
                        .param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].workDate")
                        .value(LocalDate.now().plusDays(10).toString()))
                .andExpect(jsonPath("$.items[1].workDate")
                        .value(LocalDate.now().plusDays(11).toString()));

        // 第 3 页：只剩最后一条（5 = 2+2+1）
        mockMvc.perform(get("/api/schedules")
                        .param("doctorId", String.valueOf(doctorId))
                        .param("page", "3").param("size", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].workDate")
                        .value(LocalDate.now().plusDays(14).toString()));
    }

    @Test
    @DisplayName("page=0 返回 400——页码从 1 开始，静默纠正会掩盖调用错误")
    void pageZeroShouldBeRejected() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/schedules")
                        .param("doctorId", String.valueOf(doctorId))
                        .param("page", "0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("size 超过上限会被收敛，不会一次把整张表查出来")
    void oversizedPageSizeShouldBeCapped() throws Exception {
        insertSchedule(LocalDate.now().plusDays(1), "AM", 10, 10, new BigDecimal("10.00"));
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/schedules")
                        .param("doctorId", String.valueOf(doctorId))
                        .param("size", "100000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // ------------------------------------------------------------------
    // 日期区间过滤
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日期区间过滤生效；区间内没有排班时返回空页而不是报错")
    void dateRangeFiltering() throws Exception {
        insertSchedule(LocalDate.now().plusDays(1), "AM", 10, 10, new BigDecimal("10.00"));
        insertSchedule(LocalDate.now().plusDays(30), "AM", 10, 10, new BigDecimal("10.00"));
        String token = loginAndGetToken();

        // 只取最近 7 天：应只剩 1 条
        mockMvc.perform(get("/api/schedules")
                        .param("doctorId", String.valueOf(doctorId))
                        .param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().plusDays(7).toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].workDate")
                        .value(LocalDate.now().plusDays(1).toString()));

        // 一个月后的空区间：200 + 空列表（"没排班"是正常状态，不是错误）
        mockMvc.perform(get("/api/schedules")
                        .param("doctorId", String.valueOf(doctorId))
                        .param("from", LocalDate.now().plusDays(60).toString())
                        .param("to", LocalDate.now().plusDays(90).toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ------------------------------------------------------------------
    // 错误分支：区分"资源不存在"与"没有数据"
    // ------------------------------------------------------------------

    @Test
    @DisplayName("科室不存在返回 404（与'科室没医生'的 200 空列表区分开）")
    void unknownDepartmentShouldReturn404() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/doctors").param("deptId", "99999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("医生不存在返回 404，而不是空的排班页")
    void unknownDoctorShouldReturn404() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/schedules").param("doctorId", "99999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("医生存在但没排班：200 + 空列表（这是正常状态，不是错误）")
    void doctorWithoutSchedulesReturnsEmptyPage() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/schedules").param("doctorId", String.valueOf(doctorId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("缺 deptId 返回 400——不该有'不传就返回全部医生'这种默认行为")
    void missingDeptIdShouldReturn400() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(get("/api/doctors").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // 鉴权：默认拒绝（不因为"这是只读数据"就放行）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("三个查询接口都必须登录——只读数据也在默认拒绝范围内")
    void catalogEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/departments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/doctors").param("deptId", String.valueOf(departmentId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/schedules").param("doctorId", String.valueOf(doctorId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("令牌无效时查询接口返回 401，不会因为'只是读数据'而放行")
    void catalogEndpointsRejectTamperedToken() throws Exception {
        mockMvc.perform(get("/api/departments").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void insertSchedule(LocalDate workDate, String period,
                                int total, int remaining, BigDecimal fee) {
        Schedule schedule = new Schedule();
        schedule.setDoctorId(doctorId);
        schedule.setDepartmentId(departmentId);
        schedule.setWorkDate(workDate);
        schedule.setPeriod(period);
        schedule.setTotalSlots(total);
        schedule.setRemainingSlots(remaining);
        schedule.setFee(fee);
        scheduleMapper.insert(schedule);
    }

    private String uniquePhone() {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return "137" + String.format("%08d", n);
    }

    private String loginAndGetToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "password", TEST_SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
