package com.demo.hospital.notification;

import com.demo.hospital.config.RabbitTopologyConfig;
import com.demo.hospital.notification.mq.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基于 RabbitMQ 的通知实现。
 *
 * <h2>这个类里最重要的东西不是"怎么发消息"，而是"发失败时怎么办"</h2>
 *
 * 每一个方法都是同样的结构：<b>try / catch(Exception) / 记日志 / 返回 false</b>。
 * 看起来啰嗦，但这是本项目一条硬需求（<b>A-07 / 决策 D-06</b>）的落点：
 *
 * <blockquote>
 * 外部依赖的失败不应该否定已经完成的业务动作。
 * </blockquote>
 *
 * <p>用户已经挂上号了——号源扣了、订单建了、事务提交了。
 * 这时候 broker 连不上，是"通知发不出去"，不是"挂号失败"。
 * 把这两件事混在一起，结果就是 MQ 一抖动用户就挂不上号。
 *
 * <h2>为什么 catch 的是 Exception 而不是 AmqpException</h2>
 *
 * 因为这里的目标是<b>兜住一切</b>。除了 {@code AmqpException}，实际还可能遇到：
 * <ul>
 *   <li>{@code NullPointerException}——消息序列化时某个字段为 null</li>
 *   <li>{@code MessageConversionException}——JSON 转换失败</li>
 *   <li>连接层的 {@code SocketException} 等</li>
 * </ul>
 * 对调用方而言它们的后果完全一样："这次通知没发出去"。
 * <b>用一个精确的异常类型去 catch，只是给未来的自己留了一个漏网的洞。</b>
 *
 * <p>⚠️ 唯一<b>不能</b>吞掉异常的场景是"业务要求必须送达"。
 * 本项目不是那种场景——通知可以用日志 + 后续补偿来兜。
 * 如果将来要做"必须送达"，正确的做法是引入本地消息表 + 定时补偿，
 * 而不是把异常抛回主流程。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class RabbitNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(RabbitNotifier.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitNotifier(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public boolean notifyBooked(NotificationMessage message) {
        return publish(RabbitTopologyConfig.APPOINTMENT_EXCHANGE,
                RabbitTopologyConfig.ROUTING_CREATED, message, "挂号成功通知");
    }

    @Override
    public boolean notifyCancelled(NotificationMessage message) {
        return publish(RabbitTopologyConfig.APPOINTMENT_EXCHANGE,
                RabbitTopologyConfig.ROUTING_CANCELLED, message, "取消通知");
    }

    /**
     * 投递"待支付超时"延迟消息。
     *
     * <p>⚠️ 注意这里发的是 {@code DELAY_EXCHANGE} 与 {@code ROUTING_DELAY}，
     * <b>不是</b>取消队列。消息先进延迟队列"待着"，TTL 到期后由 broker
     * 自动死信转发到取消队列。如果我们图省事直接发到取消队列，那就变成"立刻取消"了。
     */
    @Override
    public boolean schedulePaymentTimeout(String appointmentNo) {
        return publish(RabbitTopologyConfig.DELAY_EXCHANGE,
                RabbitTopologyConfig.ROUTING_DELAY, appointmentNo, "超时取消调度");
    }

    /**
     * 统一的投递出口：<b>所有异常在这里被拦住</b>。
     *
     * <p>把 try/catch 集中在一处，而不是让三个方法各写一遍——
     * 三个地方各写一遍的结果是，将来加第四个方法时一定有人忘记写。
     */
    private boolean publish(String exchange, String routingKey, Object payload, String what) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload);
            log.debug("{}已投递: exchange={} key={}", what, exchange, routingKey);
            return true;
        } catch (Exception e) {
            // ⚠️ 这里**绝对不能**向上抛。上层正在处理一笔已完成的挂号，
            //    一次通知投递失败不该把它回滚掉。
            //
            // 记 error 而不是 warn：这是"本该发生但没发生"的事，
            // 需要有人去看（后续应当有补偿机制，本期以日志为准）。
            log.error("{}投递失败（业务主流程不受影响，待补偿）: exchange={} key={} payload={}",
                    what, exchange, routingKey, payload, e);
            return false;
        }
    }
}
