package com.insbean.payroll.audit;

import com.insbean.payroll.audit.dto.AuditLogPageDTO;
import com.insbean.payroll.audit.mapper.AuditMapper;
import com.insbean.payroll.support.AbstractDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计流水查询：分页、按类型/动作/操作人/日期过滤。 */
class AuditQueryServiceTest extends AbstractDbTest {

    @Autowired
    private AuditQueryService service;

    @Autowired
    private AuditMapper auditMapper;

    @BeforeEach
    void seedAudits() {
        insert("PERIOD", 1L, "LOCK", "薪酬复核", LocalDateTime.now().minusHours(2));
        insert("EXPORT", null, "EXPORT", "审计员", LocalDateTime.now().minusHours(1));
        insert("EMPLOYEE", 9L, "VIEW_SENSITIVE", "薪酬专员", LocalDateTime.now());
    }

    @Test
    void 分页查询按时间倒序() {
        AuditLogPageDTO page = service.page(null, null, null, null, null, 1, 2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.records()).hasSize(2);
        assertThat(page.records().get(0).action()).isEqualTo("VIEW_SENSITIVE");
        assertThat(page.records().get(1).action()).isEqualTo("EXPORT");
    }

    @Test
    void 按类型动作操作人与日期过滤() {
        assertThat(service.page("PERIOD", null, null, null, null, 1, 20).records())
                .singleElement().satisfies(r -> assertThat(r.action()).isEqualTo("LOCK"));
        assertThat(service.page(null, "EXPORT", null, null, null, 1, 20).records())
                .singleElement().satisfies(r -> assertThat(r.bizType()).isEqualTo("EXPORT"));
        assertThat(service.page(null, null, "薪酬", null, null, 1, 20).records())
                .hasSize(2);
        assertThat(service.page(null, null, null, LocalDate.now(), null, 1, 20).records())
                .hasSize(3);
        assertThat(service.page(null, null, null, null, LocalDate.now().minusDays(1), 1, 20)
                .records()).isEmpty();
    }

    private void insert(String bizType, Long bizId, String action, String operator,
                        LocalDateTime createdAt) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setBizType(bizType);
        entity.setBizId(bizId);
        entity.setAction(action);
        entity.setFieldName("test");
        entity.setOperatorId(1L);
        entity.setOperatorName(operator);
        entity.setIp("127.0.0.1");
        entity.setCreatedAt(createdAt);
        auditMapper.insert(entity);
    }
}
