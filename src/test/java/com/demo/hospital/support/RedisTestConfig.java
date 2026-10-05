package com.demo.hospital.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试用 Redis：用一个<b>带 TTL 的内存 Map</b> 顶替真实 Redis。
 *
 * <p>沿用项目 1 的实现与它的取舍（那个决定值得照抄）：
 * 一开始想让真的 {@code StringRedisTemplate} 连到一个假的 {@code RedisConnection} 上，
 * 但那个接口有二十多个方法，写了两百行还在报"未实现抽象方法"。
 * 而本项目实际用到的 Redis 操作只有五个左右。
 * <b>为几个操作去实现一整套连接层，是典型的过度设计。</b>
 *
 * <p>刻意让行为是<b>真实</b>的（写进去能查到、TTL 到期就查不到），
 * 而不是只验证"方法被调用过"——否则 {@code TokenBlacklist} 这类逻辑的过期语义
 * 根本没被测到，测试却显示绿色。那比没有测试更危险。
 *
 * <p>当前（T-003）还没有代码用到 Redis；这个类先建好，是因为
 * T-006 的幂等辅助与 T-010 的缓存都要用，而"等用到再建"的结果通常是
 * 每个任务各建一个自己的版本。
 */
@TestConfiguration
public class RedisTestConfig {

    /** 简易 TTL 存储：key -> (值, 过期时刻) */
    private static final Map<String, Entry> STORE = new ConcurrentHashMap<>();

    private record Entry(String value, Instant expireAt) {
        boolean alive() {
            return expireAt == null || expireAt.isAfter(Instant.now());
        }
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);

        // 无 TTL 写入
        doAnswer(inv -> {
            STORE.put(inv.getArgument(0), new Entry(inv.getArgument(1), null));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        // 带 TTL 写入（登出黑名单、幂等键会用到）
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            Duration ttl = inv.getArgument(2);
            Instant expireAt = (ttl == null || ttl.isZero() || ttl.isNegative())
                    ? null : Instant.now().plus(ttl);
            STORE.put(key, new Entry(value, expireAt));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // 读取：惰性过期，与真实 Redis 的语义一致
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Entry e = STORE.get(key);
            if (e == null) {
                return null;
            }
            if (!e.alive()) {
                STORE.remove(key);
                return null;
            }
            return e.value();
        }).when(valueOps).get(anyString());

        // setIfAbsent：幂等键的实现基础（"只有第一个请求能写进去"）
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            Duration ttl = inv.getArgument(2);
            Entry existing = STORE.get(key);
            if (existing != null && existing.alive()) {
                return false;
            }
            Instant expireAt = (ttl == null || ttl.isZero() || ttl.isNegative())
                    ? null : Instant.now().plus(ttl);
            STORE.put(key, new Entry(value, expireAt));
            return true;
        }).when(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));

        doAnswer(inv -> STORE.containsKey(inv.getArgument(0)))
                .when(template).hasKey(anyString());

        doAnswer(inv -> STORE.remove(inv.getArgument(0)) != null)
                .when(template).delete(anyString());

        return template;
    }

    /** 每个测试前清空，避免测试之间互相影响。 */
    public static void clear() {
        STORE.clear();
    }
}
