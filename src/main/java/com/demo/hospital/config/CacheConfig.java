package com.demo.hospital.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * 缓存配置（Redis）。
 *
 * <h2>为什么现在才有这个类</h2>
 *
 * 在此之前，{@code pom.xml} 里有 {@code spring-boot-starter-data-redis}，
 * 但主代码<b>一处都没用到</b>。这被如实记录在 {@code docs/02} 的"关于 Redis"一节：
 *
 * <blockquote>
 * 引入一个不用它的依赖是负资产——多一份要解释的东西。
 * </blockquote>
 *
 * <p>现在把它真的用起来，用在一个真正合适的场景：<b>号源查询</b>。
 *
 * <h2>为什么号源查询适合缓存</h2>
 *
 * <ul>
 *   <li><b>读多写少</b>：所有人都在查排班，只有挂号/取消时才写。这是缓存最经典的适用条件。</li>
 *   <li><b>能容忍短暂不一致</b>：见下面"缓存与超卖的关系"。</li>
 * </ul>
 *
 * <h2>⚠️ 缓存与超卖的关系（这是本设计最重要的一段）</h2>
 *
 * <b>数据库永远是唯一真相，Redis 只是可丢失的加速层。</b>
 * 扣减号源走的是 {@code ScheduleMapper.tryDeduct}——一条带条件的原子 UPDATE，
 * <b>完全不经过缓存</b>。所以：
 *
 * <ul>
 *   <li>缓存命中 → 用户看到一个可能略旧的 {@code remainingSlots}；</li>
 *   <li>缓存失效/丢失/不一致 → <b>最坏是慢一次，绝不会多卖一个号</b>。</li>
 * </ul>
 *
 * <p>换句话说：<b>即使把 Redis 整个删掉，防超卖依然成立。</b>
 * 这句话是这套设计的底线——缓存只允许影响"快慢"，不允许影响"对错"。
 *
 * <h2>⚠️ 缓存故障必须降级（否则缓存会变成新的单点）</h2>
 *
 * 引入缓存的同时就引入了一个新的故障点。如果 Redis 挂了导致<b>挂号或查排班跟着挂</b>，
 * 那就是"为了提速把系统搞挂了"——典型的负优化。
 *
 * <p>所以这里注册了一个 {@link CacheErrorHandler}：<b>缓存读写任何异常都只记日志，
 * 然后当作"未命中"继续走数据库。</b>效果是 Redis 挂掉时业务自动退化为无缓存状态，
 * 只是慢一点，功能完全正常。
 *
 * <p>这与本项目 A-07 的做法是<b>同一个理念</b>：
 * "外部依赖的失败，不应该否定已经完成的业务动作。"
 * 那时是 MQ 挂了不能影响挂号，现在是缓存挂了不能影响查询。
 */
@Configuration
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /**
     * 缓存名。
     *
     * <p>集中定义，避免 {@code @Cacheable} 和 {@code @CacheEvict} 里各写一遍字符串——
     * <b>那种拼写错误编译器不管，运行时表现为"缓存永远不失效"</b>，
     * 而且很难查（表面看一切正常，只是数据偶尔是旧的）。
     */
    public static final String SCHEDULE_CACHE = "schedule";

    /**
     * ⚠️ 缓存故障降级：任何缓存异常都吞咽并记日志。
     *
     * <p>不这么做的话，Redis 一挂，所有带 {@code @Cacheable} 的接口全部 500——
     * 而这恰恰是"缓存把系统搞挂"的经典事故。
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("缓存读取失败，降级为直查数据库: cache={} key={} 原因={}",
                        cache.getName(), key, e.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("缓存写入失败，忽略（不影响本次返回）: cache={} key={} 原因={}",
                        cache.getName(), key, e.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                // ⚠️ 失效失败比读失败更值得关注：它意味着接下来一段时间会读到旧值。
                //    但因为"数据库是真相"，旧值只影响展示，不影响是否能下单成功。
                //    这里仍然只记 WARN 不抛异常——宁可短暂读到旧值，也不能让下单流程失败。
                log.warn("缓存失效失败，接下来可能短暂读到旧值: cache={} key={} 原因={}",
                        cache.getName(), key, e.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("缓存清空失败: cache={} 原因={}", cache.getName(), e.toString());
            }
        };
    }

    // ==================================================================
    // ⚠️ 这里刻意**不**提供 CacheManager bean
    // ==================================================================
    //
    // 第一版写了一个 `@ConditionalOnMissingBean` 的进程内兜底 CacheManager，
    // 以为它能"生产用 Redis、测试用内存"。**那是错的**：
    //
    //   Spring Boot 的 RedisCacheManager 自动配置带 @ConditionalOnMissingBean(CacheManager)。
    //   用户自己声明了任何 CacheManager，自动配置就会**主动退让**——
    //   结果是**生产环境也用了进程内缓存，Redis 根本不会生效**，
    //   而表面上一切正常（缓存确实在工作）。这是一个"静默降级"的经典陷阱。
    //
    // 正确做法：
    //   · **生产/开发**：什么都不声明，让 Spring Boot 自动配置出 RedisCacheManager；
    //   · **测试**：测试配置里排除 Redis 自动配置，因此由
    //     `RedisTestConfig` 提供一个进程内 CacheManager（见该类的注释）。
    //
    // 这样生产路径上没有任何"兜底"会悄悄把 Redis 换掉。
}
