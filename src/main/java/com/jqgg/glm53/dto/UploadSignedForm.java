package com.jqgg.glm53.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * 后台上传线下已签合同。
 */
public class UploadSignedForm {

    private String signerName;
    private MultipartFile file;

    public String getSignerName() {
        return signerName;
    }

    public void setSignerName(String signerName) {
        this.signerName = signerName;
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }
}
