package com.demo.hospital.notification.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通知记录 —— <b>异步消费的产物</b>。
 *
 * <p>它的存在本身就是"MQ 真的工作了"的证据：挂号接口是同步返回的，
 * 而这行数据是消费者稍后写进去的。集成测试正是靠"等这张表出现一行"来判断消费成功。
 *
 * <p>用 {@code appointment_no}（业务单号）而不是 {@code appointment_id} 关联：
 * 通知是<b>跨系统</b>的产物（将来可能真的发短信），
 * 对外系统认的是业务单号，不是我们的自增主键。
 */
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 关联的业务单号。 */
    private String appointmentNo;

    private NotificationType type;

    private String content;

    private LocalDateTime sentAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAppointmentNo() { return appointmentNo; }
    public void setAppointmentNo(String appointmentNo) { this.appointmentNo = appointmentNo; }
    public NotificationType getType() { return type; }
    public void setType(NotificationType type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
