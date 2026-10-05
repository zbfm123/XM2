package com.demo.hospital.appointment;

import com.demo.hospital.notification.Notifier;
import com.demo.hospital.notification.mq.NotificationMessage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用配置：一个<b>总是抛异常</b>的通知实现（T-011 / 验收 A-07）。
 *
 * <h2>它存在的意义</h2>
 *
 * A-07 要求：<b>MQ 不可用时挂号必须仍然成功。</b>
 * 要验证这句话，就需要一个"MQ 彻底坏掉"的环境。真实地把 broker 停掉是不行的：
 * <ul>
 *   <li>那会让测试依赖"本机 broker 当前是停的"这种外部状态，不可复现；</li>
 *   <li>而且"连不上"只是 MQ 故障的一种形态，还可能连上了但发不出去、序列化失败、
 *       交换机不存在……</li>
 * </ul>
 * 用一个<b>总是抛异常</b>的桩，覆盖的是"投递这件事彻底不可用"这个<b>最坏情况</b>。
 * 最坏情况都不影响主流程，那么其它较轻的故障形态自然也不影响。
 *
 * <h2>为什么不用 Mockito 的 mock</h2>
 *
 * 二者都能做到，但一个显式的类更好：
 * <ul>
 *   <li>它把"这里故意模拟 MQ 彻底坏掉"写在类名与注释里，
 *       而 {@code when(notifier.notifyBooked(any())).thenThrow(...)} 需要读者
 *       自己在脑子里重建这个意图；</li>
 *   <li>它还顺便<b>记下了被调用的次数</b>，让测试能断言
 *       "业务确实尝试过发通知，只是失败了"——否则"没发通知"和"发失败"分不清。</li>
 * </ul>
 *
 * <p>⚠️ 用 {@code @Primary} 而不是替换掉原来的 Bean：原来的 {@code NoopNotifier}
 * 仍然是应用的一部分（默认状态下 MQ 就是关的），这里只是在这场测试里让它让位。
 * 换掉它会让测试环境与真实环境的结构产生差异，而差异正是 bug 的藏身处。
 */
@TestConfiguration
public class ThrowingNotifierTestConfig {

    /** 记录被调用次数，供测试断言"业务真的尝试过投递"。 */
    private static final AtomicInteger CALLS = new AtomicInteger();

    public static int callCount() {
        return CALLS.get();
    }

    public static void reset() {
        CALLS.set(0);
    }

    @Bean
    @Primary
    public Notifier throwingNotifier() {
        return new Notifier() {

            @Override
            public boolean notifyBooked(NotificationMessage message) {
                CALLS.incrementAndGet();
                // ⚠️ 刻意抛 RuntimeException 而不是返回 false：
                //    "返回 false"只覆盖了"实现自己吞掉异常"这一种写法；
                //    而真实的框架代码（RabbitTemplate、连接池）抛的正是异常。
                //    调用方必须能扛住异常冒泡——否则迟早有人在 notifier 里
                //    漏了一个 catch，主流程就跟着挂。
                throw new org.springframework.amqp.AmqpException(
                        "模拟 MQ 彻底不可用（A-07）");
            }

            @Override
            public boolean notifyCancelled(NotificationMessage message) {
                CALLS.incrementAndGet();
                throw new org.springframework.amqp.AmqpException(
                        "模拟 MQ 彻底不可用（A-07）");
            }

            @Override
            public boolean schedulePaymentTimeout(String appointmentNo) {
                CALLS.incrementAndGet();
                throw new org.springframework.amqp.AmqpException(
                        "模拟 MQ 彻底不可用（A-07）");
            }
        };
    }
}
