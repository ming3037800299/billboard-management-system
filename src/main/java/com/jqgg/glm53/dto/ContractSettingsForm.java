package com.jqgg.glm53.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * 签约设置保存表单（multipart：主体信息 + 可选签章图片）。
 */
public class ContractSettingsForm {

    private String partyAName;
    private String partyACreditCode;
    private String partyAAddress;
    private String partyALegalRep;
    private String partyAContact;
    private String partyAPhone;
    private String partyABankName;
    private String partyABankAccount;
    private Integer signLinkTtlDays;
    private boolean removeSeal;
    private MultipartFile seal;

    public String getPartyAName() {
        return partyAName;
    }

    public void setPartyAName(String partyAName) {
        this.partyAName = partyAName;
    }

    public String getPartyACreditCode() {
        return partyACreditCode;
    }

    public void setPartyACreditCode(String partyACreditCode) {
        this.partyACreditCode = partyACreditCode;
    }

    public String getPartyAAddress() {
        return partyAAddress;
    }

    public void setPartyAAddress(String partyAAddress) {
        this.partyAAddress = partyAAddress;
    }

    public String getPartyALegalRep() {
        return partyALegalRep;
    }

    public void setPartyALegalRep(String partyALegalRep) {
        this.partyALegalRep = partyALegalRep;
    }

    public String getPartyAContact() {
        return partyAContact;
    }

    public void setPartyAContact(String partyAContact) {
        this.partyAContact = partyAContact;
    }

    public String getPartyAPhone() {
        return partyAPhone;
    }

    public void setPartyAPhone(String partyAPhone) {
        this.partyAPhone = partyAPhone;
    }

    public String getPartyABankName() {
        return partyABankName;
    }

    public void setPartyABankName(String partyABankName) {
        this.partyABankName = partyABankName;
    }

    public String getPartyABankAccount() {
        return partyABankAccount;
    }

    public void setPartyABankAccount(String partyABankAccount) {
        this.partyABankAccount = partyABankAccount;
    }

    public Integer getSignLinkTtlDays() {
        return signLinkTtlDays;
    }

    public void setSignLinkTtlDays(Integer signLinkTtlDays) {
        this.signLinkTtlDays = signLinkTtlDays;
    }

    public boolean isRemoveSeal() {
        return removeSeal;
    }

    public void setRemoveSeal(boolean removeSeal) {
        this.removeSeal = removeSeal;
    }

    public MultipartFile getSeal() {
        return seal;
    }

    public void setSeal(MultipartFile seal) {
        this.seal = seal;
    }
}
