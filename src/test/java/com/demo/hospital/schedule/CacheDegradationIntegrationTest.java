package com.demo.hospital.schedule;

import com.demo.hospital.config.CacheConfig;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>缓存故障降级</b>：Redis 不可用时，业务必须仍然正常。
 *
 * <h2>为什么必须有这个测试</h2>
 *
 * CacheConfig 里注册了 CacheErrorHandler，注释写着"缓存读写任何异常都只记日志，
 * 然后当作未命中继续走数据库"—— 但在此之前<b>这句话没有任何测试证明</b>。
 *
 * <p>而这恰恰是引入缓存时最容易出事的地方：如果 Redis 一挂、所有带
 * Cacheable 的接口就 500，那就是<b>"为了提速把系统搞挂了"</b>—— 典型的负优化。
 *
 * <p>一句话：<b>引入缓存的同时就引入了一个新的单点，"没有测试的降级"等于没有降级。</b>
 * 这与本项目 A-07 的纪律同一个道理：外部依赖的失败不应否定已完成的业务动作。
 *
 * <h2>怎么模拟"Redis 挂了"（第一版在这里写错过）</h2>
 *
 * 用一个 Primary 的 CacheManager：<b>只对排班缓存抛异常，其它缓存名委托给正常实现</b>。
 *
 * <p>第一版让 mock 对<b>所有</b> cache 名都抛异常，结果不仅排班查询 500，
 * 连认证都坏了（返回 401/500）—— 因为 Spring Security 自己也要用缓存，被误伤了。
 * <b>"模拟某个依赖故障"必须精确到那一个依赖</b>，
 * 否则测的就不是降级，而是"把整个应用打坏"。
 *
 * <h2>为什么必须走 HTTP 接口</h2>
 *
 * 缓存是 Spring AOP 代理生效的，<b>直接调 Service 方法会绕过整个缓存层</b>，
 * 那样测的就是个空。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({RedisTestConfig.class, CacheDegradationIntegrationTest.BrokenScheduleCacheConfig.class})
class CacheDegradationIntegrationTest {

    /**
     * 只有排班缓存"坏掉"的 CacheManager。
     *
     * <p>其它 cache 名原样委托给内存实现—— 这样既模拟了 Redis 里那一份缓存不可用，
     * 又不会误伤 Spring Security 等框架自身的缓存。
     */
    @TestConfiguration
    static class BrokenScheduleCacheConfig {

        @Bean
        @Primary
        CacheManager brokenScheduleCacheManager() {
            // 注意用**无参构造**：ConcurrentMapCacheManager 传了名单就变成"固定名单"，
            // 名单外的名字 getCache 返回 null。这里要的是一个"别的缓存都正常"的委托，
            // 所以用动态创建模式，否则最后那条"不误伤其它缓存"的断言会失败。
            ConcurrentMapCacheManager delegate = new ConcurrentMapCacheManager();

            return new CacheManager() {
                @Override
                public Cache getCache(String name) {
                    // 只有排班缓存出问题；别的照常
                    if (CacheConfig.SCHEDULE_CACHE.equals(name)) {
                        return new BrokenCache(name);
                    }
                    return delegate.getCache(name);
                }

                @Override
                public Collection<String> getCacheNames() {
                    return delegate.getCacheNames();
                }
            };
        }
    }

    /**
     * 一个"能拿到、但一读就失败"的缓存 —— 这才是 Redis 不可用的**真实形态**。
     *
     * <h2>⚠️ 第一版模拟错了，反而撞出一个更值得知道的事实</h2>
     *
     * 第一版让 {@code CacheManager.getCache(name)} 直接抛异常，结果排班查询返回 500。
     * 查 Spring 的字节码才明白：
     *
     * <pre>
     *   CacheErrorHandler.handleCacheGetError   <- 只在 findInCaches 里被调用
     *   CacheErrorHandler.handleCachePutError   <- 出现 0 次
     *   CacheErrorHandler.handleCacheEvictError <- 出现 0 次
     * </pre>
     *
     * 也就是说：**错误处理器只保护 cache.get()/put() 这类"操作"，
     * 不保护 cacheManager.getCache() 这个"解析"阶段。**
     * 解析阶段抛异常会直接冒到调用方。
     *
     * <p>那真实 Redis 挂掉时是哪一种？<b>是这一种。</b>
     * {@code RedisCacheManager.getCache()} 只是构造一个 {@code RedisCache} 对象、
     * <b>不会去连 Redis</b>（连接是惰性的），真正的异常发生在 {@code cache.get(key)} 时。
     * 所以真实故障路径是<b>被保护的</b>。
     *
     * <p>结论：第一版把"解析失败"当成了"Redis 挂了"，模拟不真实。
     * 改成"解析成功、读的时候失败"之后，才真正测到我们想要的降级路径。
     * <b>模拟故障必须模拟到真实的那一层</b>，否则测出来的结论是错的。
     */
    static class BrokenCache implements Cache {

