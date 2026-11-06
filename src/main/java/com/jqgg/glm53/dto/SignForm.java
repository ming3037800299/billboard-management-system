package com.jqgg.glm53.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * 乙方在线签名提交表单。
 */
public class SignForm {

    private String signerName;
    private String consent;
    private MultipartFile signature;

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

    public MultipartFile getSignature() {
        return signature;
    }

    public void setSignature(MultipartFile signature) {
        this.signature = signature;
    }
}
