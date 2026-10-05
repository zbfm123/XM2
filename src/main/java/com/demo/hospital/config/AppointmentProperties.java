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
 * <h2>⚠️ 为什么是 {@code double} 而不是 {@code int}</h2>
 *
 * 原本是 {@code int paymentTimeoutMinutes}。问题在于<b>测试需要秒级的时限</b>：
 *
 * <ul>
 *   <li>队列 TTL（{@code app.mq.payment-ttl-millis}）设成 1 秒时，
 *       业务时限如果只能是"1 分钟起"，两者就<b>必然对不上</b>；
 *   <li>而两者对不上会被 {@code AppointmentService.cancelOnTimeout}
 *       的时限校验拦住，导致"到期自动取消"这条链路测不出来。
 * </ul>
 *
 * <p>改成 {@code double} 之后可以配 {@code 0.05}（= 3 秒），
 * 让业务时限与队列 TTL 精确对齐。**单位仍是分钟**，所以 {@code 15} 的写法不变。
 *
 * <p>⚠️ 这两个值必须对齐才有意义：
 * <pre>
 *   app.appointment.payment-timeout-minutes  -> 订单的 expire_at（业务时限，权威）
 *   app.mq.payment-ttl-millis                -> 延迟队列的消息 TTL（投递延迟）
 * </pre>
 * 业务时限短于队列 TTL -> 取消会迟到；长于队列 TTL -> 时限校验会拦住（记 WARN）。
 *
 * @param paymentTimeoutMinutes 待支付超时时间（分钟，支持小数以便测试用秒级）
 */
@ConfigurationProperties(prefix = "app.appointment")
public record AppointmentProperties(
        double paymentTimeoutMinutes
) {

    public AppointmentProperties {
        if (paymentTimeoutMinutes <= 0) {
            throw new IllegalArgumentException("app.appointment.payment-timeout-minutes 必须大于 0");
        }
    }

    /** 折算成秒，供 {@code LocalDateTime.plusSeconds} 使用（保留小数精度）。 */
    public double paymentTimeoutSeconds() {
        return paymentTimeoutMinutes * 60.0;
    }
}
