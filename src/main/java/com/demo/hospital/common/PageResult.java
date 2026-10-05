package com.demo.hospital.common;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 分页结果。
 *
 * <p>⚠️ <b>{@code page} 是 1 基</b>（对外契约）。项目 1 在这踩过坑：
 * 数据库 {@code LIMIT/OFFSET} 是 0 基，而接口对人是 1 基，
 * 一旦把 0 基的页码直接暴露出去，前端第一页就会传 {@code page=0}，
 * 看起来"第一页少了一条数据"这种诡异现象。
 *
 * <p>本项目的纪律：<b>页码只在 Service 层做一次 1 基 → 0 基的转换</b>，
 * Mapper 只接受已经算好的 {@code limit} / {@code offset}，
 * 从根上避免同一件事在两处各转换一次。
 *
 * @param items 当前页数据
 * @param total 满足条件的总条数
 * @param page  当前页码，**从 1 开始**
 * @param size  每页条数
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    /**
     * 总页数，供前端渲染分页器。
     *
     * <p>⚠️ 必须加 {@code @JsonProperty}：Jackson 只把 {@code getXxx()} 形式的方法
     * 当作属性，而这是一个 {@code totalPages()} 形式的<b>记录内自定义方法</b>，
     * 默认<b>不会</b>被序列化。少了这个注解，前端拿到的是一个没有总页数的分页对象——
     * 分页器只能显示"上一页/下一页"，无法显示"共 3 页"。
     */
    @JsonProperty("totalPages")
    public int totalPages() {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
