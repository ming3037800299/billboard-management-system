package com.jqgg.glm53.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 甲方签约主体与签章配置（单行表，id 固定为 1）。
 * 签章图片存放在私有目录，不通过静态资源对外暴露。
 */
@Entity
@Table(name = "contract_settings")
public class ContractSettings {

    /** 固定主键 */
    @Id
    private Long id = 1L;

    /** 甲方名称 */
    @Column(name = "party_a_name", nullable = false, length = 200)
    private String partyAName = "";

    /** 统一社会信用代码 */
    @Column(name = "party_a_credit_code", length = 50)
    private String partyACreditCode;

    /** 甲方地址 */
    @Column(name = "party_a_address", length = 500)
    private String partyAAddress;

    /** 法定代表人 */
    @Column(name = "party_a_legal_rep", length = 100)
    private String partyALegalRep;

    /** 联系人 */
    @Column(name = "party_a_contact", length = 100)
    private String partyAContact;

    /** 联系电话 */
    @Column(name = "party_a_phone", length = 50)
    private String partyAPhone;

    /** 开户行 */
    @Column(name = "party_a_bank_name", length = 200)
    private String partyABankName;

    /** 银行账号 */
    @Column(name = "party_a_bank_account", length = 100)
    private String partyABankAccount;

    /** 甲方签章图片（私有目录相对路径） */
    @Column(name = "seal_path", length = 500)
    private String sealPath;

    /** 乙方签署链接有效期（天） */
    @Column(name = "sign_link_ttl_days", nullable = false)
    private int signLinkTtlDays = 7;

    /** 更新时间 */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getSealPath() {
        return sealPath;
    }

    public void setSealPath(String sealPath) {
        this.sealPath = sealPath;
    }

    public int getSignLinkTtlDays() {
        return signLinkTtlDays;
    }

    public void setSignLinkTtlDays(int signLinkTtlDays) {
        this.signLinkTtlDays = signLinkTtlDays;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
