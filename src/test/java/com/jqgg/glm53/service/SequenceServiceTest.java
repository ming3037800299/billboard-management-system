package com.jqgg.glm53.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原子流水号分配测试（H2 MySQL 模式）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jqggseq;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.admin.password=test-pass",
        "app.upload-dir=target/test-uploads-seq",
        "app.contract-dir=target/test-contracts-seq",
        "app.rag.enabled=false",
})
class SequenceServiceTest {

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 同一名称连续分配递增序号() {
        String key = "contract-test-" + System.nanoTime();
        Long first = transactionTemplate.execute(status -> sequenceService.next(key));
        Long second = transactionTemplate.execute(status -> sequenceService.next(key));
        Long third = transactionTemplate.execute(status -> sequenceService.next(key));

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
        assertThat(third).isEqualTo(3L);
    }

    @Test
    void 不同名称互不影响() {
        String day = "reservation-test-" + System.nanoTime();
        Long a = transactionTemplate.execute(status -> sequenceService.next(day + "-a"));
        Long b = transactionTemplate.execute(status -> sequenceService.next(day + "-b"));

        assertThat(a).isEqualTo(1L);
        assertThat(b).isEqualTo(1L);
    }
}
