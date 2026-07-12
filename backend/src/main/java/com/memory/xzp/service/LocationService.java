package com.memory.xzp.service;

import com.memory.xzp.model.entity.FileEntity;
import org.springframework.stereotype.Service;

@Service
public interface LocationService {
    void processCoordinates(FileEntity file);
}
