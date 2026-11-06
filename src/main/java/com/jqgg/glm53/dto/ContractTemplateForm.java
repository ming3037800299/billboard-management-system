package com.jqgg.glm53.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * 合同模板上传表单。
 */
public class ContractTemplateForm {

    private String name;
    private boolean setDefault;
    private MultipartFile file;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isSetDefault() {
        return setDefault;
    }

    public void setSetDefault(boolean setDefault) {
        this.setDefault = setDefault;
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }
}
