package com.hrh.servicearrange.service.impl;

import com.hrh.servicearrange.dao.RegisterInfoDao;
import com.hrh.servicearrange.entity.RegisterInfoEntity;
import com.hrh.servicearrange.service.IRegisterInfoService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * @author huangrenhui
 * @date 2021/11/15
 * @flow
 */
@Slf4j
@Service(IRegisterInfoService.SERVICE_BEAN_NAME)
public class RegisterInfoServiceImpl implements IRegisterInfoService {
    private static final Logger logger = LoggerFactory.getLogger(RegisterInfoServiceImpl.class);


    @Autowired
    private RegisterInfoDao registerInfoDao;


    @Override
    public String save(RegisterInfoEntity registerInfoEntity) {
        try {
            registerInfoEntity.setCreateDate(new Date());
            registerInfoEntity.setModifyDate(new Date());
            registerInfoDao.save(registerInfoEntity);
        } catch (Exception e) {
            e.printStackTrace();
            return e.getMessage();
        }
        return "success";
    }

    @Override
    public String delete(List<String> idList) {
        try {
            registerInfoDao.deleteAllByIdIn(idList);
        } catch (Exception e) {
            e.printStackTrace();
            return e.getMessage();
        }
        return "success";
    }


    @Override
    public Object findById(String id) {
        try {
            Optional<RegisterInfoEntity> optional = registerInfoDao.findById(id);
            if (optional.isPresent()) {
                return optional.get();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return e.getMessage();
        }
        return null;
    }


}
