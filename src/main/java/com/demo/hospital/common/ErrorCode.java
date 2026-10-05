package com.demo.hospital.common;

/**
 * 业务错误码。
 *
 * <p>为什么要有这么一个小枚举，而不是各模块自己抛 {@code RuntimeException}：
 * <b>错误码是接口契约的一部分。</b>前端要能区分"号源已满，请换一个医生"
 * 与"系统出错了，请重试"——这两者的用户操作完全不同。
 * 混在一个 {@code 500 INTERNAL_ERROR} 里，前端只能显示"操作失败"。
 *
 * <p>本项目的纪律：<b>任何会让用户改变操作的失败，都必须有自己的错误码。</b>
 */
public enum ErrorCode {

    /** 请求参数不合法（能指出是哪个参数）。 */
    INVALID_PARAMETER,
    /** 请求的资源不存在。 */
    NOT_FOUND,
    /** 当前状态不允许该操作。例如对已完成订单发起取消。 */
    INVALID_STATE,
    /** 号源已满——挂号场景里最需要被单独识别的一个错误。 */
    NO_SLOTS_AVAILABLE,
    /** 该排班已挂过号（命中活跃订单唯一索引）。 */
    ALREADY_BOOKED,
    /** 请求方法不被支持（例如用 GET 打一个 POST 端点）。 */
    METHOD_NOT_ALLOWED
}
