package com.demo.hospital.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ 相关配置，前缀 {@code app.mq}。
 *
 * @param enabled            是否启用 MQ。<b>默认 false</b>：这样 {@code mvn test}
 *                           和"没有装 RabbitMQ 的机器"都能正常启动与开发（A-07 的另一面）
 * @param paymentTtlMillis   延迟队列 TTL（毫秒）。默认 15 分钟。
 *                           ⚠️ 必须能改短，否则"到期自动取消"的测试要真的等 15 分钟——
 *                           <b>一条要等 15 分钟的测试，没有人会去跑它</b>
 * @param notifyQueue        通知队列名
 * @param delayQueue         延迟队列名
 * @param cancelQueue        取消队列名
 */
@ConfigurationProperties(prefix = "app.mq")
public record MqProperties(
        boolean enabled,
        long paymentTtlMillis,
        String notifyQueue,
        String delayQueue,
        String cancelQueue
) {

    public MqProperties {
        if (paymentTtlMillis <= 0) {
            paymentTtlMillis = 15 * 60 * 1000L;
        }
        // 默认名兜底：允许只配置关心的那几项
        if (notifyQueue == null || notifyQueue.isBlank()) {
            notifyQueue = "appointment.notify.queue";
        }
        if (delayQueue == null || delayQueue.isBlank()) {
            delayQueue = "appointment.delay.queue";
        }
        if (cancelQueue == null || cancelQueue.isBlank()) {
            cancelQueue = "appointment.cancel.queue";
        }
    }
}
