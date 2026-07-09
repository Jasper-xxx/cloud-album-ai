package com.memory.xzp.model.vo.picture;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BatchGetPictureTagResponseVO {
    private List<Item> items = new ArrayList<>();
    private Statistics statistics = new Statistics();

    @Data
    public static class Item {
        private Long taskId;
        private String fileId;
        private List<TagResult> tags = new ArrayList<>();
        private String status;
        private Boolean success;
        private String error;
    }

    @Data
    public static class Statistics {
        private Integer total = 0;
        private Integer success = 0;
        private Integer failed = 0;
    }
}
