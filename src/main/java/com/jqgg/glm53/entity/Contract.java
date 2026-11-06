package com.jqgg.glm53.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 广告牌租赁合同。
 * dataSnapshot 保存生成时的全部合同字段与签章/模板副本路径，
 * 签署时用同一份快照重新渲染，保证合同内容不可被后台后续修改。
 */
@Entity
@Table(name = "contract")
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 合同编号（HT-YYYYMMDD-四位序号） */
    @Column(name = "contract_no", nullable = false, unique = true, length = 40)
    private String contractNo;

    /** 关联预定ID */
    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    /** 广告牌ID */
    @Column(name = "billboard_id", nullable = false)
    private Long billboardId;

    /** 生成时使用的模板ID */
    @Column(name = "template_id")
    private Long templateId;

    /** 合同来源 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContractSource source = ContractSource.GENERATED;

    /** 合同状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContractStatus status = ContractStatus.PENDING_SIGN;

    /** 当前合同文件（私有目录相对路径） */
    @Column(name = "current_file_path", length = 500)
    private String currentFilePath;

    /** 当前合同文件名 */
    @Column(name = "current_file_name", length = 255)
    private String currentFileName;

    /** 乙方签名图片（私有目录相对路径） */
    @Column(name = "signature_file_path", length = 500)
    private String signatureFilePath;

    /** 生成时的合同字段快照（JSON） */
    @Column(name = "data_snapshot", columnDefinition = "TEXT")
    private String dataSnapshot;

    /** 签署人姓名 */
    @Column(name = "signer_name", length = 100)
    private String signerName;

    /** 签署时间 */
    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    /** 签署IP */
    @Column(name = "signer_ip", length = 64)
    private String signerIp;

    /** 乙方签署令牌 */
    @Column(name = "sign_token", unique = true, length = 128)
    private String signToken;

    /** 签署令牌过期时间 */
    @Column(name = "sign_token_expires_at")
    private LocalDateTime signTokenExpiresAt;

    /** 签署令牌是否作废 */
    @Column(name = "sign_token_revoked", nullable = false)
    private boolean signTokenRevoked = false;

    /** 作废原因 */
    @Column(name = "void_reason", length = 500)
    private String voidReason;

    /** 合同版本号 */
    @Column(nullable = false)
    private int version = 1;

    /** 完成时间 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getContractNo() {
        return contractNo;
    }

    public void setContractNo(String contractNo) {
        this.contractNo = contractNo;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public Long getBillboardId() {
        return billboardId;
    }

    public void setBillboardId(Long billboardId) {
        this.billboardId = billboardId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public ContractSource getSource() {
        return source;
    }

    public void setSource(ContractSource source) {
        this.source = source;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public void setStatus(ContractStatus status) {
        this.status = status;
    }

    public String getCurrentFilePath() {
        return currentFilePath;
    }

    public void setCurrentFilePath(String currentFilePath) {
        this.currentFilePath = currentFilePath;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    public void setCurrentFileName(String currentFileName) {
        this.currentFileName = currentFileName;
    }

    public String getSignatureFilePath() {
        return signatureFilePath;
    }

    public void setSignatureFilePath(String signatureFilePath) {
        this.signatureFilePath = signatureFilePath;
    }

    public String getDataSnapshot() {
        return dataSnapshot;
    }

    public void setDataSnapshot(String dataSnapshot) {
        this.dataSnapshot = dataSnapshot;
    }

    public String getSignerName() {
        return signerName;
    }

    public void setSignerName(String signerName) {
        this.signerName = signerName;
    }

    public LocalDateTime getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(LocalDateTime signedAt) {
        this.signedAt = signedAt;
    }

    public String getSignerIp() {
        return signerIp;
    }

    public void setSignerIp(String signerIp) {
        this.signerIp = signerIp;
    }

    public String getSignToken() {
        return signToken;
    }

    public void setSignToken(String signToken) {
        this.signToken = signToken;
    }

    public LocalDateTime getSignTokenExpiresAt() {
        return signTokenExpiresAt;
    }

    public void setSignTokenExpiresAt(LocalDateTime signTokenExpiresAt) {
        this.signTokenExpiresAt = signTokenExpiresAt;
    }

    public boolean isSignTokenRevoked() {
        return signTokenRevoked;
    }

    public void setSignTokenRevoked(boolean signTokenRevoked) {
        this.signTokenRevoked = signTokenRevoked;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
