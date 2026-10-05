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
/**
 * ⚠️ 显式给了 bean 名 {@code mqProperties}，这是**必需的**，不是装饰。
 *
 * <p>{@code @ConfigurationPropertiesScan}（见 HospitalApplication）注册出来的 bean 名
 * 是 {@code "app.mq-com.demo.hospital.config.MqProperties"} 这种带前缀的全限定名，
 * 而 {@code @RabbitListener(queues = "#{@mqProperties.notifyQueue()}")}
 * 里的 SpEL **按名字**找 bean。没有这个显式名，上下文启动会直接失败：
 *
 * <pre>
 * A component required a bean named 'mqProperties' that could not be found.
 * </pre>
 *
 * <p>为什么监听器要用 SpEL 取队列名而不是写常量——见 NotificationConsumer 上的说明：
 * 常量会导致**测试里没有消费者**，而这一点从测试结果里看不出来。
 */
@ConfigurationProperties(prefix = "app.mq")
public record MqProperties(
        boolean enabled,
        long paymentTtlMillis,
        String notifyQueue,
        String delayQueue,
        String cancelQueue,
        String appointmentExchange,
        String delayExchange,
        String deadLetterExchange
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
        // ⚠️ 交换机名也必须可配置，否则**测试与开发无法隔离**。
        //
        // 【为什么】原来三个交换机名是硬编码常量，于是出现这个现象：
        //   测试用 *.test 队列，但消息经过**同一个交换机**，
        //   而交换机上同时绑着 .test 队列与开发队列 ——
        //   一条测试消息被路由到**两个地方**：
        //     · .test 队列（测试的消费者处理，正常）
        //     · 开发队列（测试跑完后没人消费，永久滞留）
        //
        //   实测：跑一遍全量测试，开发队列会多出十几条
        //   "超时测试医生"的消息。它不影响功能，但会污染演示环境——
        //   而演示时正是要打开管理台看这几个队列。
        //
        //   根因是"只隔离了队列、没隔离交换机"。**隔离要一整套，
        //   半套隔离比不隔离更容易让人误以为已经隔离了。**
        if (appointmentExchange == null || appointmentExchange.isBlank()) {
            appointmentExchange = "appointment.exchange";
        }
        if (delayExchange == null || delayExchange.isBlank()) {
            delayExchange = "appointment.delay.exchange";
        }
        if (deadLetterExchange == null || deadLetterExchange.isBlank()) {
            deadLetterExchange = "appointment.dlx";
        }
    }
}
