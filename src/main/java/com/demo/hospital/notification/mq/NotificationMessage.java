package com.demo.hospital.notification.mq;

import java.io.Serializable;

/**
 * 通知消息的载荷。
 *
 * <p>⚠️ 关于"消息体里放什么"的一条纪律：<b>只放消费者自己完成工作所必需的信息，
 * 不放需要它再回查数据库的主键组合。</b>
 *
 * <p>这里放了医生名与日期时段，而不是只给一个 {@code appointmentId} 让消费者去查。
 * 理由不是省一次查询，而是<b>耦合</b>：
 * 如果消费者必须回查订单，那么订单表结构一变、或者订单被清理，
 * 这条已经躺在队列里的消息就永远消费不掉了。
 * 消息应当像一个"已封装的作业单"，拿到就能干活。
 *
 * <p>实现 {@link Serializable} 只是历史习惯的保险——本项目的消息用 JSON 序列化
 * （见 {@code RabbitMqConfig}），并不依赖 Java 序列化。
 */
public record NotificationMessage(
        String appointmentNo,
        Long userId,
        String doctorName,
        String departmentName,
        String visitDate,
        String period,
        String type
) implements Serializable {

    /** 挂号成功。 */
    public static final String TYPE_BOOKED = "BOOKED";

    /** 挂号取消。 */
    public static final String TYPE_CANCELLED = "CANCELLED";
}
