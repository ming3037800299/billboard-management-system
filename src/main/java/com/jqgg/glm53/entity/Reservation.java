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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 广告牌预定记录。
 * 广告牌关键信息在创建预定（以及生成合同）时保存快照，
 * 后续广告牌编辑不会改变已经生成合同的内容。
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 预定编号（YD-YYYYMMDD-四位序号） */
    @Column(name = "reservation_no", nullable = false, unique = true, length = 40)
    private String reservationNo;

    /** 广告牌ID */
    @Column(name = "billboard_id", nullable = false)
    private Long billboardId;

    // ------------------- 广告牌信息快照 -------------------

    @Column(name = "billboard_code", length = 50)
    private String billboardCode;

    @Column(name = "billboard_name", length = 100)
    private String billboardName;

    @Column(name = "billboard_road", length = 100)
    private String billboardRoad;

    @Column(name = "billboard_section", length = 200)
    private String billboardSection;

    @Column(name = "billboard_address", length = 500)
    private String billboardAddress;

    @Column(name = "billboard_size", length = 100)
    private String billboardSize;

    @Column(name = "billboard_material", length = 100)
    private String billboardMaterial;

    @Column(name = "billboard_visible_area", length = 100)
    private String billboardVisibleArea;

    @Column(name = "billboard_price", precision = 12, scale = 2)
    private BigDecimal billboardPrice;

    @Column(name = "billboard_price_unit", length = 20)
    private String billboardPriceUnit;

    @Column(name = "billboard_price_note", length = 500)
    private String billboardPriceNote;

    // ------------------- 乙方信息 -------------------

    /** 乙方类型：个人 / 企业 */
    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_type", nullable = false, length = 20)
    private TenantType tenantType = TenantType.INDIVIDUAL;

    /** 乙方姓名或公司名称 */
    @Column(name = "tenant_name", nullable = false, length = 200)
    private String tenantName;

    /** 身份证号 / 统一社会信用代码 */
    @Column(name = "tenant_id_number", length = 100)
    private String tenantIdNumber;

    @Column(name = "tenant_address", length = 500)
    private String tenantAddress;

    @Column(name = "tenant_contact", length = 100)
    private String tenantContact;

    @Column(name = "tenant_phone", length = 50)
    private String tenantPhone;

    @Column(name = "tenant_email", length = 200)
    private String tenantEmail;

    /** 广告发布内容 */
    @Column(name = "ad_content", columnDefinition = "TEXT")
    private String adContent;

    // ------------------- 商务条款 -------------------

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 租赁月数（按 30 天折算，向上取整） */
    @Column(name = "lease_months", nullable = false)
    private int leaseMonths = 1;

    /** 合同总金额（元） */
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 定金金额（元） */
    @Column(name = "deposit_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    /** 付款方式 */
    @Column(name = "payment_terms", length = 500)
    private String paymentTerms;

    /** 特殊约定 */
    @Column(name = "special_terms", columnDefinition = "TEXT")
    private String specialTerms;

    /** 内部备注 */
    @Column(length = 500)
    private String remark;

    /** 预定状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

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

    public String getReservationNo() {
        return reservationNo;
    }

    public void setReservationNo(String reservationNo) {
        this.reservationNo = reservationNo;
    }

    public Long getBillboardId() {
        return billboardId;
    }

    public void setBillboardId(Long billboardId) {
        this.billboardId = billboardId;
    }

    public String getBillboardCode() {
        return billboardCode;
    }

    public void setBillboardCode(String billboardCode) {
        this.billboardCode = billboardCode;
    }

    public String getBillboardName() {
        return billboardName;
    }

    public void setBillboardName(String billboardName) {
        this.billboardName = billboardName;
    }

    public String getBillboardRoad() {
        return billboardRoad;
    }

    public void setBillboardRoad(String billboardRoad) {
        this.billboardRoad = billboardRoad;
    }

    public String getBillboardSection() {
        return billboardSection;
    }

    public void setBillboardSection(String billboardSection) {
        this.billboardSection = billboardSection;
    }

    public String getBillboardAddress() {
        return billboardAddress;
    }

    public void setBillboardAddress(String billboardAddress) {
        this.billboardAddress = billboardAddress;
    }

    public String getBillboardSize() {
        return billboardSize;
    }

    public void setBillboardSize(String billboardSize) {
        this.billboardSize = billboardSize;
    }

    public String getBillboardMaterial() {
        return billboardMaterial;
    }

    public void setBillboardMaterial(String billboardMaterial) {
        this.billboardMaterial = billboardMaterial;
    }

    public String getBillboardVisibleArea() {
        return billboardVisibleArea;
    }

    public void setBillboardVisibleArea(String billboardVisibleArea) {
        this.billboardVisibleArea = billboardVisibleArea;
    }

    public BigDecimal getBillboardPrice() {
        return billboardPrice;
    }

    public void setBillboardPrice(BigDecimal billboardPrice) {
        this.billboardPrice = billboardPrice;
    }

    public String getBillboardPriceUnit() {
        return billboardPriceUnit;
    }

    public void setBillboardPriceUnit(String billboardPriceUnit) {
        this.billboardPriceUnit = billboardPriceUnit;
    }

    public String getBillboardPriceNote() {
        return billboardPriceNote;
    }

    public void setBillboardPriceNote(String billboardPriceNote) {
        this.billboardPriceNote = billboardPriceNote;
    }

    public TenantType getTenantType() {
        return tenantType;
    }

    public void setTenantType(TenantType tenantType) {
        this.tenantType = tenantType;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public String getTenantIdNumber() {
        return tenantIdNumber;
    }

    public void setTenantIdNumber(String tenantIdNumber) {
        this.tenantIdNumber = tenantIdNumber;
    }

    public String getTenantAddress() {
        return tenantAddress;
    }

    public void setTenantAddress(String tenantAddress) {
        this.tenantAddress = tenantAddress;
    }

    public String getTenantContact() {
        return tenantContact;
    }

    public void setTenantContact(String tenantContact) {
        this.tenantContact = tenantContact;
    }

    public String getTenantPhone() {
        return tenantPhone;
    }

    public void setTenantPhone(String tenantPhone) {
        this.tenantPhone = tenantPhone;
    }

    public String getTenantEmail() {
        return tenantEmail;
    }

    public void setTenantEmail(String tenantEmail) {
        this.tenantEmail = tenantEmail;
    }

    public String getAdContent() {
        return adContent;
    }

    public void setAdContent(String adContent) {
        this.adContent = adContent;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public int getLeaseMonths() {
        return leaseMonths;
    }

    public void setLeaseMonths(int leaseMonths) {
        this.leaseMonths = leaseMonths;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal depositAmount) {
        this.depositAmount = depositAmount;
    }

    public String getPaymentTerms() {
        return paymentTerms;
    }

    public void setPaymentTerms(String paymentTerms) {
        this.paymentTerms = paymentTerms;
    }

    public String getSpecialTerms() {
        return specialTerms;
    }

    public void setSpecialTerms(String specialTerms) {
        this.specialTerms = specialTerms;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
