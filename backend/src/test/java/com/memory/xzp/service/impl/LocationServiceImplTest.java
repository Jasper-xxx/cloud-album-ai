package com.memory.xzp.service.impl;

import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.LocationMapper;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.Location;
import com.memory.xzp.utils.picture.GeocoderLocationUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationServiceImplTest {

    @Mock
    private GeocoderLocationUtil geocoderUtil;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private LocationMapper locationMapper;

    @InjectMocks
    private LocationServiceImpl locationService;

    @Test
    void processCoordinatesUpdatesFileAndInsertsStructuredLocation() {
        FileEntity file = geocodingFile();
        GeocoderLocationUtil.Regeocode regeocode = regeocode("浙江省杭州市");
        when(geocoderUtil.reverseGeocode(120.2D, 30.1D)).thenReturn(regeocode);
        when(fileMapper.updateLocationIfCoordinatesMatch(
                "geo-1", 30.1D, 120.2D, "浙江省杭州市"
        )).thenReturn(1);
        when(locationMapper.selectById("geo-1")).thenReturn(null);

        locationService.processCoordinates(file);

        ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
        verify(locationMapper).insert(captor.capture());
        assertEquals("浙江省杭州市", captor.getValue().getFullAddress());
        assertEquals("浙江省", captor.getValue().getProvince());
        assertEquals("杭州市", captor.getValue().getCity());
    }

    @Test
    void processCoordinatesDoesNotPersistStaleResult() {
        FileEntity file = geocodingFile();
        when(geocoderUtil.reverseGeocode(120.2D, 30.1D))
                .thenReturn(regeocode("旧地址"));
        when(fileMapper.updateLocationIfCoordinatesMatch(
                "geo-1", 30.1D, 120.2D, "旧地址"
        )).thenReturn(0);

        locationService.processCoordinates(file);

        verify(locationMapper, never()).insert(any(Location.class));
        verify(locationMapper, never()).updateById(any(Location.class));
    }

    @Test
    void processCoordinatesMarksUnknownAddressWithoutLocationRow() {
        FileEntity file = geocodingFile();
        GeocoderLocationUtil.Regeocode regeocode = new GeocoderLocationUtil.Regeocode();
        when(geocoderUtil.reverseGeocode(120.2D, 30.1D)).thenReturn(regeocode);

        locationService.processCoordinates(file);

        verify(fileMapper).updateLocationIfCoordinatesMatch(
                "geo-1", 30.1D, 120.2D, "位置未知"
        );
        verify(locationMapper, never()).insert(any(Location.class));
    }

    private FileEntity geocodingFile() {
        FileEntity file = new FileEntity();
        file.setFileId("geo-1");
        file.setLatitude(30.1D);
        file.setLongitude(120.2D);
        return file;
    }

    private GeocoderLocationUtil.Regeocode regeocode(String fullAddress) {
        GeocoderLocationUtil.StreetNumber streetNumber =
                new GeocoderLocationUtil.StreetNumber();
        streetNumber.setStreet("文一西路");
        streetNumber.setNumber("969号");

        GeocoderLocationUtil.AddressComponent address =
                new GeocoderLocationUtil.AddressComponent();
        address.setCountry("中国");
        address.setProvince("浙江省");
        address.setCity("杭州市");
        address.setDistrict("余杭区");
        address.setTownship("仓前街道");
        address.setStreetNumber(streetNumber);

        GeocoderLocationUtil.Regeocode regeocode = new GeocoderLocationUtil.Regeocode();
        regeocode.setFormattedAddress(fullAddress);
        regeocode.setAddressComponent(address);
        return regeocode;
    }
}
