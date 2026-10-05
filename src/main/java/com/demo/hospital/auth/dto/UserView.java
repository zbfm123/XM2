package com.demo.hospital.auth.dto;

/**
 * 对外的用户视图。
 *
 * <p><b>存在的意义是"不该出去的字段出不去"</b>：实体 {@code SysUser} 上有
 * {@code passwordHash}、{@code failedCount}、{@code lockedUntil}。
 * 直接把实体序列化返回，是把安全边界交给"记得加 @JsonIgnore"这种运气。
 * 用独立的 record 返回，则新增实体字段时默认不会被暴露——
 * <b>默认安全，而不是默认暴露。</b>
 *
 * @param id       用户 id
 * @param phone    手机号（就是登录账号，返回给本人不构成泄露）
 * @param realName 姓名
 * @param idCard   证件号；未填时为 null（响应里会被 Jackson 的 non_null 策略省略）
 */
public record UserView(Long id, String phone, String realName, String idCard) {
}
