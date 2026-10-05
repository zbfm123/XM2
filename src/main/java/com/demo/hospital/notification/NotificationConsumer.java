package com.demo.hospital.notification;

import com.demo.hospital.config.RabbitTopologyConfig;
import com.demo.hospital.notification.domain.Notification;
import com.demo.hospital.notification.domain.NotificationType;
import com.demo.hospital.notification.mapper.NotificationMapper;
import com.demo.hospital.notification.mq.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知消费者：把消息落成 {@code notification} 表里的一行。
 *
 * <p>本期不发真实短信（docs/01 的"不做清单"），所以"发通知"这个动作的终点是写库 + 打日志。
 * <b>但消费者本身的结构与真实场景完全一致</b>——把
 * {@link #deliver} 换成调用短信/推送服务即可，上游一行都不用改。
 *
 * <h2>两条纪律</h2>
 *
 * <p><b>① 监听器必须自己 catch 一切异常。</b>
 * 让异常抛回 Spring AMQP 会导致消息按重试策略重投；如果这条消息永远处理不了
 * （例如内容格式变了、或者数据库里某列不够长），它就会被无限重投，
 * 刷爆日志并占住消费者。
 *
 * <p>对"通知"这类<b>尽力而为</b>的动作，丢掉一条远比堵住整个队列好。
 * 真正需要"必须送达"的场景，做法是本地消息表 + 定时补偿，而不是靠无限重投。
 *
 * <p><b>② 消费是幂等的。</b>
 * MQ 的投递语义是"至少一次"，同一条消息可能被投两次。
 * 这里用 {@code (appointmentNo, type)} 判重，保证同一条通知不会写两行——
 * 否则用户会收到两条一模一样的短信。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationMapper notificationMapper;

    public NotificationConsumer(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 消费通知队列。
     *
     * <p>注意参数类型是 {@link NotificationMessage}：由
     * {@code Jackson2JsonMessageConverter} 从 JSON 还原。
     * 这样写的好处是消息体在 RabbitMQ 管理台里是<b>可读的 JSON</b>，
     * 演示时可以直接点开给面试官看。
     */
    // ⚠️ 这里用 SpEL 从配置读队列名，而不是写常量。
//
// 【为什么必须这样】踩过一个很隐蔽的坑：原来写的是
//   @RabbitListener(queues = RabbitTopologyConfig.DEFAULT_NOTIFY_QUEUE)
// 而测试配置里的队列名是 appointment.notify.queue.test。
// 结果：**测试里根本没有消费者**——消息被投到 .test 队列后就一直躺着，
// 而测试也恰好没断言通知，于是"全绿"了却完全没验证到消费者。
// 只有去 RabbitMQ 管理台才看得出来（.test 队列里堆着 66+ 条没人消费的消息）。
//
// 用 SpEL 之后，队列名跟随 app.mq.notify-queue，
// 测试就能真正走到这个消费者的代码。
@RabbitListener(queues = "#{@queueNames.notifyQueueName()}")
    public void onNotification(NotificationMessage message) {
        try {
            log.info("收到通知消息: no={} type={} doctor={}",
                    message.appointmentNo(), message.type(), message.doctorName());
            deliver(message);
        } catch (Exception e) {
            // 见类注释 ①：不能让它抛回 broker，否则会无限重投
            log.error("处理通知消息失败（已丢弃，不重投）: no={} type={}",
                    message == null ? null : message.appointmentNo(),
                    message == null ? null : message.type(), e);
        }
    }

    /**
     * 真正"发通知"的地方。
     *
     * <p>替换成真实短信/推送时，只改这一个方法即可。
     */
    private void deliver(NotificationMessage message) {
        NotificationType type = NotificationMessage.TYPE_CANCELLED.equals(message.type())
                ? NotificationType.CANCELLED
                : NotificationType.BOOKED;

        // 幂等判重：MQ 是"至少一次"投递，同一条消息可能来两次
        if (notificationMapper.countByAppointmentNoAndType(message.appointmentNo(), type.name()) > 0) {
            log.info("重复消息，跳过（幂等）: no={} type={}", message.appointmentNo(), type);
            return;
        }

        Notification notification = new Notification();
        notification.setUserId(message.userId());
        notification.setAppointmentNo(message.appointmentNo());
        notification.setType(type);
        notification.setContent(buildContent(message, type));
        notification.setSentAt(LocalDateTime.now());

        notificationMapper.insert(notification);
        log.info("通知已记录: no={} type={} content={}",
                message.appointmentNo(), type, notification.getContent());
    }

    /**
     * 组装通知文案。
     *
     * <p>把文案放在消费者这一侧，而不是让生产者拼好字符串再发过来。
     * 理由是<b>通知的措辞会随渠道变</b>：短信字数有限、App 推送可以长一些、
     * 将来加邮件又要换格式。让发消息的人去操心这些，等于把渠道细节
     * 渗透进了挂号主流程。
     */
    private String buildContent(NotificationMessage m, NotificationType type) {
        if (type == NotificationType.CANCELLED) {
            return String.format("您预约的 %s %s %s 门诊已取消。",
                    m.visitDate(), periodLabel(m.period()), m.doctorName());
        }
        return String.format("您已成功预约 %s %s %s 的号，请按时就诊。",
                m.visitDate(), periodLabel(m.period()), m.doctorName());
    }

    private String periodLabel(String period) {
        if ("AM".equals(period)) {
            return "上午";
        }
        if ("PM".equals(period)) {
            return "下午";
        }
        return period;
    }
}
