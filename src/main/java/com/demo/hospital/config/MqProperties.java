package com.demo.hospital.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ 相关配置，前缀 {@code app.mq}。
 *
 * @param enabled          是否启用 MQ。<b>默认 false</b>：这样 {@code mvn test}
 *                         和"没有装 RabbitMQ 的机器"都能正常启动与开发
 * @param paymentTtlMillis 延迟队列的 TTL（毫秒）。默认 15 分钟。
 *                         ⚠️ 测试里必须能改短，否则"到期自动取消"的测试要真的等 15 分钟——
 *                         那种测试没有人会跑，等于没有
 * @param maxRetries       消费失败时的最大重试次数
 */
@ConfigurationProperties(prefix = "app.mq")
public record MqProperties(
        boolean enabled,
        long paymentTtlMillis,
        int maxRetries
) {

    public MqProperties {
        if (paymentTtlMillis <= 0) {
            paymentTtlMillis = 15 * 60 * 1000L;
        }
        if (maxRetries <= 0) {
            maxRetries = 3;
        }
    }
}
