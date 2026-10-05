package com.demo.hospital.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 挂号业务参数，前缀 {@code app.appointment}。
 *
 * <p>存在的理由是 T-012：延迟队列要按"待支付超时时间"发消息，
 * 而<b>测试里必须能把它改短</b>（例如 5 秒）才能快速验证"到期自动取消"。
 * 如果这个数字硬编码在 Service 里，T-012 的集成测试就只能真的等 15 分钟——
 * 那种测试没人会跑，等于没有。
 *
 * @param paymentTimeoutMinutes 待支付超时时间（分钟）
 */
@ConfigurationProperties(prefix = "app.appointment")
public record AppointmentProperties(
        int paymentTimeoutMinutes
) {

    public AppointmentProperties {
        if (paymentTimeoutMinutes <= 0) {
            throw new IllegalArgumentException("app.appointment.payment-timeout-minutes 必须大于 0");
        }
    }
}
