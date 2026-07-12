package com.memory.xzp.utils.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileUtilTest {

    @Test
    void calculatesMd5WithJdkHexFormat() {
        FileUtil fileUtil = new FileUtil();

        String md5 = fileUtil.getMD5(
                new ByteArrayInputStream("cloud-album".getBytes(StandardCharsets.UTF_8))
        );

        assertEquals("ff0e450f798cea1f83659c9df6bade4d", md5);
    }
}
