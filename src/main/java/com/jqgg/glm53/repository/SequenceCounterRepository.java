package com.jqgg.glm53.repository;

import com.jqgg.glm53.entity.SequenceCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 业务流水号计数访问。
 * bump 使用 MySQL / H2(MySQL 模式) 的原子 upsert，并发请求会在同一行上串行自增。
 */
public interface SequenceCounterRepository extends JpaRepository<SequenceCounter, String> {

    @Modifying
    @Query(value = """
            INSERT INTO sys_sequence(seq_name, seq_value) VALUES (:name, 1)
            ON DUPLICATE KEY UPDATE seq_value = seq_value + 1
            """, nativeQuery = true)
    int bump(@Param("name") String name);

    @Query("select s.seqValue from SequenceCounter s where s.seqName = :name")
    Long current(@Param("name") String name);
}
