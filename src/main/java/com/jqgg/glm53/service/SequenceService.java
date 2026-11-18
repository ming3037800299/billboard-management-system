package com.jqgg.glm53.service;

import com.jqgg.glm53.repository.SequenceCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原子业务编号分配。
 *
 * <p>调用方必须处于事务中：upsert 会锁住序号行，直到事务提交 / 回滚。
 * 这样并发请求会依次拿到不同序号；事务回滚时序号也一起回滚，不会产生空洞。</p>
 */
@Service
public class SequenceService {

    private final SequenceCounterRepository repository;

    public SequenceService(SequenceCounterRepository repository) {
        this.repository = repository;
    }

    /** 取下一个序号（从 1 开始）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String name) {
        repository.bump(name);
        Long value = repository.current(name);
        if (value == null || value < 1) {
            throw new IllegalStateException("流水号分配失败：" + name);
        }
        return value;
    }
}
