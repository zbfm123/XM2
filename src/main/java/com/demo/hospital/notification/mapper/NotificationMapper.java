package com.demo.hospital.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.hospital.notification.domain.Notification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 通知记录持久化。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /** 某订单的通知（按时间正序）。测试用它验证"消费者确实写了库"。 */
    @Select("""
            SELECT id, user_id, appointment_no, type, content, sent_at
              FROM notification
             WHERE appointment_no = #{appointmentNo}
             ORDER BY id
            """)
    List<Notification> findByAppointmentNo(@Param("appointmentNo") String appointmentNo);

    /** 某订单 + 某类型的通知数量（幂等判断用）。 */
    @Select("""
            SELECT COUNT(*)
              FROM notification
             WHERE appointment_no = #{appointmentNo}
               AND type = #{type}
            """)
    long countByAppointmentNoAndType(@Param("appointmentNo") String appointmentNo,
                                     @Param("type") String type);

    /** ⚠️ 仅供测试清理。 */
    @Delete("DELETE FROM notification WHERE appointment_no = #{appointmentNo}")
    int deleteByAppointmentNo(@Param("appointmentNo") String appointmentNo);
}
