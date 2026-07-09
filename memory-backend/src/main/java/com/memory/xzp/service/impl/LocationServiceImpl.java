package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.LocationMapper;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.Location;
import com.memory.xzp.service.LocationService;
import com.memory.xzp.utils.picture.GeocoderLocationUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * @description:
 * @author: xzp
 * @date: 2025/3/7,22:38
 */
@Service
public class LocationServiceImpl implements LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationServiceImpl.class);

    @Resource
    private GeocoderLocationUtil geocoderUtil;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private LocationMapper locationMapper;

    @Override
    public void processCoordinates(FileEntity file) {
        if (file == null || file.getFileId() == null
                || file.getLatitude() == null || file.getLongitude() == null) {
            throw new IllegalArgumentException("Geocoding file data is incomplete");
        }

        GeocoderLocationUtil.Regeocode regeocode = geocoderUtil.reverseGeocode(
                file.getLongitude(),
                file.getLatitude()
        );
        if (regeocode == null || StringUtils.isBlank(regeocode.getFormattedAddress())) {
            fileMapper.updateLocationIfCoordinatesMatch(
                    file.getFileId(),
                    file.getLatitude(),
                    file.getLongitude(),
                    "位置未知"
            );
            log.info("[地点] 文件 {} 的 GPS 坐标无法解析为有效地址", file.getFileId());
            return;
        }

        int updated = fileMapper.updateLocationIfCoordinatesMatch(
                file.getFileId(),
                file.getLatitude(),
                file.getLongitude(),
                regeocode.getFormattedAddress()
        );
        if (updated != 1) {
            log.info("[地点] 文件坐标已变化，忽略过期地理编码结果: fileId={}", file.getFileId());
            return;
        }

        GeocoderLocationUtil.AddressComponent address = regeocode.getAddressComponent();
        String country = null;
        String province = null;
        String city = null;
        String district = null;
        String township = null;
        String street = null;
        String number = null;
        if (address != null) {
            country = address.getCountry();
            province = address.getProvince();
            city = address.getCity();
            district = address.getDistrict();
            township = address.getTownship();
            if (address.getStreetNumber() != null) {
                street = address.getStreetNumber().getStreet();
                number = address.getStreetNumber().getNumber();
            }
        }

        Location location = new Location(
                file.getFileId(),
                country,
                province,
                city,
                district,
                township,
                street,
                number,
                regeocode.getFormattedAddress()
        );
        if (locationMapper.selectById(file.getFileId()) == null) {
            locationMapper.insert(location);
        } else {
            locationMapper.updateById(location);
        }
        log.info("[地点] 更新文件 {} 的地址：{}", file.getFileId(), regeocode.getFormattedAddress());
    }
}
