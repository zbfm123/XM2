package com.demo.hospital.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应。
 *
 * <h2>为什么需要它（它替代了 Spring Boot 默认的 BasicErrorController）</h2>
 *
 * {@code GlobalExceptionHandler} 只能处理**进入控制器之后**抛出的异常。
 * 有一类错误**根本到不了控制器**：
 *
 * <ul>
 *   <li>请求路径匹配不上任何 handler（例如 {@code POST /api/appointments//cancel}，空路径变量）</li>
 *   <li>Servlet 容器层直接拒绝（如非法 URL 编码）</li>
 * </ul>
 *
 * 这些情况下 Spring Boot 会把请求转发到 {@code /error}，由默认的
 * {@code BasicErrorController} 返回它自己的格式：
 *
 * <pre>
 * {"timestamp":"...","status":400,"error":"Bad Request","path":"..."}
 * </pre>
 *
 * <p><b>于是同一个 API 出现了两种错误格式</b>——而"前端只需要一套解析逻辑"
 * 正是本项目在 `GlobalExceptionHandler` 里明确追求的目标。
 * 两种格式并存会让前端必须写分支去嗅探"这次是哪种"。
 *
 * <h2>它是怎么被发现的</h2>
 *
 * 做边界值探测时试了 {@code POST /api/appointments//cancel}（空单号），
 * 返回的是 Spring 默认格式而不是 {@code {code, message, path, time}}。
 * **这类问题不会出现在任何单测里**——测试都是打正常路径，
 * 而它只在"路径根本没匹配上"时触发。
 *
 * <h2>默认错误格式还有个更实际的问题</h2>
 *
 * <b>它把原始异常消息放进响应体</b>（{@code message} 字段），
 * 而本项目在 {@code GlobalExceptionHandler} 里刻意对 500 类错误返回泛化文案——
 * 异常细节可能含表名、SQL 片段、文件路径。默认控制器不受这条纪律约束。
 */
@RestController
public class UnifiedErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(UnifiedErrorController.class);

    /**
     * 接管 {@code /error}。
     *
     * <p>注意这里拿的是 {@link RequestDispatcher#ERROR_STATUS_CODE} 属性，
     * 而不是自己判断异常类型——错误已经发生，这一层只负责**把状态码翻译成统一的响应体**。
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<Map<String, Object>> handleError(HttpServletRequest request) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr == null ? 500 : Integer.parseInt(statusAttr.toString());

        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // 状态码 → 本项目的错误码。与 GlobalExceptionHandler 的映射保持同一套词汇。
        String code;
        String message;
        switch (httpStatus) {
            case NOT_FOUND -> {
                code = "NOT_FOUND";
                message = "接口不存在：" + request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            }
            case METHOD_NOT_ALLOWED -> {
                code = "METHOD_NOT_ALLOWED";
                message = "请求方法不被支持";
            }
            case BAD_REQUEST -> {
                code = "INVALID_PARAMETER";
                message = "请求参数格式不正确";
            }
            case UNAUTHORIZED -> {
                code = "UNAUTHENTICATED";
                message = "请先登录";
            }
            case FORBIDDEN -> {
                code = "FORBIDDEN";
                message = "没有访问权限";
            }
            default -> {
                code = "INTERNAL_ERROR";
                // ⚠️ 刻意不带上原始异常消息（与 GlobalExceptionHandler 同一条纪律）：
                //    细节可能含表名、SQL 片段、文件路径，只进服务端日志。
                message = httpStatus.is5xxServerError() ? "服务器内部错误" : "请求无法处理";
            }
        }

        // 4xx 是调用方的问题，INFO 足够；5xx 才是要人去查的
        if (httpStatus.is5xxServerError()) {
            log.error("容器层错误: status={} uri={}", status,
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                    request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        } else {
            log.info("容器层拒绝请求: status={} uri={}", status,
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        body.put("time", OffsetDateTime.now().toString());

        return ResponseEntity.status(httpStatus).body(body);
    }
}
