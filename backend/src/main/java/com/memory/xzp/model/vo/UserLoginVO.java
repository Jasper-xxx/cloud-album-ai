package com.memory.xzp.model.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.memory.xzp.model.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @description:
 * @author: xzp
 * @date: 2025/2/18,16:55
 */
@Data
public class UserLoginVO {
    // 用户信息
    private User user;
    private String token;
}
