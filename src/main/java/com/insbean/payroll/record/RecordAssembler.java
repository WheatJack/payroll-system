package com.insbean.payroll.record;

import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.payroll.PayItemResult;
import com.insbean.payroll.domain.payroll.PayrollResult;
import com.insbean.payroll.employee.dto.CompProfileDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 领域计算结果 → 数据库实体。
 *
 * <p>这里同时是"快照边界"：人员姓名、部门名、职级、入职日期都在生成时写死进
 * pay_record，之后人员调动或部门改名都不会改写历史工资表。
 */
@Component
public class RecordAssembler {

    public PayRecordEntity toRecord(EmpEmployeeEntity emp,
                                    CompProfileDTO profile,
                                    PayrollResult result,
                                    PayPeriodEntity period,
                                    String deptName,
                                    int seniorityYears,
                                    Long operatorId) {
        PayRecordEntity record = new PayRecordEntity();
        record.setPeriodId(period.getId());
        record.setPeriodYm(period.getPeriodYm());
        record.setCompanyId(period.getCompanyId());
        record.setEmployeeId(emp.getId());
        record.setEmpNo(emp.getEmpNo());
        record.setEmpName(emp.getName());
        record.setDeptId(emp.getDeptId());
        record.setDeptName(deptName == null ? "" : deptName);
        record.setJobLevel(emp.getJobLevel());
        record.setEmployeeType(emp.getEmployeeType());
        record.setHireDate(emp.getHireDate());
        record.setSeniorityYears(BigDecimal.valueOf(seniorityYears));
        record.setBaseSalarySnapshot(profile.baseSalary());
        record.setGrossAmount(result.grossAmount());
        record.setDeductionAmount(result.deductionAmount());
        record.setNetAmount(result.netAmount());
        record.setRecordStatus("DRAFT");
        record.setCreatedBy(operatorId);
        record.setDeleted(0);
        return record;
    }

    public List<PayRecordItemEntity> toItems(Long recordId,
                                             PayrollResult result,
                                             Map<String, Integer> sortNoByCode) {
        List<PayRecordItemEntity> items = new ArrayList<>();
        for (PayItemResult item : result.items()) {
            PayRecordItemEntity entity = new PayRecordItemEntity();
            entity.setRecordId(recordId);
            entity.setItemCode(item.code());
            entity.setItemName(item.name());
            entity.setCategory(item.category().name());
            entity.setDirection(item.direction() == com.insbean.payroll.domain.payroll.PayDirection.DECREASE ? 2 : 1);
            entity.setAmount(item.amount());
            entity.setSource(item.source().name());
            entity.setSortNo(sortNoByCode.getOrDefault(item.code(), 0));
            items.add(entity);
        }
        return items;
    }
}
