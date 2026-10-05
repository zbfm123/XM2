package com.demo.hospital.notification.domain;

/**
 * 通知类型。
 *
 * <p>本期不发真实短信（见 docs/01 的"不做清单"），
 * 但<b>消费者逻辑与真实场景完全一致</b>——把这里的实现换成短信/推送服务即可，
 * 上游（谁在什么时候发什么通知）一行都不用改。
 */
public enum NotificationType {

    /** 挂号成功。 */
    BOOKED,

    /** 挂号被取消。 */
    CANCELLED,

    /** 就诊提醒（本期未实现，预留）。 */
    REMINDER
}
