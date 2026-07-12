package com.memory.xzp.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-02-18
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
