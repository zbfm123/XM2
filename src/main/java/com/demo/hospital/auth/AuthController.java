package com.demo.hospital.auth;

import com.demo.hospital.auth.domain.CurrentUser;
import com.demo.hospital.auth.domain.IssuedToken;
import com.demo.hospital.auth.dto.LoginRequest;
import com.demo.hospital.auth.dto.LoginResponse;
import com.demo.hospital.auth.dto.RegisterRequest;
import com.demo.hospital.auth.dto.UserView;
import com.demo.hospital.user.domain.SysUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>端点与鉴权要求（白名单见 {@code SecurityConfig}）：
 * <ul>
 *   <li>{@code POST /api/auth/register} —— 匿名</li>
 *   <li>{@code POST /api/auth/login} —— 匿名</li>
 *   <li>{@code GET  /api/auth/me} —— <b>需登录</b></li>
 * </ul>
 *
 * <p>这个类刻意<b>不含任何业务判断</b>：它只做"HTTP 与领域之间"的翻译
 * （解析请求体、把实体收敛成 {@link UserView}、给对的状态码）。
 * 把校验顺序、锁定逻辑放在 Service 里，才能被单元测试直接覆盖，
 * 而不是只能通过发 HTTP 请求来验证。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册。
     *
     * <p>返回 <b>201 Created</b> 而不是 200，且<b>不返回令牌</b>：
     * 注册成功不等于登录。让前端显式再调一次登录，
     * 少一条"令牌从哪来的"分支（这也是验收 A-01 的流程：注册 → 登录 → 带令牌访问）。
     */
    @PostMapping("/register")
    public ResponseEntity<UserView> register(@Valid @RequestBody RegisterRequest request) {
        SysUser user = authService.register(request.phone(), request.password(), request.realName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(user));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.phone(), request.password());
        IssuedToken issued = result.token();

        return ResponseEntity.ok(LoginResponse.bearer(
                issued.token(),
                issued.expiresIn(),
                issued.expiresAt().getEpochSecond(),
                toView(result.user())));
    }

    /**
     * 当前用户信息。
     *
     * <p>前端刷新页面后用它恢复登录状态：令牌还在且接口返回 200，就是"仍然登录着"。
     */
    @GetMapping("/me")
    public ResponseEntity<UserView> me() {
        // require() 而不是 get()：这个端点没有"没有用户"这种合法情况。
        // 真出现了就是过滤器没跑起来——宁可 500 也不要静默返回 null。
        CurrentUser current = CurrentUser.require();
        return ResponseEntity.ok(toView(authService.currentUser(current.getUserId())));
    }

    /**
     * 实体 → 对外视图。
     *
     * <p>转换放在这里而不是给实体加注解：<b>实体是用来存数据的，视图是用来出网的</b>。
     * 混在一起的结果是"改一个字段的存储方式，可能顺带改了对外接口"。
     */
    private UserView toView(SysUser user) {
        return new UserView(user.getId(), user.getPhone(), user.getRealName(), user.getIdCard());
    }
}
