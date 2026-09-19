package com.memory.xzp.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

public interface FileObjectGcMapper {
    @Insert("INSERT INTO file_object_gc(object_name) VALUES(#{objectName})")
    int enqueue(String objectName);
    @Select("SELECT id, object_name FROM file_object_gc ORDER BY id LIMIT 100")
    List<Map<String, Object>> pending();
    @Delete("DELETE FROM file_object_gc WHERE id=#{id}")
    int complete(long id);
}
