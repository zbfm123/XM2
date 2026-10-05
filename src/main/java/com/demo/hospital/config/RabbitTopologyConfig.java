package com.demo.hospital.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列与交换机声明（T-010 / T-012）。
 *
 * <h2>拓扑总览</h2>
 *
 * <pre>
 *   挂号成功 ──→ appointment.exchange (topic)
 *                     │ routing key: appointment.created
 *                     ▼
 *              appointment.notify.queue ──→ 消费者写 notification 表
 *
 *   下单时 ──→ appointment.delay.exchange
 *                     │ routing key: appointment.delay
 *                     ▼
 *        appointment.delay.queue  (TTL = 15 分钟，**故意没有消费者**)
 *                     │ 消息过期（TTL 到期）
 *                     ▼ 成为死信，按 DLX 转发
 *            appointment.dlx (direct)
 *                     │ routing key: appointment.cancel
 *                     ▼
 *              appointment.cancel.queue ──→ 消费者：仍未支付则取消并归还号源
 * </pre>
 *
 * <h2>为什么"延迟队列"要这么绕（TTL + DLX 而不是延迟插件）</h2>
 *
 * RabbitMQ 本身<b>没有</b>延迟队列。社区有 {@code rabbitmq_delayed_message_exchange}
 * 插件能做，但决策 D-05 选了 TTL + 死信交换机，理由有两个：
 * <ol>
 *   <li>插件需要额外安装与运维（本机环境已经够多组件了）；</li>
 *   <li>DLX 是标准机制，<b>面试更常被问</b>，而且它体现的是"理解 AMQP 的机制"
 *       而不是"装了一个插件"。</li>
 * </ol>
 *
 * <p>关键点在于：<b>延迟队列自己不消费</b>。消息在里面放够 TTL 之后过期，
 * 过期消息按队列的 {@code x-dead-letter-exchange} 转发到 DLX，再进取消队列。
 * 它就是一个"用时间换延迟"的定时器。
 *
 * <h2>⚠️ 为什么整份配置加了 @ConditionalOnProperty</h2>
 *
 * 因为本项目有一条硬需求（A-07）：<b>MQ 不可用时挂号必须仍然成功</b>。
 * 如果队列声明是无条件的，那么在 {@code app.mq.enabled=false}（或测试环境）下，
 * 应用启动时就会去连 broker；连不上就抛异常，连"启动"这一步都过不去。
 * 那样 A-07 就成了空话——一个启动不起来的东西谈不上"主流程不受影响"。
 *
 * <p>所以整套 MQ 组件（拓扑、发布者、监听器）全部挂在 {@code app.mq.enabled} 这个开关下，
 * 默认关闭；只有明确打开时才生效。<b>默认关闭也让 {@code mvn test} 不依赖 broker。</b>
 */
