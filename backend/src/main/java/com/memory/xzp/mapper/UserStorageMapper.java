package com.memory.xzp.mapper;

import com.memory.xzp.model.entity.UserStorage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 用户存储信息表 Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-03-10
 */
public interface UserStorageMapper extends BaseMapper<UserStorage> {

    @Update("""
            UPDATE user_storage
            SET used_space = COALESCE(used_space, 0) + #{size}
            WHERE user_id = #{userId}
              AND COALESCE(used_space, 0) + #{size} <= total_space
            """)
    int consumeSpace(@Param("userId") Long userId, @Param("size") long size);

    @Update("""
            UPDATE user_storage
            SET used_space = GREATEST(COALESCE(used_space, 0) - #{size}, 0)
            WHERE user_id = #{userId}
            """)
    int releaseSpace(@Param("userId") Long userId, @Param("size") long size);
}
