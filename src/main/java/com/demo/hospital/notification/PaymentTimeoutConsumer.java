package com.demo.hospital.notification;

import com.demo.hospital.appointment.AppointmentService;
import com.demo.hospital.config.RabbitTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 待支付超时消费者：延迟消息到期后取消订单并归还号源（T-012 / 验收 A-06）。
 *
 * <h2>它消费的是什么</h2>
 *
 * 消息里只有一个 <b>业务单号</b>（字符串），不是完整对象。这是刻意的：
 * 这条消息的作用是"到点了，去看一眼那张单子"，而不是"执行取消这个动作"。
 * 真正的判断（该不该取消）发生在消费的时刻，见
 * {@code AppointmentService#cancelOnTimeout}。
 *
 * <h2>⚠️ 为什么不在消息里帶上"请取消"这个指令</h2>
 *
 * 因为这个消息是 15 分钟前发出的，而 15 分钟里世界会变：
 * 用户可能已经付款了。一条 15 分钟前的"请取消"指令，在 15 分钟后
 * 可能已经<b>完全错误</b>。
 *
 * <p>这是"用 MQ 做定时任务"最容易踩的坑，值得作为一条纪律记住：
 * <blockquote>
 * 延迟消息只能表达<b>"到了该检查的时间"</b>，不能表达<b>"到点就该执行"</b>。
 * </blockquote>
 * 所以消费者拿到单号之后必须<b>重新判断一次状态</b>，而不是无条件执行。
 *
 * <h2>异常处理</h2>
 *
 * 与通知消费者同样的纪律：自己 catch 一切异常，不让消息被无限重投。
 * 延迟消息的价值在于"触发一次检查"，丢一条最多是"这张单没被自动取消"——
 * 而堵住整个取消队列会让<b>所有</b>订单都失去自动取消。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class PaymentTimeoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutConsumer.class);

    /** 取消原因文案。放在消费者这一侧，理由同通知文案（渠道/措辞会变）。 */
    private static final String TIMEOUT_REASON = "超过支付时限，系统自动取消";

    private final AppointmentService appointmentService;

    public PaymentTimeoutConsumer(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    /**
     * 消费死信转发过来的"超时"消息。
     *
     * <p>参数类型是 {@code String}：发出去的就是业务单号本身。
     */
    // ⚠️ 同 NotificationConsumer：用 SpEL 读配置的队列名，而不是写常量。
//    写常量的后果是"测试里没有消费者"，而这一点**从测试结果里看不出来**——
//    延迟消息会安静地躺在 .test 队列里，测试却因为别的原因（直接调 service）
//    而通过。详见 NotificationConsumer 上的同一段说明。
@RabbitListener(queues = "#{@queueNames.cancelQueueName()}")
    public void onPaymentTimeout(String appointmentNo) {
        try {
            if (appointmentNo == null || appointmentNo.isBlank()) {
                log.warn("收到空的超时消息（可能是消息体格式变过），忽略");
                return;
            }
            log.info("收到待支付超时消息，检查订单: no={}", appointmentNo);

            boolean cancelled = appointmentService.cancelOnTimeout(appointmentNo, TIMEOUT_REASON);

            if (cancelled) {
                log.info("超时自动取消已生效: no={}", appointmentNo);
            } else {
                // 这不是失败：说明订单已经支付、已被用户主动取消，或已不存在。
                // 用 INFO 而不是 WARN —— 用 WARN 记录正常的分支，
                // 会让真正的警告淹没在噪音里。
                log.info("超时检查未取消任何订单（状态已变或不存在）: no={}", appointmentNo);
            }
        } catch (Exception e) {
            // 见类注释：不能抛回 broker，否则这条消息会被无限重投
            log.error("处理待支付超时消息失败（已丢弃，不重投）: no={}", appointmentNo, e);
        }
    }
}