@Configuration
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class RabbitTopologyConfig {

    private final MqProperties mqProperties;

    public RabbitTopologyConfig(MqProperties mqProperties) {
        this.mqProperties = mqProperties;
    }

    // ------------------------------------------------------------------
    // 通知（T-010）
    // ------------------------------------------------------------------

    public static final String APPOINTMENT_EXCHANGE = "appointment.exchange";
    public static final String NOTIFY_QUEUE = "appointment.notify.queue";
    public static final String ROUTING_CREATED = "appointment.created";
    public static final String ROUTING_CANCELLED = "appointment.cancelled";

    /**
     * 业务交换机。用 topic 而不是 direct：现在只有两个 routing key，
     * 但将来加"提醒/改期"时不必新建交换机，只要多一个 key 和一个绑定。
     */
    @Bean
    public TopicExchange appointmentExchange() {
        // durable=true：broker 重启后交换机还在。
        // autoDelete=false：不能因为"暂时没有队列绑定"就自动删掉——
        // 那会在服务重启的空窗期把交换机弄丢。
        return new TopicExchange(APPOINTMENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(NOTIFY_QUEUE).build();
    }

    @Bean
    public Binding notifyCreatedBinding(Queue notifyQueue, TopicExchange appointmentExchange) {
        return BindingBuilder.bind(notifyQueue).to(appointmentExchange).with(ROUTING_CREATED);
    }

    @Bean
    public Binding notifyCancelledBinding(Queue notifyQueue, TopicExchange appointmentExchange) {
        return BindingBuilder.bind(notifyQueue).to(appointmentExchange).with(ROUTING_CANCELLED);
    }

    // ------------------------------------------------------------------
    // 延迟取消（T-012）
    // ------------------------------------------------------------------

    public static final String DELAY_EXCHANGE = "appointment.delay.exchange";
    public static final String DELAY_QUEUE = "appointment.delay.queue";
    public static final String DLX = "appointment.dlx";
    public static final String CANCEL_QUEUE = "appointment.cancel.queue";
    public static final String ROUTING_DELAY = "appointment.delay";
    public static final String ROUTING_CANCEL = "appointment.cancel";

    @Bean
    public DirectExchange delayExchange() {
        return new DirectExchange(DELAY_EXCHANGE, true, false);
    }

    /**
     * 延迟队列：<b>设 TTL，但不给它任何消费者</b>。
     *
     * <p>这正是"延迟"的实现方式——没有消费者，消息就只能等到过期，
     * 过期后按死信配置被转发出去。
     *
     * <p>⚠️ {@code x-dead-letter-routing-key} 与 {@code x-dead-letter-exchange}
     * 必须成对声明。只给 exchange 的话，死信会带着**原来的 routing key**
     * （{@code appointment.delay}）转发到 DLX，而 DLX 上没有任何队列绑定这个 key，
     * 消息会被静默丢弃——<b>表现为"15 分钟后什么都没发生"，且没有任何错误日志。</b>
     */
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                // ⚠️ QueueBuilder.ttl 的签名是 ttl(int)，而配置里用 long 更自然
                //    （毫秒数容易写成大数字）。这里显式转换并注明上界：
                //    int 毫秒上限约 24.8 天，超过它 TTL 会溢出成负数——
                //    负 TTL 的队列会让消息**立刻**过期，表现为"下单后秒取消"。
                //    所以这里先卡住范围，而不是让溢出去制造一个诡异的现象。
                .ttl(toTtlMillis())
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(ROUTING_CANCEL)
                .build();
    }

    @Bean
    public Binding delayBinding(Queue delayQueue, DirectExchange delayExchange) {
        return BindingBuilder.bind(delayQueue).to(delayExchange).with(ROUTING_DELAY);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue cancelQueue() {
        return QueueBuilder.durable(CANCEL_QUEUE).build();
    }

    @Bean
    public Binding cancelBinding(Queue cancelQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(cancelQueue).to(deadLetterExchange).with(ROUTING_CANCEL);
    }

    /**
     * 延迟时间（毫秒），并保证落在 int 可表达的范围内。
     *
     * <p>⚠️ 在<b>声明队列时</b>就把 TTL 固化到队列参数上，而不是发消息时逐条设置
     * ({@code MessagePostProcessor})。原因是：
     * <ul>
     *   <li>队列级 TTL 是<b>队列属性</b>，一眼能在管理台看到"这个队列是 15 分钟"；</li>
     *   <li>消息级 TTL 在"先入队的消息 TTL 更长"时会<b>阻塞后面的消息</b>——
     *       RabbitMQ 只检查队首消息是否过期。这是个著名陷阱，能避开就避开。</li>
     * </ul>
     *
     * <p>代价是改 TTL 需要重建队列（删除后重声明）。对本地演示完全可以接受，
     * 而且这个取舍本身值得在面试里讲。
     *
     * <p>值来自 {@code app.mq.payment-ttl-millis}（见 {@link MqProperties}），
     * 这样测试可以把 15 分钟改成 2 秒来验证"到期自动取消"，
     * 而不必真的等 15 分钟——<b>一条要等 15 分钟的测试，没有人会去跑它。</b>
     */
    private int toTtlMillis() {
        long configured = mqProperties.paymentTtlMillis();
        long max = Integer.MAX_VALUE;                  // ≈ 24.8 天
        if (configured > max) {
            throw new IllegalStateException(
                    "app.mq.payment-ttl-millis 过大（" + configured + "），上限为 " + max
                            + " 毫秒（约 24.8 天）。超过它 TTL 会溢出成负数，"
                            + "导致消息立刻过期、订单一创建就被取消。");
        }
        return (int) configured;
    }
}
