package com.demo.hospital.auth;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链路集成测试：HTTP 层 → 安全过滤器链 → Service → H2。
 *
 * <p>这是验收 <b>A-01</b> 的证据：注册 → 登录拿令牌 → 带令牌访问受保护接口成功；
 * 无令牌返回 401。同时覆盖失败路径（口令错误、账号枚举、锁定、令牌伪造/过期）。
 *
 * <p>测试纪律：<b>失败路径比成功路径更值得写测试。</b>
 * 成功路径出问题会立刻被发现（功能不可用），而"口令错了却返回 200"、
 * "账号不存在与口令错误提示不同"这类问题，不写测试就永远发现不了。
 *
 * <p>⚠️ <b>这个类刻意不加 {@code @Transactional}——这是踩过坑之后专门改的。</b>
 * 加测试级事务时，MockMvc 的请求会跑在<b>测试自己的那个事务</b>里，
 * 于是"业务方法抛异常导致回滚"只会回滚到一个 savepoint，
 * 计数器照样留在数据库里、测试照样通过。而真实环境里那次回滚是把计数一起丢掉的：
 * <b>账号永远不会被锁定。</b>
 * 见 {@link #failuresShouldActuallyPersistToDatabase()}——它就是为了守住这条而写的。
 * 代价是测试数据要自己清理（{@link #cleanUp()}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
class AuthIntegrationTest {

    /**
     * 测试账号的口令。
     *
     * <p>命名刻意不叫 {@code PASSWORD}、取值也刻意不像真实口令：
     * 常量名叫 "PASSWORD" 会让人误以为项目里存着一个真实口令（push.ps1 的敏感信息扫描
     * 也会正确地把它拦下来）。<b>用显式表明用途的名字与值，既不自欺也不制造假警报。</b>
     */
    private static final String TEST_ACCOUNT_SECRET = "unit-test-placeholder-only";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private ObjectMapper objectMapper;

    /** 每个测试用独立手机号，避免测试之间互相干扰（尤其锁定测试会改账号状态）。 */
    private String phone;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        phone = uniquePhone();
    }

    /**
     * 清理本测试造出的账号。
     *
     * <p>没有测试级事务，所以必须自己删干净——否则 `mvn test` 会在开发库里
     * 越积越多的测试账号，最后没人敢相信那个库里的数据。
     */
    @AfterEach
    void cleanUp() {
        userMapper.deleteByPhone(phone);
    }

    // ------------------------------------------------------------------
    // A-01：注册 → 登录 → 带令牌访问
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A-01 完整链路：注册 → 登录拿令牌 → 带令牌访问 /me")
    void registerThenLoginThenAccessProtectedEndpoint() throws Exception {
        // ① 注册
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(phone, TEST_ACCOUNT_SECRET, "测试患者")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.realName").value("测试患者"))
                // 注册响应里不该有令牌：注册成功 ≠ 登录
                .andExpect(jsonPath("$.token").doesNotExist());

        // ② 登录
        String token = loginAndGetToken(phone, TEST_ACCOUNT_SECRET);

        // ③ 带令牌访问受保护接口
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.realName").value("测试患者"));
    }

    @Test
    @DisplayName("注册写入口令哈希而不是明文，且哈希每次不同（BCrypt 自带盐）")
    void registerShouldStoreHashNotPlaintext() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(phone, TEST_ACCOUNT_SECRET, null)))
                .andExpect(status().isCreated());

        SysUser saved = userMapper.findByPhone(phone);

        assertThat(saved.getPasswordHash())
                .as("绝不能存明文")
                .isNotEqualTo(TEST_ACCOUNT_SECRET)
                .startsWith("$2");
        assertThat(saved.getFailedCount()).isZero();
        assertThat(saved.getEnabled()).isTrue();
        // 未填姓名时给中性默认值，而不是留 NULL（real_name 是 NOT NULL 列）
        assertThat(saved.getRealName()).isNotBlank();
    }

    @Test
    @DisplayName("注册响应体里不出现口令哈希")
    void registerResponseShouldNotLeakHash() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(phone, TEST_ACCOUNT_SECRET, "测试患者")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain(TEST_ACCOUNT_SECRET);
    }

    @Test
    @DisplayName("重复手机号注册返回 409 且错误码为 PHONE_ALREADY_REGISTERED")
    void duplicatePhoneShouldConflict() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(phone, "another-placeholder-secret", "另一个人")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("注册参数不合法返回 400，并一次性指出所有出错字段")
    void invalidRegisterPayloadShouldReturn400WithFields() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "phone", "12345",                 // 格式不对
                "password", "short"               // 太短
        ));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.phone").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    @DisplayName("同一个口令在两个账号上得到不同的哈希——撞库比对哈希因此失去意义")
    void samePasswordShouldProduceDifferentHashes() throws Exception {
        String other = uniquePhone();
        register(phone, TEST_ACCOUNT_SECRET);
        register(other, TEST_ACCOUNT_SECRET);

        assertThat(userMapper.findByPhone(phone).getPasswordHash())
                .isNotEqualTo(userMapper.findByPhone(other).getPasswordHash());
    }

    // ------------------------------------------------------------------
    // 登录成功路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("登录成功返回令牌、有效期与用户信息，且不含口令哈希")
    void loginShouldReturnTokenWithoutHash() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, TEST_ACCOUNT_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(120 * 60))
                .andExpect(jsonPath("$.expiresAt").isNumber())
                .andExpect(jsonPath("$.user.phone").value(phone))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("令牌只用于认证，不携带业务字段——JWT 是签名而非加密")
    void tokenShouldOnlyCarrySubjectAndStandardClaims() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        String token = loginAndGetToken(phone, TEST_ACCOUNT_SECRET);

        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);

        // 载荷里不该出现手机号：JWT 谁都能解开看，能少放就少放
        assertThat(payload)
                .as("令牌载荷不应包含手机号")
                .doesNotContain(phone);
        assertThat(payload).contains("sub");
    }

    // ------------------------------------------------------------------
    // 登录失败路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("口令错误返回 401 且错误码为 BAD_CREDENTIALS")
    void wrongPasswordShouldReturnBadCredentials() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, "definitely-not-the-secret")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    @DisplayName("账号不存在时不泄露账号是否存在（与口令错误同一个错误码）")
    void unknownPhoneShouldNotLeakExistence() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(uniquePhone(), TEST_ACCOUNT_SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    @DisplayName("连续失败达到阈值后锁定：第 5 次返回 423，此后正确口令也被拒绝")
    void repeatedFailuresShouldLockAccount() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);

        // 配置阈值是 5：前 4 次是普通失败
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(phone, "wrong-" + i)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        }

        // 第 5 次触发锁定
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, "wrong-final")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.unlockAt").isNumber());

        // ⚠️ 关键断言：锁定后连正确口令也必须被拒绝。
        //    否则"锁定"只是一个好看的提示，攻击者照样能继续爆破。
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, TEST_ACCOUNT_SECRET)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("登录成功会清零失败计数——否则偶尔打错一次的人会被累积到锁定")
    void successShouldResetFailureCounter() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, "wrong")))
                .andExpect(status().isUnauthorized());
        assertThat(userMapper.findByPhone(phone).getFailedCount()).isEqualTo(1);

        loginAndGetToken(phone, TEST_ACCOUNT_SECRET);

        assertThat(userMapper.findByPhone(phone).getFailedCount()).isZero();
        assertThat(userMapper.findByPhone(phone).getLockedUntil()).isNull();
    }

    /**
     * 这条测试守的是一个<b>真实发生过的 bug</b>，不是假想的：
     *
     * <p>失败计数写在 {@code sys_user.failed_count}，而登录失败时业务方法要抛异常。
     * Spring 的 {@code @Transactional} 默认只对 {@code RuntimeException} 回滚——
     * 于是<b>计数更新和异常一起被回滚，数据库里永远是 0，账号永远锁不上</b>。
     *
     * <p>它之所以能长期不被发现，是因为测试里加了 {@code @Transactional}：
     * 那样"回滚"只回滚到一个 savepoint，计数在测试事务里看得见，测试就是绿的。
     * <b>错误的事务边界同时掩盖了 bug 和它的测试。</b>
     *
     * <p>因此这里不从 HTTP 响应断言（响应码本来就是对的），
     * 而是<b>回库看那条记录到底改了没有</b>——这才是"锁定是否真的生效"的唯一证据。
     */
    @Test
    @DisplayName("登录失败必须真的写进数据库（回滚会静默废掉整个锁定功能）")
    void failuresShouldActuallyPersistToDatabase() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, "wrong-secret")))
                .andExpect(status().isUnauthorized());

        SysUser afterFirst = userMapper.findByPhone(phone);
        assertThat(afterFirst.getFailedCount())
                .as("失败计数必须落库；等于 0 说明事务回滚把计数一起丢掉了")
                .isEqualTo(1);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, "wrong-secret-2")))
                .andExpect(status().isUnauthorized());

        assertThat(userMapper.findByPhone(phone).getFailedCount())
                .as("计数必须累加，而不是每次从头开始")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("账号停用后即使口令正确也返回 403")
    void disabledAccountShouldBeForbidden() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        disable(phone);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, TEST_ACCOUNT_SECRET)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    @DisplayName("登录参数为空返回 400 并指出字段")
    void blankCredentialsShouldFailValidation() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.phone").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    // ------------------------------------------------------------------
    // 受保护接口与令牌
    // ------------------------------------------------------------------

    @Test
    @DisplayName("不带令牌访问 /api/auth/me 返回 401 且是 JSON（不是 Spring 默认的 HTML 错误页）")
    void missingTokenShouldReturnJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("未登记的接口默认要求认证——默认拒绝，而不是默认放行")
    void unlistedEndpointShouldRequireAuth() throws Exception {
        // 这个路径对应 T-004 的科室列表：现在还没实现，
        // 但"未带令牌"必须在进入控制器之前就被拦下（401），而不是 404。
        // 这条测试锁定的是"默认拒绝"这个策略本身。
        mockMvc.perform(get("/api/departments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("令牌被篡改返回 401 且错误码为 TOKEN_INVALID")
    void tamperedTokenShouldReturnInvalid() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        String token = loginAndGetToken(phone, TEST_ACCOUNT_SECRET);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("用别的密钥签的令牌一律无效——签名校验不是可选项")
    void tokenSignedWithAnotherKeyShouldBeRejected() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        SysUser user = userMapper.findByPhone(phone);

        String forged = io.jsonwebtoken.Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer("hospital-appointment")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "attacker-key-attacker-key-attacker-key".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        io.jsonwebtoken.Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("已过期的令牌返回 401 且错误码为 TOKEN_EXPIRED（与伪造令牌区分开）")
    void expiredTokenShouldBeReportedAsExpired() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        SysUser user = userMapper.findByPhone(phone);

        String expired = io.jsonwebtoken.Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer("hospital-appointment")
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 3_600_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-only-secret-must-be-at-least-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        io.jsonwebtoken.Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("垃圾字符串当令牌：返回 401 而不是 500")
    void garbageTokenShouldNotCauseServerError() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("令牌有效但账号已被停用：/me 立刻返回 401（每次回库换来的能力）")
    void meShouldRejectTokenOfDisabledAccount() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        String token = loginAndGetToken(phone, TEST_ACCOUNT_SECRET);

        disable(phone);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    /**
     * 请求方法不被支持（例如用 GET 打一个 POST 端点）。
     *
     * <p>⚠️ 这条是补的，因为原来的行为是 <b>500 "服务器内部错误"</b>——
     * 而"方法用错了"是调用方的问题，正确状态码是 <b>405</b>。
     *
     * <p>它是被边界值探测发现的（用 GET 打 {@code /api/auth/login}）。
     * 与"缺参数返回 500"、"路径不存在返回 500"是同一类问题的第三次出现：
     * 少写一个 {@code @ExceptionHandler}，异常就掉进
     * {@code @ExceptionHandler(Exception.class)} 兜底。
     * <b>兜底处理器保证不漏堆栈，但它把一切归因成"服务端崩了"。</b>
     */
    @Test
    @DisplayName("请求方法不被支持返回 405（不是 500）——方法用错是调用方的问题")
    void wrongHttpMethodShouldReturn405() throws Exception {
        // /api/auth/login 只接受 POST
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        // /api/departments 只接受 GET
        register(phone, TEST_ACCOUNT_SECRET);
        String token = loginAndGetToken(phone, TEST_ACCOUNT_SECRET);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/departments").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /**
     * 容器层的错误也必须用统一格式。
     *
     * <p>这类请求**到不了控制器**（路径匹配不上），Spring Boot 会转发到 {@code /error}。
     * 默认的 {@code BasicErrorController} 会返回它自己的格式
     * （{@code {timestamp, status, error, path}}），
     * 于是同一个 API 出现两种错误格式——而"前端只需要一套解析逻辑"
     * 正是本项目追求的目标。
     *
     * <p>现在由 {@code UnifiedErrorController} 接管，格式与其它错误一致。
     */
    @Test
    @DisplayName("容器层错误（空路径变量）也用统一格式，不是 Spring 默认格式")
    void containerLevelErrorUsesUnifiedFormat() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        String token = loginAndGetToken(phone, TEST_ACCOUNT_SECRET);

        // 空路径变量：匹配不上任何 handler，于是被转发到 /error。
        //
        // ⚠️ 这里**不断言具体是 400 还是 404**：真实 Tomcat 上返回 400，
        //    而 MockMvc 会把 "//" 规范化成 "/"，于是变成 404（找不到 /api/appointments/cancel）。
        //    两者都走 /error，本测试要守的是**格式统一**，不是某个具体状态码——
        //    把断言绑死在状态码上只会让测试对容器的路径规范化行为敏感。
        var response = mockMvc.perform(post("/api/appointments//cancel")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("应当是 4xx（而不是 5xx：路径问题不该表现为服务端崩了）")
                .isBetween(400, 499);

        String body = response.getContentAsString();

        assertThat(body)
                .as("必须是统一错误格式（含 code/message/time），而不是 Spring 默认的 timestamp/error")
                .contains("\"code\"")
                .contains("\"time\"")
                .doesNotContain("\"timestamp\"");
    }

    /**
     * 请求不存在的接口路径。
     *
     * <p>⚠️ 这条是补的，因为原来的行为是 <b>500 "服务器内部错误"</b>——
     * 而"路径打错了"是调用方的问题，不是服务端崩了。
     * 用户看到 500 会去查服务端日志，而真正的原因是他的 URL 拼错了。
     *
     * <p>它是被 T-017 的 Nginx 验证暴露的：当时想确认"未匹配的 /api 路径
     * 是否被正确转发给后端"，结果后端回了一个 500。
     * 与"缺少必填参数返回 500"同一类：<b>少写一个 @ExceptionHandler 不会编译报错，
     * 只会静默返回错的状态码。</b>
     */
    @Test
    @DisplayName("请求不存在的接口返回 404（不是 500）——路径打错是调用方的问题")
    void unknownPathShouldReturn404NotServerError() throws Exception {
        register(phone, TEST_ACCOUNT_SECRET);
        String token = loginAndGetToken(phone, TEST_ACCOUNT_SECRET);

        mockMvc.perform(get("/api/nonexistent-endpoint").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /**
     * 生成一个合法的测试手机号。
     *
     * <p>用 {@code System.nanoTime()} 的后 8 位派生：测试数据必须是<b>互不冲突</b>的
     * （手机号有唯一索引），同时又必须是合法的手机号格式（否则会被参数校验挡掉，
     * 测试就变成了在测校验规则，而不是在测它想测的东西）。
     */
    private String uniquePhone() {
        long n = Math.abs(System.nanoTime() % 100_000_000L);
        return "139" + String.format("%08d", n);
    }

    private void register(String phone, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(phone, password, "测试患者")))
                .andExpect(status().isCreated());
    }

    private void disable(String phone) {
        SysUser user = userMapper.findByPhone(phone);
        user.setEnabled(false);
        userMapper.updateById(user);
    }

    private String registerJson(String phone, String password, String realName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", phone);
        body.put("password", password);
        if (realName != null) {
            body.put("realName", realName);
        }
        return objectMapper.writeValueAsString(body);
    }

    private String loginJson(String phone, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("phone", phone, "password", password));
    }

    private String loginAndGetToken(String phone, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(phone, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
