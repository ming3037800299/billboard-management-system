package com.jqgg.glm53.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改数据库字段注释的请求体。
 * 系统字段只修改数据库列注释；扩展字段会同时更新扩展字段的显示名称。
 */
public class ColumnCommentForm {

    @Size(max = 500, message = "字段注释不能超过 500 字")
    private String comment;

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
