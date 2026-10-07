package com.demo.hospital.appointment;

import com.demo.hospital.appointment.domain.AppointmentStatus;
import com.demo.hospital.appointment.dto.AppointmentView;
import com.demo.hospital.appointment.dto.BookRequest;
import com.demo.hospital.appointment.dto.AdvanceRequest;
import com.demo.hospital.appointment.dto.CancelRequest;
import com.demo.hospital.auth.domain.CurrentUser;
import com.demo.hospital.common.PageResult;
import com.demo.hospital.config.AppointmentProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 挂号接口。
 *
 * <p>五个端点（列表曾在只有三个端点时写成「三个端点」，后来补了支付与完成没同步改）：
 * <ul>
 *   <li>{@code POST /api/appointments} —— 提交挂号（幂等，A-04）</li>
 *   <li>{@code POST /api/appointments/{no}/cancel} —— 取消并归还号源（A-05）</li>
 *   <li>{@code POST /api/appointments/{no}/pay} —— 模拟支付成功，推进到 PAID</li>
 *   <li>{@code POST /api/appointments/{no}/complete} —— 就诊完成，推进到 COMPLETED</li>
 *   <li>{@code GET  /api/appointments} —— 我的挂号（只能看到自己的）</li>
 * </ul>
 *
 * <p>⚠️ {@code /pay} 与 {@code /complete} 是<b>模拟</b>端到端流程用的，
 * 不是真实支付网关回调：本项目的重点是并发正确性与状态机，
 * 真接支付会把范围撑开却稀释重点。
 *
 * <p>鉴权：全部<b>需登录</b>，且未在白名单里登记 —— 由 {@code SecurityConfig}
 * 的默认拒绝自动兜住，不需要为本接口改任何安全配置。
 *
 * <p>⚠️ 所有端点都<b>不接受 userId 参数</b>：用户 id 一律从
 * {@link CurrentUser}（由 JWT 过滤器写入的登录上下文）取出。
 * 如果做成参数，那么"越权查别人的挂号"就只差一次参数篡改。
 * <b>不该由调用方决定的事，就不要给它这个参数。</b>
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final AppointmentProperties appointmentProperties;

    public AppointmentController(AppointmentService appointmentService,
                                AppointmentProperties appointmentProperties) {
        this.appointmentService = appointmentService;
        this.appointmentProperties = appointmentProperties;
    }

    /**
     * 提交挂号。
     *
     * <p>⚠️ 返回 <b>200 而不是 201</b>，这是刻意的：
     * 幂等重放时并没有"创建"任何东西（订单早就存在），
     * 返回 201 会等于对客户端说"我新建了一个"——那是假的。
     *
     * <p>区分两种情况的正确方式是响应体里的 {@code replayed} 字段：
     * {@code true} = 这个订单早就存在，我没有新建；
     * {@code false} = 本次真的创建了订单。
     * <b>把语义放在数据里，而不是放在状态码的细微差别里</b>，
     * 前端和测试都更容易处理。
     */
    @PostMapping
    public ResponseEntity<AppointmentView> book(@Valid @RequestBody BookRequest request) {
        Long userId = CurrentUser.require().getUserId();
        AppointmentView view = appointmentService.book(
                userId, request.scheduleId(), request.idempotencyKey(),
                appointmentProperties.paymentTimeoutMinutes());
        return ResponseEntity.ok(view);
    }

    /**
     * 取消挂号（归还号源）。
     *
     * <p>单号放在路径里而不是请求体里：它是一个<b>资源标识</b>，
     * 而"取消这个资源"这个动作作用于它。放进请求体只是把同样的信息挪了个位置，
     * 却失去了"路径即资源"的可读性与可缓存性。
     *
     * <p>{@code reason} 可选，请求体可以为空。
     */
    @PostMapping("/{appointmentNo}/cancel")
    public ResponseEntity<AppointmentView> cancel(
            @PathVariable("appointmentNo") String appointmentNo,
            @RequestBody(required = false) CancelRequest request) {
        Long userId = CurrentUser.require().getUserId();
        String reason = (request == null || request.reason() == null || request.reason().isBlank())
                ? "用户主动取消"
                : request.reason().trim();
        return ResponseEntity.ok(appointmentService.cancel(userId, appointmentNo, reason));
    }

    /**
     * 模拟支付回调（决策 D-07：不接真实支付）。
     *
     * <h2>为什么需要它（它不在任务书里，是补的）</h2>
     *
     * 任务书没列支付任务，但没有这个入口，状态机里的 {@code PAID} 与
     * {@code COMPLETED} 就是**不可达的**——只有测试手动改库才能到。
     *
     * <p>更要紧的是演示：「15 分钟未支付自动取消」这条链路只讲了一半。
     * 另一半是**"已支付的订单不会被误取消"**，而如果到不了 {@code PAID}，
     * 这一半就演示不出来。
     *
     * <p>⚠️ **真实系统里这个端点绝不能长这样**：必须校验支付平台的签名与金额，
     * 否则任何人构造一个请求就能把订单标记成已支付。
     * 本项目的支付是主动砍掉的（docs/01 的"不做清单"），
     * 这个端点只在本地演示环境存在，且命名上直接叫 {@code pay}（模拟）。
     */
    @PostMapping("/{appointmentNo}/pay")
    public ResponseEntity<AppointmentView> pay(
            @PathVariable("appointmentNo") String appointmentNo,
            @RequestBody(required = false) AdvanceRequest request) {
        Long userId = CurrentUser.require().getUserId();
        String note = (request == null || request.note() == null || request.note().isBlank())
                ? "模拟支付回调"
                : request.note().trim();
        return ResponseEntity.ok(appointmentService.advanceStatus(
                userId, appointmentNo, AppointmentStatus.PAID, note));
    }

    /**
     * 标记已就诊完成（{@code PAID → COMPLETED}）。
     *
     * <p>它是状态机里唯一还能往前走的一步，也是"终态无出边"那条不变量的
     * 正向对照：走完这一步之后，订单就再也变不了了（不能取消、不能回退）。
     *
     * <p>真实系统里这一步通常由 HIS（医院信息系统）的对接回调触发，
     * 或由定时任务扫描"就诊日期已过的 PAID 订单"批量推进。这里同样做成显式端点，
     * 便于演示与测试。
     */
    @PostMapping("/{appointmentNo}/complete")
    public ResponseEntity<AppointmentView> complete(
            @PathVariable("appointmentNo") String appointmentNo,
            @RequestBody(required = false) AdvanceRequest request) {
        Long userId = CurrentUser.require().getUserId();
        String note = (request == null || request.note() == null || request.note().isBlank())
                ? "就诊完成"
                : request.note().trim();
        return ResponseEntity.ok(appointmentService.advanceStatus(
                userId, appointmentNo, AppointmentStatus.COMPLETED, note));
    }

    /**
     * 我的挂号列表。
     *
     * <p>{@code status} 可选，取值即 {@link AppointmentStatus} 的枚举名
     * （{@code PENDING_PAYMENT} / {@code PAID} / {@code COMPLETED} / {@code CANCELLED}）。
     * 传了非法值会由 {@code GlobalExceptionHandler} 映射成 400
     * （{@code MethodArgumentTypeMismatchException}），而不是 500。
     */
    @GetMapping
    public PageResult<AppointmentView> listMine(
            @RequestParam(value = "status", required = false) AppointmentStatus status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Long userId = CurrentUser.require().getUserId();
        return appointmentService.listMine(userId, status, page, size);
    }
}
