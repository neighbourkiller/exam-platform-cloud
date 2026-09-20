package com.ekusys.exam.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.ekusys.exam.common.security.SecurityUtils;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
public class MybatisMetaHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Long userId = SecurityUtils.getCurrentUserId();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "createBy", Long.class, userId == null ? 0L : userId);
        this.strictInsertFill(metaObject, "updateBy", Long.class, userId == null ? 0L : userId);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        Long userId = SecurityUtils.getCurrentUserId();
        this.setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        this.setFieldValByName("updateBy", userId == null ? 0L : userId, metaObject);
    }
}
