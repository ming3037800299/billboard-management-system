package com.jqgg.glm53.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 合同到期检查：每天凌晨自动完成结束日期已过的合同，并释放广告牌。
 */
@Component
public class ContractExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ContractExpiryJob.class);

    private final ContractService contractService;

    public ContractExpiryJob(ContractService contractService) {
        this.contractService = contractService;
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void completeExpiredContracts() {
        try {
            int count = contractService.completeExpiredContracts();
            if (count > 0) {
                log.info("合同到期检查完成：自动完成 {} 份合同", count);
            }
        } catch (Exception e) {
            log.error("合同到期检查失败", e);
        }
    }
}
