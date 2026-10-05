package com.demo.hospital.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 给队列名配置提供一个**名字稳定**的 bean，供 {@code @RabbitListener} 的 SpEL 使用。
 *
 * <h2>为什么需要它（一个绕不开的细节）</h2>
 *
 * 两个消费者的队列名必须**可配置**：
 *
 * <pre>
 *   &#64;RabbitListener(queues = "#{@queueNames.notify()}")
 *   &#64;RabbitListener(queues = "#{@queueNames.cancel()}")
 * </pre>
 *
 * <p>为什么不能写 {@code RabbitTopologyConfig.DEFAULT_NOTIFY_QUEUE} 这个常量——
 * 因为测试用的是 {@code *.test} 后缀的队列。写常量的后果是
 * <b>测试里根本没有消费者</b>：消息投进 {@code .test} 队列后就一直躺着，
 * 而测试恰好也没断言通知，于是"全绿"却完全没验证到消费者。
 * 这个问题只有去 RabbitMQ 管理台才看得出来（.test 队列里堆着几十条没人消费的消息）。
 *
 * <h2>为什么不直接在 SpEL 里引用 {@code MqProperties}</h2>
 *
 * {@code @ConfigurationPropertiesScan} 注册的 bean 名是
 * {@code "app.mq-com.demo.hospital.config.MqProperties"} 这种带前缀的全限定名，
 * SpEL 里写这个名字既丑又脆（改包名就断）。
 *
 * <p>也试过给 {@code MqProperties} 加 {@code @Component("mqProperties")}，
 * 但 Spring Boot 3 会直接拒绝：
 * <blockquote>
 * MqProperties is annotated with &#64;ConstructorBinding but it is defined as a
 * regular bean which caused dependency injection to fail.
 * </blockquote>
 *
 * <p>所以这里用一个 {@code @Bean} 方法暴露**稳定的名字** {@code queueNames}，
 * 内部仍然委托给 {@code MqProperties}。bean 名成了显式契约，
 * 不再依赖自动生成的命名规则。
 *
 * <h2>为什么用显式 bean 而不是让消费者构造器注入</h2>
 *
 * {@code @RabbitListener} 的 {@code queues} 属性只接受编译期常量或 SpEL，
 * <b>不能</b>写成 {@code this.mqProperties.notifyQueue()}。
 * 要绕开 SpEL 就得手写 {@code SimpleMessageListenerContainer}，
 * 那会丢掉 {@code @RabbitListener} 的全部便利（自动重连、异常处理、并发度配置）。
 * 用一个三行的 {@code @Bean} 换回这些，是划算的。
 */
@Configuration
public class QueueNameConfig {

    /**
     * 队列名的名字稳定别名。
     *
     * <p>bean 名 {@code queueNames} 被两个消费者类的 SpEL 引用，
     * **改这里就要同步改那两处**（所以两处都写了注释指回这里）。
     */
    @Bean("queueNames")
    public QueueNames queueNames(MqProperties mqProperties) {
        return new QueueNames(mqProperties.notifyQueue(), mqProperties.delayQueue(),
                mqProperties.cancelQueue());
    }

    /**
     * 三个队列名。
     *
     * <p>record 的访问器就是 {@code notifyQueueName()} 这样的方法，
     * 所以 SpEL 里写成 {@code @queueNames.notifyQueueName()}：
     * 外层 {@code #{...}} 求值一次得到字符串，正好是 {@code queues} 属性要的东西。
     *
     * <p>⚠️ 第一版写成返回 {@code Supplier<String>} 的 {@code notify()}，
     * 结果 SpEL 求值出来是个 Supplier 对象而不是队列名——
     * 而 {@code queues} 属性只接受 String。**能被 SpEL 求值 ≠ 求值结果对**，
     * 这种错误在编译期完全看不出来。
     */
    public record QueueNames(String notifyQueueName, String delayQueueName, String cancelQueueName) {
    }
}
