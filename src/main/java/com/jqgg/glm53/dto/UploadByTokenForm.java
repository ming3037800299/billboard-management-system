package com.jqgg.glm53.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * 乙方通过签署链接上传线下已签合同。
 */
public class UploadByTokenForm {

    private String signerName;
    private String consent;
    private MultipartFile file;

    public String getSignerName() {
        return signerName;
    }

    public void setSignerName(String signerName) {
        this.signerName = signerName;
    }

    public String getConsent() {
        return consent;
    }

    public void setConsent(String consent) {
        this.consent = consent;
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }
}
