package com.demo.hospital.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息序列化配置。
 *
 * <h2>为什么必须显式指定 JSON，而不是用 Spring AMQP 的默认行为</h2>
 *
 * Spring AMQP 的默认转换器是 {@code SimpleMessageConverter}，
 * 它在遇到 {@code Serializable} 对象时会用 <b>Java 原生序列化</b>。那有三个问题：
 *
 * <ol>
 *   <li><b>管理台里看不懂</b>：消息体会是一串二进制乱码。
 *       演示时想在 RabbitMQ 管理台点开一条消息给面试官看，结果什么都看不到——
 *       而"能看到消息内容"恰恰是 MQ 最好讲的地方之一。</li>
 *   <li><b>与语言绑死</b>：将来换一个非 Java 的消费者（比如 Python 发的短信服务）
 *       就必须重写。</li>
 *   <li><b>有安全历史包袱</b>：Java 反序列化漏洞是一整类 CVE。
 *       虽然本项目两端都是自己的代码，但"默认不用它"是更省心的选择。</li>
 * </ol>
 *
 * <p>JSON 的代价是消息体积略大、且需要两边约定字段名——
 * 在本项目里这点代价可以忽略。
 *
 * <p>⚠️ 注意 {@code Jackson2JsonMessageConverter} 是<b>无条件注册</b>的（不带
 * {@code @ConditionalOnProperty}）：它只是一个转换器 Bean，不建立任何连接，
 * 因此即使 MQ 关闭也不会去连 broker。而 {@code RabbitTemplate} 由 Spring Boot
 * 自动配置创建，它会自动使用容器里唯一的 {@link MessageConverter} Bean。
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