        private final String name;

        BrokenCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return this;
        }

        @Override
        public ValueWrapper get(Object key) {
            throw new RedisConnectionFailureException("模拟：Redis 连接不可用");
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            throw new RedisConnectionFailureException("模拟：Redis 连接不可用");
        }

        @Override
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            throw new RedisConnectionFailureException("模拟：Redis 连接不可用");
        }

        @Override
        public void put(Object key, Object value) {
            throw new RedisConnectionFailureException("模拟：Redis 连接不可用");
        }

        @Override
        public void evict(Object key) {
            throw new RedisConnectionFailureException("模拟：Redis 连接不可用");
        }

        @Override
        public void clear() {
            throw new RedisConnectionFailureException("模拟：Redis 连接不可用");
        }
    }

    private static final String SECRET = "unit-test-placeholder-only";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;

    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private DoctorMapper doctorMapper;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private SysUserMapper userMapper;
    @Autowired private com.demo.hospital.appointment.mapper.AppointmentMapper appointmentMapper;

    private Long departmentId;
    private Long doctorId;
    private Long scheduleId;
    private String phone;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();

        Department d = new Department();
        d.setCode("DEGR-" + System.nanoTime());
        d.setName("降级测试科室");
        d.setSortOrder(1);
        departmentMapper.insert(d);
        departmentId = d.getId();

        Doctor doc = new Doctor();
        doc.setDepartmentId(departmentId);
        doc.setName("降级测试医生");
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

        phone = "136" + String.format("%08d", Math.abs(System.nanoTime() % 100_000_000L));
        SysUser u = new SysUser();
        u.setPhone(phone);
        u.setPasswordHash(passwordEncoder.encode(SECRET));
        u.setRealName("降级测试用户");
        u.setEnabled(true);
        u.setFailedCount(0);
        userMapper.insert(u);
    }

    @AfterEach
    void cleanUp() {
        SysUser u = userMapper.findByPhone(phone);
        if (u != null) {
            appointmentMapper.deleteByUserId(u.getId());
            userMapper.deleteByPhone(phone);
        }
        if (doctorId != null) {
            scheduleMapper.deleteByDoctorId(doctorId);
            doctorMapper.deleteById(doctorId);
        }
        if (departmentId != null) {
            departmentMapper.deleteById(departmentId);
        }
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

    // ------------------------------------------------------------------

    @Test
    @DisplayName("排班缓存不可用时，查排班仍返回正确数据（降级为直查数据库）")
    void scheduleQueryShouldStillWorkWhenCacheIsDown() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", "Bearer " + token)
                        .param("doctorId", String.valueOf(doctorId))
                        .param("page", "1")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].remainingSlots").value(5))
                .andExpect(jsonPath("$.items[0].doctorName").value("降级测试医生"));
    }

    @Test
    @DisplayName("排班缓存不可用时，挂号仍然成功并真的扣掉号源")
    void bookingShouldStillWorkWhenCacheIsDown() throws Exception {
        String token = login();

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId,
                                "idempotencyKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        assertThat(scheduleMapper.selectById(scheduleId).getRemainingSlots())
                .as("缓存挂了也必须真的扣掉一个号")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("降级确实被触发：排班缓存真的抛异常，且不误伤其它缓存")
    void degradationIsActuallyTriggered() {
        // 这条是防"假绿"的关键。
        // 如果缓存拦截器因为任何原因没生效，上面两个测试也会通过——
        // 但它们证明的就变成"压根没用缓存"，而不是"降级生效"。
        Cache scheduleCache = cacheManager.getCache(CacheConfig.SCHEDULE_CACHE);
        assertThat(scheduleCache)
                .as("排班缓存必须能拿到（RedisCacheManager 也是能拿到的，只是连不上）")
                .isNotNull();
        assertThatThrownBy(() -> scheduleCache.get("any-key"))
                .as("读排班缓存必须抛异常，否则前两个测试证明的是别的东西")
                .isInstanceOf(RedisConnectionFailureException.class);

        // 并且其它缓存名不受影响：证明"只坏了一个依赖"，不是把整个应用打坏
        assertThat(cacheManager.getCache("some-other-cache"))
                .as("其它缓存名必须正常，否则这个测试模拟的是'整个应用崩了'")
                .isNotNull();
    }
}