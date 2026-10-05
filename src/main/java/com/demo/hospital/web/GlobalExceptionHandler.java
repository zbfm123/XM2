package com.demo.hospital.web;

import com.demo.hospital.auth.AuthErrorCode;
import com.demo.hospital.auth.AuthException;
import com.demo.hospital.common.BusinessException;
import com.demo.hospital.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>目标只有一个：<b>让所有错误响应长得一样</b>——{@code {code, message, path, time}}。
 * 前端只需要一套解析逻辑；同时保证 500 类错误不把堆栈泄露出去。
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li><b>状态码在这里集中决定</b>，而不是散落在各控制器里。
 *       {@code AuthException} 只表达业务含义，HTTP 状态码只在这一处映射。</li>
 *   <li><b>状态码的选择依据是"调用方该做什么"</b>，不是"错误听起来多严重"。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthException e, HttpServletRequest request) {
        HttpStatus status = switch (e.getCode()) {
            // 凭据不对、令牌不可用：调用方能做的都是"重新登录/重新输入"
            case BAD_CREDENTIALS, UNAUTHENTICATED, TOKEN_EXPIRED, TOKEN_INVALID -> HttpStatus.UNAUTHORIZED;
            // 423 Locked：不是"你没权限"，而是"现在不行，等一会儿就行"——这是一个可等待的状态
            case ACCOUNT_LOCKED -> HttpStatus.LOCKED;
            // 403：账号被停用是"你有身份但不能用"，与凭据错误不同
            case ACCOUNT_DISABLED -> HttpStatus.FORBIDDEN;
            // 409 Conflict：请求本身没错，是目标状态已被占用。前端应提示"请直接登录"
            case PHONE_ALREADY_REGISTERED -> HttpStatus.CONFLICT;
            // 缺登录上下文属于实现缺陷，不是用户问题
            case AUTH_CONTEXT_MISSING -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        Map<String, Object> body = base(e.getCode().name(), e.getMessage(), request);
        if (e.getUnlockAtEpochSeconds() != null) {
            body.put("unlockAt", e.getUnlockAtEpochSeconds());
        }

        // 日志级别按"是否值得人去看"分：
        //   锁定是正常的业务事件（有人在试口令，但我们处理了）→ INFO
        //   令牌无效可能是伪造尝试 → WARN
        if (e.getCode() == AuthErrorCode.ACCOUNT_LOCKED) {
            log.info("账号锁定: {} {}", request.getRequestURI(), e.getMessage());
        } else if (e.getCode() == AuthErrorCode.TOKEN_INVALID) {
            log.warn("令牌异常: {} {}", request.getRequestURI(), e.getMessage());
        }

        return ResponseEntity.status(status).body(body);
    }

    /**
     * 业务异常。状态码按"调用方该做什么"决定：
     * <ul>
     *   <li>参数错了 / 当前状态不允许 → 400、409，改一下请求就能成功</li>
     *   <li>资源不存在 → 404</li>
     *   <li>号源已满 / 已经挂过 → 409 Conflict：这不是"你错了"，
     *       而是"你要的东西现在没有了"。前端据此提示"已约满"而不是"操作失败"</li>
     * </ul>
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e,
                                                              HttpServletRequest request) {
        HttpStatus status = switch (e.getCode()) {
            case INVALID_PARAMETER -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_STATE, NO_SLOTS_AVAILABLE, ALREADY_BOOKED -> HttpStatus.CONFLICT;
        };

        // 这些是预期内的业务分支（有人抢不到号是正常的），用 INFO 而不是 ERROR：
        // 用 ERROR 记录正常的业务失败，会淹没真正需要看的错误。
        log.info("业务请求被拒绝: {} {} -> {} {}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());

        return ResponseEntity.status(status).body(base(e.getCode().name(), e.getMessage(), request));
    }

    /**
     * 请求了不存在的接口路径，例如 {@code GET /api/nonexistent}。
     *
     * <p>⚠️ 这个分支是补上的，之前会掉进兜底变成 <b>500 "服务器内部错误"</b>——
     * 但**路径打错了是调用方的问题，不是服务端崩了**。
     * 用户看到 500 会去查服务端日志，而真正的原因是他的 URL 拼错了。
     *
     * <p>它是被 T-017 的 Nginx 验证暴露出来的：当时想确认"未匹配的 /api 路径
     * 是否被正确转发给后端"，结果后端回了一个 500。
     * 这与之前"缺少必填参数返回 500"是同一类问题：
     * <b>少写一个 @ExceptionHandler 不会编译报错，只会静默返回错的状态码。</b>
     *
     * <p>Spring 6.1 / Boot 3.2 起，静态资源未命中抛的是
     * {@code NoResourceFoundException}（不再有默认的 404 转发）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            NoResourceFoundException e, HttpServletRequest request) {
        log.info("请求了不存在的路径: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(base(ErrorCode.NOT_FOUND.name(), "接口不存在：" + request.getRequestURI(), request));
    }

    /**
     * 缺少必填的请求参数，例如 {@code GET /api/doctors} 没带 {@code deptId}。
     *
     * <p>⚠️ 这个分支是补上的，之前会掉进兜底变成 <b>500</b>：<b>调用方少传一个参数，
     * 却得到"服务器内部错误"</b>——前端会去查服务端日志，而真正的原因在自己身上。
     * 是 T-004 的测试（{@code missingDeptIdShouldReturn400}）把它暴露出来的。
     *
     * <p>这也说明"状态码应当反映谁该负责"这条纪律需要用测试去守：
     * 少写一个 {@code @ExceptionHandler} 不会编译报错，只会静默地返回错的状态码。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException e, HttpServletRequest request) {
        log.info("缺少必填参数: {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(base(ErrorCode.INVALID_PARAMETER.name(),
                        "缺少必填参数：" + e.getParameterName(), request));
    }

    /**
     * 参数类型不对，例如 {@code ?page=abc}、路径变量不是数字。
     *
     * <p>单独处理是必须的：不处理就会落到兜底分支变成 500，
     * 让"用户把参数写错了"看起来像"服务端崩了"。
     * <b>状态码应当反映谁该负责。</b>
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e, HttpServletRequest request) {
        log.info("请求无法解析: {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(base(ErrorCode.INVALID_PARAMETER.name(), "请求参数格式不正确", request));
    }

    /** 参数校验失败：把所有字段错误<b>一次性</b>返回，避免前端"改一个报一个"。 */    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e,
                                                               HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

        Map<String, Object> body = base("VALIDATION_FAILED", "请求参数不合法", request);
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 取不到登录上下文。
     *
     * <p>这属于实现缺陷（该被拦的请求没被拦住），明确记 error，
     * 并且<b>不返回 500 的通用文案</b>——返回一个可识别的错误码，
     * 排查时一眼能看出是"过滤器没跑"还是"真的崩了"。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e,
                                                                  HttpServletRequest request) {
        log.error("状态异常（疑似缺少登录上下文）: {} {}", request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(base(AuthErrorCode.AUTH_CONTEXT_MISSING.name(), "会话上下文缺失，请重新登录", request));
    }

    /**
     * 兜底。
     *
     * <p><b>刻意返回泛化消息</b>：异常细节（表名、路径、SQL 片段）只进服务端日志。
     * 把原始消息返回给客户端，一次未预期的异常就变成了一次信息泄露。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未处理异常: {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(base("INTERNAL_ERROR", "服务器内部错误", request));
    }

    private Map<String, Object> base(String code, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("time", OffsetDateTime.now().toString());
        return body;
    }
}
