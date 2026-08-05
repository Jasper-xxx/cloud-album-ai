package com.memory.xzp.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 串行化同一用户下的智能体相册创建，避免同名相册并发分叉。
     */
    @Select("SELECT id FROM `user` WHERE id = #{userId} FOR UPDATE")
    Long lockUserForAlbumCreation(@Param("userId") Long userId);
}
