package com.hrh.servicearrange.service;


import com.hrh.servicearrange.entity.RegisterInfoEntity;

import java.util.List;

/**
 * @author hrh
 */
public interface IRegisterInfoService {
    String SERVICE_BEAN_NAME = "registerInfoService";


    /**
     * 传入实体对象保存到数据库
     */

    String save(RegisterInfoEntity registerInfoEntity);

    /**
     * 删除 指定id数据
     *
     * @param idList
     * @return null
     */
    String delete(List<String> idList);

    /**
     * 根据ID查找数据
     *
     * @param id
     * @return RegisterInfoEntity
     */
    Object findById(String id);


}
