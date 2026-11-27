package com.insbean.payroll.tax;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.SysDeptEntity;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.org.mapper.DeptMapper;
import com.insbean.payroll.record.mapper.PayRecordItemMapper;
import com.insbean.payroll.security.DataScopeEnforcer;
import com.insbean.payroll.tax.dto.AnnualTaxRow;
import com.insbean.payroll.tax.mapper.PayYtdMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 年度个税核对报表：直接读 pay_ytd 台账，并把应纳税所得额按与计算一致的公式复算，
 * 财务可以逐人核对"台账累计"与"逐月生成的税额"是否一致。
 */
@Service
public class AnnualTaxReportService {

    private static final String YEAR_PATTERN = "^[0-9]{4}$";

    private final EmployeeMapper employeeMapper;
    private final PayYtdMapper ytdMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final DeptMapper deptMapper;
    private final DataScopeEnforcer dataScope;

    public AnnualTaxReportService(EmployeeMapper employeeMapper, PayYtdMapper ytdMapper,
                                  PayRecordItemMapper recordItemMapper, DeptMapper deptMapper,
                                  DataScopeEnforcer dataScope) {
        this.employeeMapper = employeeMapper;
        this.ytdMapper = ytdMapper;
        this.recordItemMapper = recordItemMapper;
        this.deptMapper = deptMapper;
        this.dataScope = dataScope;
    }

    public List<AnnualTaxRow> annualTax(String year, List<Long> employeeIds, Long deptId) {
        if (year == null || !year.matches(YEAR_PATTERN)) {
            throw new BizException(400, "year 格式必须为 YYYY：" + year);
        }
        var query = Wrappers.<EmpEmployeeEntity>lambdaQuery()
                .eq(EmpEmployeeEntity::getDeleted, 0)
                .orderByAsc(EmpEmployeeEntity::getEmpNo);
        if (employeeIds != null && !employeeIds.isEmpty()) {
            query.in(EmpEmployeeEntity::getId, employeeIds);
        }
        if (deptId != null) {
            query.eq(EmpEmployeeEntity::getDeptId, deptId);
        }
        // 数据范围下推到人员表：SELF/DEPT/DEPT_SUB 只能看到范围内的人
        dataScope.applyEmployeeFilter(query);
        List<EmpEmployeeEntity> employees = employeeMapper.selectList(query);
        if (employees.isEmpty()) {
            return List.of();
        }
        List<Long> ids = employees.stream().map(EmpEmployeeEntity::getId).toList();
        Map<Long, PayYtdEntity> ytdByEmployee = ytdMapper
                .listByYearAndEmployees(Integer.parseInt(year), ids).stream()
                .collect(Collectors.toMap(PayYtdEntity::getEmployeeId, y -> y, (a, b) -> a));
        if (ytdByEmployee.isEmpty()) {
            return List.of();
        }

        Map<String, BigDecimal> lastTax = new LinkedHashMap<>();
        for (Map<String, Object> row : recordItemMapper.findTaxByYearAndEmployees(year, ids)) {
            String key = row.get("employee_id") + "|" + row.get("period_ym");
            Object amount = row.get("amount");
            lastTax.put(key, amount == null ? BigDecimal.ZERO : new BigDecimal(amount.toString()));
        }
        Map<Long, String> deptNames = deptNames(employees);

        List<AnnualTaxRow> rows = new ArrayList<>();
        for (EmpEmployeeEntity employee : employees) {
            PayYtdEntity ytd = ytdByEmployee.get(employee.getId());
            if (ytd == null) {
                continue;
            }
            int paidMonths = ytd.getPaidMonths() == null ? 0 : ytd.getPaidMonths();
            BigDecimal income = money(ytd.getCumIncome());
            BigDecimal social = money(ytd.getCumSocial());
            BigDecimal fund = money(ytd.getCumFund());
            BigDecimal special = money(ytd.getCumSpecialDeduction());
            BigDecimal basic = TaxService.round2(
                    TaxService.MONTHLY_BASIC_DEDUCTION.multiply(BigDecimal.valueOf(paidMonths)));
            BigDecimal taxable = income.subtract(basic).subtract(social).subtract(fund).subtract(special);
            if (taxable.signum() < 0) {
                taxable = BigDecimal.ZERO;
            }
            BigDecimal cumTax = money(ytd.getCumTax());
            BigDecimal last = money(lastTax.get(employee.getId() + "|" + ytd.getLastPeriodYm()));
            BigDecimal rate = income.signum() > 0
                    ? cumTax.divide(income, 4, RoundingMode.HALF_UP)
                    : new BigDecimal("0.0000");
            rows.add(new AnnualTaxRow(employee.getId(), employee.getEmpNo(), employee.getName(),
                    deptNames.get(employee.getDeptId()), paidMonths, income, basic, social, fund,
                    special, TaxService.round2(taxable), cumTax, last, rate));
        }
        return rows;
    }

    private Map<Long, String> deptNames(List<EmpEmployeeEntity> employees) {
        List<Long> deptIds = employees.stream().map(EmpEmployeeEntity::getDeptId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (deptIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (SysDeptEntity dept : deptMapper.selectBatchIds(deptIds)) {
            names.put(dept.getId(), dept.getDeptName());
        }
        return names;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? new BigDecimal("0.00") : value.setScale(2, RoundingMode.HALF_UP);
    }
}
