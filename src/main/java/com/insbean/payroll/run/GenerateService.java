package com.insbean.payroll.run;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.domain.entity.EmpEmployeeEntity;
import com.insbean.payroll.domain.entity.PayPeriodEntity;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import com.insbean.payroll.domain.entity.PayRunEntity;
import com.insbean.payroll.domain.payroll.EmployeePayContext;
import com.insbean.payroll.domain.payroll.PayCategory;
import com.insbean.payroll.domain.payroll.PayDirection;
import com.insbean.payroll.domain.payroll.PayItemDef;
import com.insbean.payroll.domain.payroll.PayItemResult;
import com.insbean.payroll.domain.payroll.PaySource;
import com.insbean.payroll.domain.payroll.PayrollRules;
import com.insbean.payroll.domain.payroll.PayrollCalculator;
import com.insbean.payroll.domain.payroll.PayrollResult;
import com.insbean.payroll.calendar.WorkingDays;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.dto.CompProfileDTO;
import com.insbean.payroll.employee.dto.CompProfileItemDTO;
import com.insbean.payroll.employee.mapper.EmployeeMapper;
import com.insbean.payroll.employee.mapper.EmploymentMapper;
import com.insbean.payroll.item.ItemService;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.org.mapper.DeptMapper;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.mapper.PeriodMapper;
import com.insbean.payroll.record.PeriodTotalsRefresher;
import com.insbean.payroll.record.RecordAssembler;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.DataScopeEnforcer;
import com.insbean.payroll.setting.ConfigService;
import com.insbean.payroll.tax.PayYtdEntity;
import com.insbean.payroll.tax.TaxService;
import com.insbean.payroll.tax.YtdService;
import com.insbean.payroll.tax.mapper.PayYtdMapper;
import com.insbean.payroll.run.dto.RunErrorDTO;
import com.insbean.payroll.run.dto.RunProgressDTO;
import com.insbean.payroll.run.dto.RunSkipDetail;
import com.insbean.payroll.record.mapper.PayRecordItemMapper;
import com.insbean.payroll.record.mapper.PayRecordMapper;
import com.insbean.payroll.run.mapper.PayRunMapper;
import com.insbean.payroll.run.mapper.RunSkipMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.YearMonth;
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
    private static final String SKIP_LEAVE_DATE_MISSING = "离职状态未填离职日期，已跳过";

    /** error_msg 是 TEXT(64KB)，进度明细必须设上限，否则 closeRun 自己会因超长而失败。 */
    private static final int MAX_ERROR_ENTRIES = 200;
    private static final int MAX_SKIPPED_ENTRIES = 500;
    private static final int MAX_ERROR_MSG_LENGTH = 60_000;

    private final PeriodService periodService;
    private final PeriodMapper periodMapper;
    private final ItemService itemService;
    private final CompProfileService compProfileService;
    private final EmployeeMapper employeeMapper;
    private final EmploymentMapper employmentMapper;
    private final DeptMapper deptMapper;
    private final PayRecordMapper recordMapper;
    private final PayRecordItemMapper recordItemMapper;
    private final PayRunMapper runMapper;
    private final RunSkipMapper runSkipMapper;
    private final RecordAssembler recordAssembler;
    private final ObjectMapper objectMapper;
    private final WorkingDays workingDays;
    private final ConfigService configService;
    private final TaxService taxService;
    private final PayYtdMapper ytdMapper;
    private final AuditService auditService;
    private final DataScopeEnforcer dataScope;
    private final PeriodTotalsRefresher totalsRefresher;
    private final TransactionTemplate transactionTemplate;

    private final PayrollCalculator calculator = new PayrollCalculator();

    public GenerateService(PeriodService periodService,
                           PeriodMapper periodMapper,
                           ItemService itemService,
                           CompProfileService compProfileService,
                           EmployeeMapper employeeMapper,
                           EmploymentMapper employmentMapper,
                           DeptMapper deptMapper,
                           PayRecordMapper recordMapper,
                           PayRecordItemMapper recordItemMapper,
                           PayRunMapper runMapper,
                           RunSkipMapper runSkipMapper,
                           RecordAssembler recordAssembler,
                           ObjectMapper objectMapper,
                           WorkingDays workingDays,
                           ConfigService configService,
                           TaxService taxService,
                           PayYtdMapper ytdMapper,
                           AuditService auditService,
                           DataScopeEnforcer dataScope,
                           PeriodTotalsRefresher totalsRefresher,
                           PlatformTransactionManager transactionManager) {
        this.periodService = periodService;
        this.periodMapper = periodMapper;
        this.itemService = itemService;
        this.compProfileService = compProfileService;
        this.employeeMapper = employeeMapper;
        this.employmentMapper = employmentMapper;
        this.deptMapper = deptMapper;
        this.recordMapper = recordMapper;
        this.recordItemMapper = recordItemMapper;
        this.runMapper = runMapper;
        this.runSkipMapper = runSkipMapper;
        this.recordAssembler = recordAssembler;
        this.objectMapper = objectMapper;
        this.workingDays = workingDays;
        this.configService = configService;
        this.taxService = taxService;
        this.ytdMapper = ytdMapper;
        this.auditService = auditService;
        this.dataScope = dataScope;
        this.totalsRefresher = totalsRefresher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 生成指定期间的工资数据。
     *
     * @return pay_run 主键，供前端轮询进度
     * @throws BizException 期间已锁定 / 期间正被其他任务处理 / 跨主体同月重复
     */
    public Long startGenerate(Long periodId, Long operatorId) {
        dataScope.assertPeriodVisible(periodId);
        // 生成是全公司级动作：部门级账号即使有 record:edit 也不能触发
        CurrentUser current = CurrentUser.get();
        if (current == null || (!DataScopeEnforcer.ALL.equals(current.dataScope())
                && !DataScopeEnforcer.COMPANY.equals(current.dataScope()))) {
            throw new BizException(403, "仅薪酬管理员可以发起核算生成");
        }
        PayPeriodEntity period = periodService.requirePeriod(periodId);
        periodService.assertWritable(periodId);

        String owner = "generate-" + periodId + "-" + UUID.randomUUID();
        if (!periodService.tryAcquire(periodId, owner, LEASE_TTL)) {
            throw new BizException(409, "该期间正在处理中，请稍后再试");
        }

        // 已拿到租约，说明之前的 RUNNING 批次即使存在也已经是孤儿（进程被杀）
        runMapper.failRunningByPeriod(periodId, RunRecoveryRunner.INTERRUPTED);
        PayRunEntity run = openRun(periodId, operatorId);
        Progress progress = new Progress();
        try {
            generate(period, operatorId, progress);
            // 部分员工失败不能报 SUCCESS：HR 会据此直接锁定，把漏算的人永久留在外面
            String runStatus = progress.failed > 0 ? "FAILED" : "SUCCESS";
            closeRun(run, runStatus, progress);
            persistSkips(run.getId(), progress);
            periodService.markEditing(periodId, operatorId);
            markRunFailureCount(periodId, progress.failed);
            // 回写人数/合计 + 生成时的列快照（导出用，不能取当前启用科目）
            totalsRefresher.refresh(period);
            return run.getId();
        } catch (RuntimeException e) {
            log.warn("期间 {} 生成失败: {}", periodId, e.getMessage());
            // 分批提交模式下前面批次可能已写入；把期间合计刷新到"实际已写的部分"，
            // 避免失败后列表页还挂着旧的（或空）合计，造成半成品期间看起来是完整数据。
            try {
                totalsRefresher.refresh(period);
            } catch (RuntimeException refreshError) {
                log.warn("生成失败后刷新期间合计也失败: {}", refreshError.getMessage());
            }
            // R10：失败原因必须落到 pay_run.error_msg，不能只靠这次 HTTP 异常——
            // 将来真改异步后，前端只能靠轮询拿到原因。
            progress.failure = e.getMessage();
            closeRun(run, "FAILED", progress);
            persistSkips(run.getId(), progress);
            markRunFailureCount(periodId, progress.failed);
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
        // 批次属于某个期间的某个主体，越权的人不该看到别家的进度与错误明细
        dataScope.assertPeriodVisible(run.getPeriodId());
        Progress progress = readProgress(run.getErrorMsg());
        List<RunErrorDTO> errors = new ArrayList<>();
        if (progress.failure != null && !progress.failure.isBlank()) {
            // 批次级失败原因（如跨主体重复）没有具体工号，empNo 留空，前端整条提示即可
            errors.add(new RunErrorDTO("", progress.failure));
        }
        errors.addAll(progress.errors);
        List<RunSkipDetail> skippedDetails = runSkipMapper.selectList(
                        Wrappers.<PayRunSkipEntity>lambdaQuery()
                                .eq(PayRunSkipEntity::getRunId, runId)
                                .orderByAsc(PayRunSkipEntity::getId))
                .stream()
                .map(e -> new RunSkipDetail(e.getEmpNo(), e.getEmpName(), e.getReason()))
                .toList();

        // 部门级账号只看得到自己范围内的人：跳过名单/失败明细都含姓名+工号，不能跨部门泄漏
        if (!broadScope()) {
            Set<String> visible = visibleEmpNos(errors, skippedDetails, progress.skipped);
            skippedDetails = skippedDetails.stream()
                    .filter(d -> visible.contains(d.empNo())).toList();
            errors = errors.stream()
                    .filter(e -> e.empNo() == null || e.empNo().isEmpty() || visible.contains(e.empNo()))
                    .toList();
            progress.skipped.keySet().retainAll(visible);
        }

        return new RunProgressDTO(run.getStatus(), progress.total, progress.success,
                progress.failed, errors, progress.skipped, progress.warnings, skippedDetails);
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

        // 业务参数在生成开始时读一次，整批口径一致（参数改到一半不会出现两种算法）。
        // 计薪天数口径：期间上的 hpd（建期间时已取系统参数作默认）> PayrollRules 常量；
        // 工龄单价与节假日是纯全局参数，直接取系统参数。
        BigDecimal payableDaysDivisor = period.getHpd();
        BigDecimal seniorityPayPerYear = configService.getDecimal(
                ConfigService.SENIORITY_PAY_PER_YEAR, PayrollRules.SENIORITY_PAY_PER_YEAR);
        Set<LocalDate> holidays = configService.getDateList(ConfigService.CALENDAR_HOLIDAYS);
        if (holidays.isEmpty()) {
            // 参数为空时回退环境变量（PAYROLL_HOLIDAYS），保持既有部署兼容
            holidays = workingDays.holidays();
        }

        for (int from = 0; from < employees.size(); from += BATCH_SIZE) {
            List<EmpEmployeeEntity> batch = employees.subList(
                    from, Math.min(from + BATCH_SIZE, employees.size()));
            BigDecimal divisor = payableDaysDivisor;
            BigDecimal seniority = seniorityPayPerYear;
            Set<LocalDate> days = holidays;
            // 计数只在事务提交后合并：批内回滚时不能把"已成功 N 人"算进进度
            BatchProgress batchProgress = transactionTemplate.execute(status ->
                    processBatch(period, periodStart, periodEnd, itemDefs, sortNoByCode, batch,
                            operatorId, divisor, seniority, days));
            mergeBatch(progress, batchProgress);
            // 生成也是"创建核算记录"：按批记一条，不逐人写（上万条会爆审计表）
            if (batchProgress != null && batchProgress.success > 0) {
                auditService.record("RECORD", period.getId(), "CREATE", "generate", null,
                        "periodYm=" + period.getPeriodYm() + ";batch=" + (from / BATCH_SIZE + 1)
                                + ";created=" + batchProgress.success);
            }
        }

        // 生成结束后才能统计到实际折算人数：节假日未配置是有钱风险的提示，必须让人看见
        if (progress.prorated > 0 && holidays.isEmpty()) {
            progress.warnings.add("本期有 " + progress.prorated
                    + " 人按不足月折算，但法定节假日未配置，折算天数可能偏多");
        }
    }

    private BatchProgress processBatch(PayPeriodEntity period,
                                       LocalDate periodStart,
                                       LocalDate periodEnd,
                                       Map<String, PayItemDef> itemDefs,
                                       Map<String, Integer> sortNoByCode,
                                       List<EmpEmployeeEntity> batch,
                                       Long operatorId,
                                       BigDecimal payableDaysDivisor,
                                       BigDecimal seniorityPayPerYear,
                                       Set<LocalDate> holidays) {
        BatchProgress progress = new BatchProgress();
        // 每批都在事务内重新加锁校验期间状态：批次之间可能被锁定，
        // 只在入口校验一次的话，锁定后的批次照样会写进去。
        periodService.assertWritableForUpdate(period.getId());
        List<PayRecordEntity> existing = recordMapper.lockByPeriod(period.getId(), period.getCompanyId());
        Map<Long, PayRecordEntity> existingByEmployee = existing.stream()
                .collect(Collectors.toMap(PayRecordEntity::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, Map<String, BigDecimal>> existingVariableAmounts = loadVariableAmounts(existing);
        Map<Long, Map<String, PayRecordItemEntity>> manualItems = loadPreservedItems(existing);

        for (EmpEmployeeEntity emp : batch) {
            // 离职却没有离职日期：不臆造日期去改钱，直接跳过并给出可操作的原因
            if (emp.getStatus() != null && emp.getStatus() == 2 && emp.getLeaveDate() == null) {
                progress.skip(emp, SKIP_LEAVE_DATE_MISSING);
                continue;
            }
            CompProfileDTO profile = compProfileService.effectiveAt(emp.getId(), periodEnd);
            if (profile == null) {
                progress.skip(emp, SKIP_NO_PROFILE);
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
            Map<String, PayRecordItemEntity> manual = existingRecord == null
                    ? Map.of()
                    : manualItems.getOrDefault(existingRecord.getId(), Map.of());

            Integer payableDays = payableDays(emp, periodStart, periodEnd, holidays);
            if (payableDays != null) {
                progress.prorated++;
            }
            PayrollResult result;
            try {
                result = calculator.calculate(new EmployeePayContext(
                        emp.getId(), emp.getEmpNo(), emp.getName(), profile.baseSalary(),
                        emp.getHireDate(), emp.getLeaveDate(), periodStart, periodEnd,
                        payableDays,
                        itemDefs, fixedItems, variableItems,
                        payableDaysDivisor, seniorityPayPerYear));

                // 社保/公积金/个税在领域计算之后、落库之前算：依赖参数表与累计台账
                TaxService.InsuranceResult insurance = taxService.insurance(
                        emp.getId(), period.getPeriodYm(), periodEnd, result.grossAmount());
                BigDecimal special = taxService.specialDeduction(emp.getId(), periodEnd);
                int taxYear = YearMonth.parse(period.getPeriodYm()).getYear();
                PayYtdEntity ytd = ytdMapper.findByEmployeeAndYear(emp.getId(), taxYear);
                BigDecimal cumIncome = nvl(ytd == null ? null : ytd.getCumIncome())
                        .add(result.grossAmount());
                BigDecimal cumSocial = nvl(ytd == null ? null : ytd.getCumSocial())
                        .add(insurance.social());
                BigDecimal cumFund = nvl(ytd == null ? null : ytd.getCumFund())
                        .add(insurance.fund());
                BigDecimal cumSpecial = nvl(ytd == null ? null : ytd.getCumSpecialDeduction())
                        .add(special);
                int months = taxService.monthsInYear(period.getPeriodYm(), emp.getHireDate());
                BigDecimal tax = taxService.computeTax(months, cumIncome, cumSocial, cumFund,
                        cumSpecial, nvl(ytd == null ? null : ytd.getCumTax()));
                result = appendDeductions(result, itemDefs, insurance.social(), insurance.fund(), tax);
            } catch (RuntimeException e) {
                progress.errors.add(new RunErrorDTO(emp.getEmpNo(), "计算失败：" + e.getMessage()));
                progress.failed++;
                continue;
            }
            // 必须先按 itemCode 去重再落库：
            // 同一科目可能同时来自薪酬档案（fixedItems）与既有变动项（variableItems），
            // 计算器会产出两条同 code 明细，直接 INSERT 会撞 uk_record_item 让整批失败。
            // 同时把手工改过的项（含 FIXED/COMPUTED 与 4 位精度）覆盖回去。
            result = finalizeResult(result, manual, itemDefs);

            int seniorityYears = calculator.seniorityYears(emp.getHireDate(), periodEnd);
            // 部门快照与 company_id 同口径：取期间末日的任职部门，而不是人员表的当前部门
            Long deptId = employmentMapper.findDeptAt(emp.getId(), periodEnd);
            if (deptId == null) {
                deptId = emp.getDeptId();
            }
            String deptName = deptMapper.findNameById(deptId);
            PayRecordEntity record = recordAssembler.toRecord(
                    emp, profile, result, period, deptId, deptName, seniorityYears, operatorId);

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
        return progress;
    }

    private void insertItems(Long recordId, PayrollResult result, Map<String, Integer> sortNoByCode) {
        // 兜底：即使上游漏了去重，也不能让同一 (record_id, item_code) 插两次撞唯一键，
        // 那会让该员工整条生成失败（线上 record_id=32/P0 的根因）
        Set<String> inserted = new LinkedHashSet<>();
        for (PayRecordItemEntity item : recordAssembler.toItems(recordId, result, sortNoByCode)) {
            if (inserted.add(item.getItemCode())) {
                recordItemMapper.insert(item);
            }
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
     * 重算时需要保留的明细：编制台手工改的（MANUAL）与导入的（IMPORT），任意类别。
     *
     * <p>只保留 MANUAL 会漏掉 FIXED 类的导入数据——它既不在档案里也不在变动项里，
     * 重写明细时会被整体删除再按档案重插，导入金额静默消失且 run 仍是 SUCCESS。
     */
    private Map<Long, Map<String, PayRecordItemEntity>> loadPreservedItems(List<PayRecordEntity> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = records.stream().map(PayRecordEntity::getId).toList();
        return recordItemMapper.findByRecordIds(ids).stream()
                .filter(i -> "MANUAL".equals(i.getSource()) || "IMPORT".equals(i.getSource()))
                .collect(Collectors.groupingBy(PayRecordItemEntity::getRecordId,
                        Collectors.toMap(PayRecordItemEntity::getItemCode, i -> i, (a, b) -> a)));
    }

    /**
     * 计算结果定稿：按 itemCode 去重 + 合并手工项 + 重算合计。
     *
     * <p>去重是必须的：同一科目可能既在薪酬档案里、又是既有变动明细，
     * 计算器会为它产出两条同 code 的明细；明细表有 uk_record_item，
     * 分两次插或重复插都会让整批生成失败（线上 record_id=32/item_code=P0 即此）。
     * 后出现的值覆盖先出现的值，手工项最后覆盖（人的修改优先级最高）。
     *
     * <p>合计按与计算器相同的口径重算：应发只加 include_in_gross 的增项，
     * 扣款加全部减项，不再二次舍入。
     */
    private PayrollResult finalizeResult(PayrollResult result,
                                         Map<String, PayRecordItemEntity> manual,
                                         Map<String, PayItemDef> itemDefs) {
        Map<String, PayItemResult> byCode = new LinkedHashMap<>();
        for (PayItemResult item : result.items()) {
            byCode.put(item.code(), item);
        }
        if (manual != null) {
            for (PayRecordItemEntity entity : manual.values()) {
                PayItemDef def = itemDefs.get(entity.getItemCode());
                PayCategory category = entity.getCategory() == null
                        ? (def == null ? PayCategory.VARIABLE : def.category())
                        : PayCategory.valueOf(entity.getCategory());
                boolean decrease = entity.getDirection() != null
                        ? entity.getDirection() == 2
                        : def != null && def.direction() == PayDirection.DECREASE;
                boolean includeInGross = def == null || def.includeInGross();
                byCode.put(entity.getItemCode(), new PayItemResult(
                        entity.getItemCode(), entity.getItemName(), category,
                        decrease ? PayDirection.DECREASE : PayDirection.INCREASE,
                        entity.getAmount(), sourceOf(entity.getSource()), includeInGross));
            }
        }
        List<PayItemResult> items = new ArrayList<>(byCode.values());
        BigDecimal gross = items.stream()
                .filter(i -> i.direction() == PayDirection.INCREASE && i.includeInGross())
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deduction = items.stream()
                .filter(i -> i.direction() == PayDirection.DECREASE)
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PayrollResult(List.copyOf(items), gross, deduction, gross.subtract(deduction));
    }

    /** 把社保/公积金/个税作为扣款明细写进结果（科目存在时才加）。 */
    private PayrollResult appendDeductions(PayrollResult result, Map<String, PayItemDef> itemDefs,
                                           BigDecimal social, BigDecimal fund, BigDecimal tax) {
        Map<String, PayItemResult> byCode = new LinkedHashMap<>();
        result.items().forEach(i -> byCode.put(i.code(), i));
        addDeduction(byCode, itemDefs, YtdService.SOCIAL_INS, social);
        addDeduction(byCode, itemDefs, YtdService.HOUSING_FUND, fund);
        addDeduction(byCode, itemDefs, YtdService.TAX, tax);

        List<PayItemResult> items = new ArrayList<>(byCode.values());
        BigDecimal gross = items.stream()
                .filter(i -> i.direction() == PayDirection.INCREASE && i.includeInGross())
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deduction = items.stream()
                .filter(i -> i.direction() == PayDirection.DECREASE)
                .map(PayItemResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PayrollResult(List.copyOf(items), gross, deduction, gross.subtract(deduction));
    }

    private static void addDeduction(Map<String, PayItemResult> byCode,
                                     Map<String, PayItemDef> itemDefs,
                                     String code, BigDecimal amount) {
        PayItemDef def = itemDefs.get(code);
        if (def == null) {
            return;
        }
        byCode.put(code, new PayItemResult(code, def.name(), PayCategory.COMPUTED,
                PayDirection.DECREASE, TaxService.round2(amount), PaySource.COMPUTED, false));
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 保留明细的来源（MANUAL/IMPORT）；未知值按手工处理，宁可保留也不丢。 */
    private static PaySource sourceOf(String raw) {
        try {
            return raw == null ? PaySource.MANUAL : PaySource.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return PaySource.MANUAL;
        }
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
     * 入职/离职当月按实际**计薪天数**折算；全月在岗返回 null 表示不折算。
     *
     * <p>计薪天数是**工作日**口径：21.75 = (365 − 104) ÷ 12，分母本身就排除了周末。
     * 用自然日做分子会造成量纲错误（2026-11-16 入职：11 个工作日 vs 15 个自然日）。
     *
     * <p>边界必须用严格比较：入职日 == 期间首日 / 离职日 == 期间末日 **都属于全月**。
     * 用 {@code !isBefore} 之类会把全月误判成不足月——23 个工作日的月份因子会变成
     * 23/21.75 = 1.0575，全月在职工资反而多发 5.7%。
     */
    Integer payableDays(EmpEmployeeEntity emp, LocalDate periodStart, LocalDate periodEnd) {
        return payableDays(emp, periodStart, periodEnd, workingDays.holidays());
    }

    Integer payableDays(EmpEmployeeEntity emp, LocalDate periodStart, LocalDate periodEnd,
                        Set<LocalDate> holidays) {
        LocalDate from = periodStart;
        LocalDate to = periodEnd;
        boolean prorated = false;
        if (emp.getHireDate() != null && emp.getHireDate().isAfter(periodStart)) {
            from = emp.getHireDate();
            prorated = true;
        }
        if (emp.getLeaveDate() != null && emp.getLeaveDate().isBefore(periodEnd)) {
            to = emp.getLeaveDate();
            prorated = true;
        }
        if (!prorated) {
            return null;
        }
        return workingDays.between(from, to, holidays);
    }

    private void persistSkips(Long runId, Progress progress) {
        try {
            runSkipMapper.deleteByRun(runId);
            for (RunSkipDetail detail : progress.skipDetails) {
                PayRunSkipEntity entity = new PayRunSkipEntity();
                entity.setRunId(runId);
                entity.setEmpNo(detail.empNo());
                entity.setEmpName(detail.empName());
                entity.setReason(detail.reason());
                runSkipMapper.insert(entity);
            }
        } catch (RuntimeException e) {
            log.warn("跳过明细持久化失败: {}", e.getMessage());
        }
    }

    private static boolean broadScope() {
        CurrentUser current = CurrentUser.get();
        return current == null || DataScopeEnforcer.ALL.equals(current.dataScope())
                || DataScopeEnforcer.COMPANY.equals(current.dataScope());
    }

    private Set<String> visibleEmpNos(List<RunErrorDTO> errors, List<RunSkipDetail> details,
                                      Map<String, String> skipped) {
        Set<String> empNos = new LinkedHashSet<>();
        errors.forEach(e -> {
            if (e.empNo() != null && !e.empNo().isBlank()) {
                empNos.add(e.empNo());
            }
        });
        details.forEach(d -> empNos.add(d.empNo()));
        empNos.addAll(skipped.keySet());
        if (empNos.isEmpty()) {
            return Set.of();
        }
        var query = Wrappers.<EmpEmployeeEntity>lambdaQuery()
                .in(EmpEmployeeEntity::getEmpNo, empNos)
                .eq(EmpEmployeeEntity::getDeleted, 0);
        dataScope.applyEmployeeFilter(query);
        return employeeMapper.selectList(query).stream()
                .map(EmpEmployeeEntity::getEmpNo)
                .collect(Collectors.toSet());
    }

    /** 把上次生成失败人数写到期间上，前端在期间列表即可看到"有 N 人没算"。 */
    private void markRunFailureCount(Long periodId, int failed) {
        try {
            periodMapper.update(null, Wrappers.<PayPeriodEntity>lambdaUpdate()
                    .set(PayPeriodEntity::getLastRunFailCount, failed)
                    .eq(PayPeriodEntity::getId, periodId));
        } catch (RuntimeException e) {
            log.warn("写入期间失败人数标记失败: {}", e.getMessage());
        }
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

    /**
     * 进度序列化。
     *
     * <p>pay_run.error_msg 是 TEXT（64KB）。上万人规模的 skipped/errors 会直接超限，
     * 导致 closeRun 自己抛错、批次永远停在 RUNNING。这里做两级截断：
     * 先限制条数，再兜底限制长度，保证写入永远成功（宁可丢明细也不能丢状态）。
     */
    private String writeProgress(Progress progress) {
        try {
            ProgressPayload payload = new ProgressPayload(
                    progress.total, progress.success, progress.failed, progress.failure,
                    truncate(progress.errors, MAX_ERROR_ENTRIES),
                    truncate(progress.skipped, MAX_SKIPPED_ENTRIES),
                    progress.prorated, progress.warnings);
            String json = objectMapper.writeValueAsString(payload);
            if (json.length() <= MAX_ERROR_MSG_LENGTH) {
                return json;
            }
            ProgressPayload summary = new ProgressPayload(
                    progress.total, progress.success, progress.failed, progress.failure,
                    List.of(), Map.of("_truncated",
                    "明细过多已省略：errors=" + progress.errors.size()
                            + "，skipped=" + progress.skipped.size()),
                    progress.prorated, progress.warnings);
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.warn("进度序列化失败", e);
            return null;
        }
    }

    private static <T> List<T> truncate(List<T> source, int max) {
        return source.size() <= max ? source : new ArrayList<>(source.subList(0, max));
    }

    private static Map<String, String> truncate(Map<String, String> source, int max) {
        if (source.size() <= max) {
            return source;
        }
        Map<String, String> limited = new LinkedHashMap<>();
        source.entrySet().stream().limit(max).forEach(e -> limited.put(e.getKey(), e.getValue()));
        limited.put("_truncated", "还有 " + (source.size() - max) + " 条跳过记录未展示");
        return limited;
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
            progress.failure = payload.failure();
            progress.errors = payload.errors() == null ? new ArrayList<>() : new ArrayList<>(payload.errors());
            progress.skipped = payload.skipped() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload.skipped());
            progress.prorated = payload.prorated();
            progress.warnings = payload.warnings() == null ? new ArrayList<>() : new ArrayList<>(payload.warnings());
        } catch (Exception e) {
            log.warn("进度反序列化失败，按空进度处理", e);
        }
        return progress;
    }

    private record ProgressPayload(int total, int success, int failed, String failure,
                                   List<RunErrorDTO> errors, Map<String, String> skipped,
                                   int prorated, List<String> warnings) {
    }

    /** 生成过程中的可变进度。只在单线程内使用。 */
    static final class Progress {
        int total;
        int success;
        int failed;
        /** 批次级失败原因；成功时为 null。 */
        String failure;
        List<RunErrorDTO> errors = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        /** 本期发生不足月折算的人数（用于节假日未配置的警告）。 */
        int prorated;
        List<String> warnings = new ArrayList<>();
        /** 完整跳过明细（persistSkips 用；不进 progress JSON）。 */
        List<RunSkipDetail> skipDetails = new ArrayList<>();
    }

    /** 单批事务内累计的进度；事务成功提交后才合并进总进度。 */
    static final class BatchProgress {
        int success;
        int failed;
        int prorated;
        List<RunErrorDTO> errors = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<RunSkipDetail> skipDetails = new ArrayList<>();

        void skip(EmpEmployeeEntity emp, String reason) {
            skipped.put(emp.getEmpNo(), reason);
            skipDetails.add(new RunSkipDetail(emp.getEmpNo(), emp.getName(), reason));
        }
    }

    private static void mergeBatch(Progress progress, BatchProgress batch) {
        if (batch == null) {
            return;
        }
        progress.success += batch.success;
        progress.failed += batch.failed;
        progress.prorated += batch.prorated;
        progress.errors.addAll(batch.errors);
        progress.skipped.putAll(batch.skipped);
        progress.skipDetails.addAll(batch.skipDetails);
    }
}
