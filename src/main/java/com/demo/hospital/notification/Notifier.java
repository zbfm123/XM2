package com.demo.hospital.notification;

import com.demo.hospital.notification.mq.NotificationMessage;

/**
 * 通知投递出口。
 *
 * <h2>为什么要有这个接口，而不是直接在 Service 里用 RabbitTemplate</h2>
 *
 * 因为本项目有一条硬需求（<b>A-07</b>）：
 * <b>MQ 不可用时挂号必须仍然成功。</b>
 * 而"可替换"正是把它变成可测试的前提——T-011 会把实现换成一个
 * <b>总是抛异常的桩</b>，然后断言挂号依然成功。
 *
 * <p>如果 Service 直接依赖 {@code RabbitTemplate}，那个测试就只能去 mock 一个
 * 有十几个方法的框架类，而且测出来的是"我 mock 对了"而不是"业务不受影响"。
 *
 * <h2>⚠️ 实现者必须遵守的契约：<b>绝不向调用方抛异常</b></h2>
 *
 * 这是本接口唯一重要的约定。投递失败对业务而言只是一条日志，
 * 不是一个应当让挂号失败的理由。
 * 实现里必须自己 try/catch 并返回 {@code false}，
 * <b>而不是让异常冒泡出去</b>——因为调用方的 catch 迟早会有人删掉。
 */
public interface Notifier {

    /**
     * 投递一条挂号成功通知。
     *
     * @return {@code true} = 已交给 broker；{@code false} = 投递失败（已记日志）。
     *         <b>返回值只用于观测，调用方不得据此改变业务结果。</b>
     */
    boolean notifyBooked(NotificationMessage message);

    /** 投递一条取消通知。同 {@link #notifyBooked}：不抛异常，返回是否成功。 */
    boolean notifyCancelled(NotificationMessage message);

    /**
     * 投递一条"待支付超时"延迟消息（T-012）。
     *
     * <p>注意它与上面两个的语义差别：上面两个是"立刻通知用户"，
     * 这一个是"**过一段时间之后**再回来处理"。
     */
    boolean schedulePaymentTimeout(String appointmentNo);
}
