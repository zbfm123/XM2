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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>幂等重放的确定性契约 + 并发失败时的可诊断性。</b>
 *
 * <h2>为什么单独建这个类</h2>
 *
 * 全量跑测试时 {@code ConcurrentIdempotencyIntegrationTest} 曾出现过两次失败
 * （之后连跑 15 次全量又完全正常，所以那两次更可能是环境因素，不是稳定缺陷）。
 *
 * <p>但那次排查暴露出一个<b>与偶发无关的真实缺陷</b>：那个测试的失败断言
 * 只统计"有没有拿到订单号"，<b>不记录失败的响应体</b>。
 * 于是失败时只能知道"有一个请求没拿到"，<b>不知道它返回的到底是什么</b>——
 * 409？500？还是别的。没有这个信息就无从定位。
 *
 * <p><b>"只知道有失败、不知道失败是什么"本身就是缺陷。</b>
 * 这个类补上两件事：
 * <ol>
 *   <li><b>确定性的幂等契约</b>：首次下单成功后，重试必须返回<b>同一个单号</b>
 *       且 {@code replayed=true}。这条不依赖并发时序，任何时候都该成立。</li>
 *   <li><b>并发失败时能看清原因</b>：把每个响应的
 *       {@code HTTP 状态码 + 完整响应体} 都收集起来，失败时原样报出。</li>
 * </ol>
 *
 * <h2>一条记录：曾经怀疑过、后来证伪的推断</h2>
 *
 * 我一度认为并发失败的原因是"唯一索引冲突后先到者尚未提交，
 * 所以按幂等键查不到它，于是落到兜底抛 {@code ALREADY_BOOKED}"，
 * 并据此在生产代码 {@code book()} 里加了"冲突后短暂重试"。
 *
 * <p>但推敲加锁行为就发现站不住：先到者插入时<b>持有唯一索引上的锁</b>，
 * 落败者的 {@code insert} 会<b>阻塞等锁</b>而不是立刻抛冲突；
 * 等锁释放时先到者已提交，此时按幂等键必然查得到。
 * 那个窗口并不存在，<b>那段重试治错了病，已撤掉</b>。
 *
 * <p>教训写在这里：<b>不要凭对时序的推理去改生产代码，先拿到失败现场。</b>
 * 这正是本类存在的理由——把诊断信息补齐，让下次失败能自己说话。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class IdempotencyVisibilityIntegrationTest {

    private static final String SECRET = "unit-test-placeholder-only";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AppointmentMapper appointmentMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private SysUserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;

    private Long departmentId;
    private Long doctorId;
    private Long scheduleId;
    private String phone;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();

        Department d = new Department();
        d.setCode("IDEMVIS-" + System.nanoTime());
        d.setName("幂等并发诊断科室");
        d.setSortOrder(1);
        departmentMapper.insert(d);
        departmentId = d.getId();

        Doctor doc = new Doctor();
        doc.setDepartmentId(departmentId);
        doc.setName("幂等并发诊断医生");
        doc.setTitle("主任医师");
        doctorMapper.insert(doc);
        doctorId = doc.getId();

        Schedule s = new Schedule();
        s.setDoctorId(doctorId);
        s.setDepartmentId(departmentId);
        s.setWorkDate(LocalDate.now().plusDays(1));
        s.setPeriod("AM001");
        s.setTotalSlots(5);
        s.setRemainingSlots(5);
        s.setFee(new BigDecimal("50.00"));
        scheduleMapper.insert(s);
        scheduleId = s.getId();

        phone = "139" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        SysUser u = new SysUser();
        u.setPhone(phone);
        u.setPasswordHash(passwordEncoder.encode(SECRET));
        u.setRealName("幂等并发诊断用户");
        u.setEnabled(true);
        u.setFailedCount(0);
        userMapper.insert(u);
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

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "password", SECRET))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    /** 发一次挂号请求，返回「HTTP 状态码 + 响应体」，便于失败时看全貌。 */
    private String[] book(String token, String key) throws Exception {
        var result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("scheduleId", scheduleId, "idempotencyKey", key))))
                .andReturn();
        return new String[]{
                String.valueOf(result.getResponse().getStatus()),
                result.getResponse().getContentAsString()};
    }

    // ==================================================================

    @Test
    @DisplayName("先到者已提交：重试走幂等快路径，直接拿到那一单")
    void committedWinnerIsReturnedByFastPath() throws Exception {
        String key = UUID.randomUUID().toString();
        String token = login();

        String[] first = book(token, key);
        assertThat(first[0]).as("首次下单应当成功，实际：" + first[1]).isEqualTo("200");

        String[] second = book(token, key);
        assertThat(second[0]).as("重试也应当是 200，实际：" + second[1]).isEqualTo("200");

        var node = objectMapper.readTree(second[1]);
        assertThat(node.has("appointmentNo")).as("重试必须返回订单号，实际：" + second[1]).isTrue();
        assertThat(node.get("replayed").asBoolean())
                .as("重试应当标记为幂等重放，实际：" + second[1])
                .isTrue();
        assertThat(node.get("appointmentNo").asText())
                .as("重试必须返回**同一个**单号")
                .isEqualTo(objectMapper.readTree(first[1]).get("appointmentNo").asText());
    }

    @Test
    @DisplayName("⚠️ 并发同一键：每个请求都要拿到订单号；失败时把状态码与响应体全列出来")
    void concurrentSameKeyMustAllGetAnOrderNo() throws Exception {
        String key = UUID.randomUUID().toString();
        String token = login();

        int before = scheduleMapper.selectById(scheduleId).getRemainingSlots();

        int concurrency = 8;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        List<Future<String[]>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return book(token, key);
            }));
        }
        gate.countDown();

        List<String[]> results = new ArrayList<>();
        for (Future<String[]> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        // 把每个响应的「状态码 → 响应体」整理出来，失败时能直接看出是 409 还是 500
        List<String> notOk = new ArrayList<>();
        List<String> withoutNo = new ArrayList<>();
        for (String[] r : results) {
            if (!"200".equals(r[0])) {
                notOk.add("HTTP " + r[0] + " → " + r[1]);
            }
            if (!objectMapper.readTree(r[1]).has("appointmentNo")) {
                withoutNo.add("HTTP " + r[0] + " → " + r[1]);
            }
        }

        assertThat(withoutNo)
                .as("同一个幂等键并发到达时，每个请求都应拿到订单号（幂等重放）。"
                        + "下面是**完整的 状态码 + 响应体**，据此定位：")
                .isEmpty();

        int after = scheduleMapper.selectById(scheduleId).getRemainingSlots();
        assertThat(after)
                .as("号源只能扣 1 个 —— 失败的那些请求必须把多扣的号源还回去，"
                        + "否则会凭空丢号且没有任何报错")
                .isEqualTo(before - 1);

        assertThat(notOk)
                .as("并发重复提交不应产生非 200 响应（成功或明确的业务错误都算合理，"
                        + "但不能是 500）。下面是完整的 状态码 + 响应体：")
                .isEmpty();
    }
}
