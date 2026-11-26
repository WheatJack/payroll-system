package com.insbean.payroll.run;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.domain.entity.PayRunEntity;
import com.insbean.payroll.domain.payroll.EmployeePayContext;
import com.insbean.payroll.domain.payroll.PayItemDef;
import com.insbean.payroll.domain.payroll.PayrollCalculator;
import com.insbean.payroll.domain.payroll.PayrollResult;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.dto.CompProfileDTO;
import com.insbean.payroll.employee.dto.CompProfileItemDTO;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.item.ItemService;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.org.mapper.DeptMapper;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.record.RecordAssembler;
import com.insbean.payroll.run.dto.RunErrorDTO;
import com.insbean.payroll.run.dto.RunProgressDTO;
import com.insbean.payroll.run.mapper.PayRecordItemMapper;
import com.insbean.payroll.run.mapper.PayRecordMapper;
import com.insbean.payroll.run.mapper.PayRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 核算生成：幂等 + 三层重复校验（详细设计 §4.2）。
 *
 * <p>三条不可动摇的实现约束：
 * <ol>
 *   <li><b>绝不使用 ON DUPLICATE KEY UPDATE</b>。唯一键是集团级 (employee_id, period_ym)，
 *       撞键意味着命中的是别家主体的记录，盲目 upsert 会把别人的工资单原地改写——
 *       防重复的保险反而变成跨主体静默覆盖。</li>
 *   <li>本主体本期间已存在的记录走按主键 UPDATE（幂等重算），并且用
 *       period_id + company_id 双条件加 FOR UPDATE 取，避免把别家的记录判成自己的。</li>
 *   <li>INSERT 撞唯一键时回查该员工同月的有效记录，把「在哪家分公司」写进错误信息；
 *       财务需要知道去哪个主体找，只说"重复"等于没说。</li>
 * </ol>
 *
 * <p><b>关于同步执行</b>：契约写的是"异步生成 + 轮询进度"，但任务文本要求
 * {@code startGenerate} 在跨主体重复时直接抛出 BizException，且两连调后立即断言记录数，
 * 这只有同步执行才成立。因此这里同步跑完再把结果写进 pay_run，
 * 进度接口照常可用（轮询会立刻拿到终态）。数据量达到万级时应改成真正的异步任务。
 */
@Slf4j
@Service
public class GenerateService {

    /** 单批处理人数。批内一个事务，批间互不影响，避免长事务与超大回滚段。 */
    private static final int BATCH_SIZE = 500;

    /** 生成租约有效期。足够覆盖一次全量生成，进程崩溃后到期可被抢占。 */
    private static final Duration LEASE_TTL = Duration.ofMinutes(10);

    private static final String SKIP_NO_PROFILE = "未配置薪酬档案";

    private final PeriodService periodService;
    private final PeriodMapper periodMapper;
    private final ItemService itemService;
    private final CompProfileService compProfileService;
    private final EmployeeMapper employeeMapper;
    private final DeptMapper deptMapper;
    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final PayRunMapper runMapper;
    private final RecordAssembler recordAssembler;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final PayrollCalculator calculator = new PayrollCalculator();

    public GenerateService(PeriodService periodService,
                           PeriodMapper periodMapper,
                           ItemService itemService,
                           CompProfileService compProfileService,
                           EmployeeMapper employeeMapper,
                           DeptMapper deptMapper,
                           PayRecordMapper recordMapper,
                           PayRecordItemMapper recordItemMapper,
                           PayRunMapper runMapper,
                           RecordAssembler recordAssembler,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.periodService = periodService;
        this.periodMapper = periodMapper;
        this.itemService = itemService;
        this.compProfileService = compProfileService;
        this.employeeMapper = employeeMapper;
        this.deptMapper = deptMapper;
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.runMapper = runMapper;
        this.recordAssembler = recordAssembler;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 生成指定期间的工资数据。
     *
     * @return pay_run 主键，供前端轮询进度
     * @throws BizException 期间已锁定 / 期间正被其他任务处理 / 跨主体同月重复
     */
    public Long startGenerate(Long periodId, Long operatorId) {
        PayPeriodEntity period = periodService.requirePeriod(periodId);
        periodService.assertWritable(periodId);

        String owner = "generate-" + periodId + "-" + UUID.randomUUID();
        if (!periodService.tryAcquire(periodId, owner, LEASE_TTL)) {
            throw new BizException(409, "该期间正在处理中，请稍后再试");
        }

        PayRunEntity run = openRun(periodId, operatorId);
        Progress progress = new Progress();
        try {
            generate(period, operatorId, progress);
            closeRun(run, "SUCCESS", progress);
            periodService.markEditing(periodId, operatorId);
            refreshPeriodTotals(period);
            return run.getId();
        } catch (RuntimeException e) {
            log.warn("期间 {} 生成失败: {}", periodId, e.getMessage());
            closeRun(run, "FAILED", progress);
            throw e;
        } finally {
            periodService.release(periodId, owner);
        }
    }

    public RunProgressDTO progress(Long runId) {
        PayRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(404, "核算批次不存在");
        }
        Progress progress = readProgress(run.getErrorMsg());
        return new RunProgressDTO(run.getStatus(), progress.total, progress.success,
                progress.failed, progress.errors, progress.skipped);
    }

