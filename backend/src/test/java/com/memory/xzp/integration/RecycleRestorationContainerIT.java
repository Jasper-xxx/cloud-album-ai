package com.memory.xzp.integration;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.memory.xzp.mapper.*;
import com.memory.xzp.service.impl.FileServiceImpl;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RecycleRestorationContainerIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("recycle_restore_test").withUsername("test").withPassword("test");

    @Test void softDeleteHidesAndRestorationPreservesAlbumCoverTagsAndSimilarity() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V0__init_schema.sql"));
            for (String sql : List.of(
                    "INSERT INTO user(id,user_name,account,password,email) VALUES(1,'A','test_a','unused','a@test'),(2,'B','test_b','unused','b@test')",
                    "INSERT INTO file(file_id,origin_file_name,size,last_modified_time,category,file_url,thumbnail_url,file_object_name,md5) VALUES('shared','test.jpg',100,NOW(),'image','http://test/photo','http://test/cover','test/photo','shared')",
                    "INSERT INTO user_file(user_id,file_id) VALUES(1,'shared'),(2,'shared')",
                    "INSERT INTO album(album_id,album_name,user_id) VALUES(11,'A album',1),(22,'B album',2)",
                    "INSERT INTO album_picture(album_id,file_id,user_id,is_cover) VALUES(11,'shared',1,1),(22,'shared',2,1)",
                    "INSERT INTO picture_tag(file_id,tag_name,image_type) VALUES('shared','retained','manual')",
                    "INSERT INTO similar_picture(similar_id,file_id,user_id) VALUES('group-a','shared',1)"))
                c.createStatement().execute(sql);
        }
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/FileMapper.xml"),
                new ClassPathResource("mapper/AlbumMapper.xml"), new ClassPathResource("mapper/PictureTagMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        var files = session.getMapper(FileMapper.class);
        var albums = session.getMapper(AlbumMapper.class);
        var tags = session.getMapper(PictureTagMapper.class);
        var service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileMapper", files);

        assertEquals(1, albums.selectAlbumById(11L, 1L).getImageCount());
        assertTrue(service.setIsDeleted(List.of("shared"), true, 1L));
        assertEquals(0, albums.selectAlbumById(11L, 1L).getImageCount());
        assertNull(albums.selectAlbumById(11L, 1L).getCoverUrl());
        assertTrue(files.selectFileIdByAlbumId(11L, 1L).isEmpty());
        assertTrue(files.selectFileIdByAlbumId(22L, 1L).isEmpty());
        assertTrue(tags.selectAllTags(1L).isEmpty());
        // Account B remains active, but cannot make A's recycled similarity member visible.
        assertTrue(files.selectAllSimilarPicture(1L, "all").isEmpty());
        assertEquals(1, albums.selectAlbumById(22L, 2L).getImageCount());
        assertEquals(1, tags.selectAllTags(2L).size());

        assertTrue(service.setIsDeleted(List.of("shared"), false, 1L));
        assertEquals(1, albums.selectAlbumById(11L, 1L).getImageCount());
        assertEquals("http://test/cover", albums.selectAlbumById(11L, 1L).getCoverUrl());
        assertEquals(List.of("shared"), files.selectFileIdByAlbumId(11L, 1L));
        assertEquals(1, tags.selectAllTags(1L).size());
        assertEquals(1, files.selectAllSimilarPicture(1L, "all").size());
        try (Connection c = ds.getConnection(); var rs = c.createStatement().executeQuery("SELECT deleted_time FROM user_file WHERE user_id=1")) {
            assertTrue(rs.next()); assertNull(rs.getTimestamp(1));
        }
    }
}
