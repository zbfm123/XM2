package com.demo.hospital.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 *
 * <p>为什么这么小的东西要单独存在：<b>部署脚本与 Nginx 需要一个"只验证进程活着"的端点。</b>
 * 用业务接口来探活是错的——业务接口需要认证，而且它的失败可能只是数据问题，
 * 不代表进程不可用。
 *
 * <p>它在安全白名单里（见 {@code SecurityConfig}）。放行的理由是：
 * 这个响应里<b>没有任何业务数据</b>，只有"我在跑"这一个事实。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("app", "hospital-appointment");
        body.put("time", OffsetDateTime.now().toString());
        return body;
    }
}