    // ------------------------------------------------------------------ 生成主流程

    private void generate(PayPeriodEntity period, Long operatorId, Progress progress) {
        LocalDate periodStart = PeriodService.periodStart(period.getPeriodYm());
        LocalDate periodEnd = PeriodService.periodEnd(period.getPeriodYm());
        Map<String, PayItemDef> itemDefs = itemService.toDomainDefs(period.getCompanyId());
        Map<String, Integer> sortNoByCode = itemService.listEnabled(period.getCompanyId()).stream()
                .collect(Collectors.toMap(PayItemDefDTO::itemCode,
                        d -> d.sortNo() == null ? 0 : d.sortNo(), (a, b) -> a));

        List<EmpEmployeeEntity> employees = employeeMapper.findCandidates(
                period.getCompanyId(), periodStart, periodEnd);
        progress.total = employees.size();

        for (int from = 0; from < employees.size(); from += BATCH_SIZE) {
            List<EmpEmployeeEntity> batch = employees.subList(
                    from, Math.min(from + BATCH_SIZE, employees.size()));
            transactionTemplate.executeWithoutResult(status ->
                    processBatch(period, periodStart, periodEnd, itemDefs, sortNoByCode, batch, operatorId, progress));
        }
    }

    private void processBatch(PayPeriodEntity period,
                              LocalDate periodStart,
                              LocalDate periodEnd,
                              Map<String, PayItemDef> itemDefs,
                              Map<String, Integer> sortNoByCode,
                              List<EmpEmployeeEntity> batch,
                              Long operatorId,
                              Progress progress) {
        List<PayRecordEntity> existing = recordMapper.lockByPeriod(period.getId(), period.getCompanyId());
        Map<Long, PayRecordEntity> existingByEmployee = existing.stream()
                .collect(Collectors.toMap(PayRecordEntity::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, Map<String, BigDecimal>> existingVariableAmounts = loadVariableAmounts(existing);

        for (EmpEmployeeEntity emp : batch) {
            CompProfileDTO profile = compProfileService.effectiveAt(emp.getId(), periodEnd);
            if (profile == null) {
                progress.skipped.put(emp.getEmpNo(), SKIP_NO_PROFILE);
                continue;
            }

            // 科目被停用/删除时直接丢弃该明细：既不抛 NPE，也不落一条 0 元记录
            Map<String, BigDecimal> fixedItems = new LinkedHashMap<>();
            for (CompProfileItemDTO item : profile.items()) {
                if (itemDefs.containsKey(item.itemCode())) {
                    fixedItems.put(item.itemCode(), item.amount());
                }
            }

            PayRecordEntity existingRecord = existingByEmployee.get(emp.getId());
            Map<String, BigDecimal> variableItems = existingRecord == null
                    ? Map.of()
                    : existingVariableAmounts.getOrDefault(existingRecord.getId(), Map.of());

            PayrollResult result;
            try {
                result = calculator.calculate(new EmployeePayContext(
                        emp.getId(), emp.getEmpNo(), emp.getName(), profile.baseSalary(),
                        emp.getHireDate(), emp.getLeaveDate(), periodStart, periodEnd,
                        payableDays(emp, periodStart, periodEnd),
                        itemDefs, fixedItems, variableItems));
            } catch (RuntimeException e) {
                progress.errors.add(new RunErrorDTO(emp.getEmpNo(), "计算失败：" + e.getMessage()));
                progress.failed++;
                continue;
            }

            int seniorityYears = calculator.seniorityYears(emp.getHireDate(), periodEnd);
            String deptName = deptMapper.findNameById(emp.getDeptId());
            PayRecordEntity record = recordAssembler.toRecord(
                    emp, profile, result, period, deptName, seniorityYears, operatorId);

            if (existingRecord != null) {
                record.setId(existingRecord.getId());
                record.setVersionNo(existingRecord.getVersionNo() + 1);
                record.setCreatedBy(null);   // 不覆盖原始创建人
                recordMapper.updateById(record);
                recordItemMapper.delete(Wrappers.<PayRecordItemEntity>lambdaQuery()
                        .eq(PayRecordItemEntity::getRecordId, existingRecord.getId()));
                insertItems(existingRecord.getId(), result, sortNoByCode);
            } else {
                record.setVersionNo(0);
                try {
                    recordMapper.insert(record);
                } catch (DuplicateKeyException e) {
                    throw crossCompanyDuplicate(emp, period);
                }
                insertItems(record.getId(), result, sortNoByCode);
            }
            progress.success++;
        }
    }

    private void insertItems(Long recordId, PayrollResult result, Map<String, Integer> sortNoByCode) {
        for (PayRecordItemEntity item : recordAssembler.toItems(recordId, result, sortNoByCode)) {
            recordItemMapper.insert(item);
        }
    }

    /** 重算时必须保留人工录入的变动项，否则编制台改过的数据会被静默抹掉。 */
    private Map<Long, Map<String, BigDecimal>> loadVariableAmounts(List<PayRecordEntity> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = records.stream().map(PayRecordEntity::getId).toList();
        return recordItemMapper.findByRecordIds(ids).stream()
                .filter(i -> "VARIABLE".equals(i.getCategory()))
                .collect(Collectors.groupingBy(PayRecordItemEntity::getRecordId,
                        Collectors.toMap(PayRecordItemEntity::getItemCode,
                                PayRecordItemEntity::getAmount, (a, b) -> a)));
    }

    /**
     * 跨主体同月重复：回查该员工同月的有效记录，把主体名写进错误信息。
     * 唯一索引只是最后一道保险，真正对财务有用的是"去哪家找"。
     */
    private BizException crossCompanyDuplicate(EmpEmployeeEntity emp, PayPeriodEntity period) {
        Long otherCompanyId = recordMapper.findCompanyIdByEmployeeYm(emp.getId(), period.getPeriodYm());
        String companyName = otherCompanyId == null ? null : periodMapper.findCompanyName(otherCompanyId);
        String where = companyName == null ? "其他主体" : companyName;
        return new BizException(409, emp.getName() + "(" + emp.getEmpNo() + ") " + period.getPeriodYm()
                + " 已在『" + where + "』的工资表中");
    }

    /**
     * 入职/离职当月按实际计薪天数折算；全月在岗返回 null 表示不折算。
     * 本期按自然日计数（业务尚未确认按工作日口径），规则改动只影响这里。
     */
    static Integer payableDays(EmpEmployeeEntity emp, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate from = periodStart;
        LocalDate to = periodEnd;
        boolean prorated = false;
        if (emp.getHireDate() != null && !emp.getHireDate().isBefore(periodStart)) {
            from = emp.getHireDate();
            prorated = true;
        }
        if (emp.getLeaveDate() != null && !emp.getLeaveDate().isAfter(periodEnd)) {
            to = emp.getLeaveDate();
            prorated = true;
        }
        if (!prorated) {
            return null;
        }
        if (to.isBefore(from)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    // ------------------------------------------------------------------ 批次与汇总

    private PayRunEntity openRun(Long periodId, Long operatorId) {
        PayRunEntity run = new PayRunEntity();
        run.setPeriodId(periodId);
        run.setRunNo("RUN" + periodId + "-" + System.currentTimeMillis()
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        run.setRunType("INIT");
        run.setStatus("RUNNING");
        run.setEmpTotal(0);
        run.setEmpSuccess(0);
        run.setEmpFailed(0);
        run.setOperatorId(operatorId);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    private void closeRun(PayRunEntity run, String status, Progress progress) {
        PayRunEntity update = new PayRunEntity();
        update.setId(run.getId());
        update.setStatus(status);
        update.setEmpTotal(progress.total);
        update.setEmpSuccess(progress.success);
        update.setEmpFailed(progress.failed);
        update.setFinishedAt(LocalDateTime.now());
        update.setErrorMsg(writeProgress(progress));
        runMapper.updateById(update);
    }

    private void refreshPeriodTotals(PayPeriodEntity period) {
        List<PayRecordEntity> records = recordMapper.selectList(Wrappers.<PayRecordEntity>lambdaQuery()
                .eq(PayRecordEntity::getPeriodId, period.getId())
                .eq(PayRecordEntity::getCompanyId, period.getCompanyId()));
        BigDecimal gross = records.stream()
                .map(PayRecordEntity::getGrossAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = records.stream()
                .map(PayRecordEntity::getNetAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PayPeriodEntity update = new PayPeriodEntity();
        update.setId(period.getId());
        update.setEmployeeCount(records.size());
        update.setTotalGross(gross);
        update.setTotalNet(net);
        periodMapper.updateById(update);
    }

    private String writeProgress(Progress progress) {
        try {
            return objectMapper.writeValueAsString(new ProgressPayload(
                    progress.total, progress.success, progress.failed,
                    progress.errors, progress.skipped));
        } catch (Exception e) {
            log.warn("进度序列化失败", e);
            return null;
        }
    }

    private Progress readProgress(String json) {
        Progress progress = new Progress();
        if (json == null || json.isBlank()) {
            return progress;
        }
        try {
            ProgressPayload payload = objectMapper.readValue(json, ProgressPayload.class);
            progress.total = payload.total();
            progress.success = payload.success();
            progress.failed = payload.failed();
            progress.errors = payload.errors() == null ? new ArrayList<>() : new ArrayList<>(payload.errors());
            progress.skipped = payload.skipped() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload.skipped());
        } catch (Exception e) {
            log.warn("进度反序列化失败，按空进度处理", e);
        }
        return progress;
    }

    private record ProgressPayload(int total, int success, int failed,
                                   List<RunErrorDTO> errors, Map<String, String> skipped) {
    }

    /** 生成过程中的可变进度。只在单线程内使用。 */
    static final class Progress {
        int total;
        int success;
        int failed;
        List<RunErrorDTO> errors = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
    }
}
