package com.demo.hospital.notification;

import com.demo.hospital.notification.mq.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ 关闭时的通知实现：<b>什么都不做，只记一行日志。</b>
 *
 * <h2>为什么需要一个"什么都不做"的实现，而不是干脆不注册任何 Bean</h2>
 *
 * 因为 {@code AppointmentService} 依赖 {@link Notifier}。
 * 如果不注册，那么在 {@code app.mq.enabled=false}（默认值、也是 {@code mvn test} 的状态）
 * 下应用会因为"找不到这个 Bean"而起不来——
 * <b>而"MQ 没开"恰恰是本项目必须能正常工作的状态</b>（A-07）。
 *
 * <p>这也顺便说明了一件事：<b>"不启用某功能"应当是一个显式的实现，而不是一段缺失的代码。</b>
 * 缺失的代码在运行时表现为启动失败或空指针，而显式的 Noop 实现会把
 * "这里是故意什么都不做"写在脸上。
 *
 * <p>⚠️ 日志级别用 INFO 而不是 WARN：MQ 未启用是一个<b>被明确选择的状态</b>
 * （配置里写着 {@code app.mq.enabled=false}），不是异常。
 * 用 WARN 会让正常的本地开发环境满屏警告，而警告一多就没人看了。
 */
@Component
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NoopNotifier.class);

    public NoopNotifier() {
        log.info("MQ 未启用（app.mq.enabled=false）：通知不会真正投递，挂号主流程不受影响");
    }

    @Override
    public boolean notifyBooked(NotificationMessage message) {
        log.info("跳过挂号通知（MQ 未启用）: no={}", message.appointmentNo());
        return false;
    }

    @Override
    public boolean notifyCancelled(NotificationMessage message) {
        log.info("跳过取消通知（MQ 未启用）: no={}", message.appointmentNo());
        return false;
    }

    @Override
    public boolean schedulePaymentTimeout(String appointmentNo) {
        log.info("跳过超时调度（MQ 未启用）: no={}", appointmentNo);
        return false;
    }
}
