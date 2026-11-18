package com.jqgg.glm53.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 业务流水号计数（合同号 / 预定号）。
 * 以“序号名称 + 当前值”持久化，通过数据库原子自增分配编号，
 * 避免并发下 count()+1 生成重复编号。
 */
@Entity
@Table(name = "sys_sequence")
public class SequenceCounter {

    /** 序号名称，如 contract-20261118 / reservation-20261118 */
    @Id
    @Column(name = "seq_name", length = 64)
    private String seqName;

    /** 当前已分配的最大序号 */
    @Column(name = "seq_value", nullable = false)
    private long seqValue;

    protected SequenceCounter() {
    }

    public SequenceCounter(String seqName, long seqValue) {
        this.seqName = seqName;
        this.seqValue = seqValue;
    }

    public String getSeqName() {
        return seqName;
    }

    public long getSeqValue() {
        return seqValue;
    }

    public void setSeqValue(long seqValue) {
        this.seqValue = seqValue;
    }
}
