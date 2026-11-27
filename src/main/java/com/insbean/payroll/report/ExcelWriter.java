package com.insbean.payroll.report;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * xlsx 写出器（同步流式，两段式）。
 *
 * <p>为什么是"临时文件 + SXSSFWorkbook"而不是 {@code byte[]}：
 * <ul>
 *   <li>全量工资表动辄几万行，拼成 byte[] 会把堆打满；SXSSF 只保留窗口内的行；</li>
 *   <li>两段式保证"prepare 全成功 → 写审计 → 设响应头 → 写出"，</li>
 *   <li>一旦查询或写文件失败，响应还没开始写，仍能返回 JSON 统一错误包。</li>
 * </ul>
 *
 * <p>金额一律写成**数值单元格**并设 {@code #,##0.00}，Excel 里可以直接 SUM；
 * 工号等长数字用文本格式，避免被 Excel 转成科学计数法。
 */
@Component
public class ExcelWriter {

    public enum Kind {
        /** 文本，长数字不丢精度。 */
        TEXT,
        /** 金额，数值单元格 + #,##0.00。 */
        MONEY,
        /** 整数（人数等）。 */
        INT,
        /** 比率，数值 + 4 位小数格式（如实际税负率）。 */
        RATE
    }

    public record Column(String title, Kind kind) {

        public static Column text(String title) {
            return new Column(title, Kind.TEXT);
        }

        public static Column money(String title) {
            return new Column(title, Kind.MONEY);
        }

        public static Column integer(String title) {
            return new Column(title, Kind.INT);
        }

        public static Column rate(String title) {
            return new Column(title, Kind.RATE);
        }
    }

    public record SheetData(String name, List<Column> columns, List<List<Object>> rows) {
    }

    /**
     * 写出一个工作簿到临时文件。
     *
     * @return 临时文件描述，调用方负责把文件流给客户端后删除
     */
    public PreparedExport write(String filename, List<SheetData> sheets) throws IOException {
        Path temp = Files.createTempFile("payroll-export-", ".xlsx");
        boolean success = false;
        SXSSFWorkbook workbook = new SXSSFWorkbook(200);
        try {
            CellStyles styles = new CellStyles(workbook);
            long rowCount = 0;
            for (SheetData data : sheets) {
                Sheet sheet = workbook.createSheet(safeSheetName(data.name(), workbook.getNumberOfSheets()));
                writeSheet(workbook, sheet, data, styles);
                rowCount += data.rows().size();
            }
            try (OutputStream out = Files.newOutputStream(temp)) {
                workbook.write(out);
            }
            success = true;
            return new PreparedExport(temp, filename, rowCount);
        } finally {
            // dispose 删除 SXSSF 在磁盘上的临时分片；异常路径也必须清理
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException ignored) {
                // 关闭失败不影响结果
            }
            if (!success) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private void writeSheet(SXSSFWorkbook workbook, Sheet sheet, SheetData data, CellStyles styles) {
        Row header = sheet.createRow(0);
        header.setHeightInPoints(20);
        int index = 0;
        for (Column column : data.columns()) {
            Cell cell = header.createCell(index);
            cell.setCellValue(column.title());
            cell.setCellStyle(styles.header());
            sheet.setColumnWidth(index, widthOf(column));
            index++;
        }
        sheet.createFreezePane(0, 1);
        if (!data.columns().isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, data.columns().size() - 1));
        }

        int rowIndex = 1;
        for (List<Object> values : data.rows()) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < data.columns().size(); i++) {
                Object value = i < values.size() ? values.get(i) : null;
                if (value == null) {
                    continue;
                }
                Cell cell = row.createCell(i);
                if (value instanceof BigDecimal amount) {
                    cell.setCellValue(amount.doubleValue());
                    cell.setCellStyle(data.columns().get(i).kind() == Kind.RATE
                            ? styles.rate() : styles.money());
                    continue;
                }
                if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                    cell.setCellStyle(styles.integer());
                    continue;
                }
                cell.setCellValue(value.toString());
                cell.setCellStyle(styles.text());
            }
        }
    }

    private static int widthOf(Column column) {
        int base = switch (column.kind()) {
            case MONEY -> 14;
            case INT -> 8;
            case TEXT -> 12;
            case RATE -> 10;
        };
        // 中文按两字符宽估算
        return Math.min(40, Math.max(base, column.title().length() * 2 + 4)) * 256;
    }

    private static String safeSheetName(String name, int index) {
        String cleaned = name == null || name.isBlank() ? "Sheet" + (index + 1)
                : name.replaceAll("[\\[\\]:*?/\\\\]", "-");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private record CellStyles(CellStyle header, CellStyle money, CellStyle text,
                              CellStyle integer, CellStyle rate) {

        CellStyles(SXSSFWorkbook workbook) {
            this(headerStyle(workbook), moneyStyle(workbook),
                    plainStyle(workbook, "@"), plainStyle(workbook, "#,##0"),
                    plainStyle(workbook, "0.0000"));
        }

        private static CellStyle headerStyle(SXSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            return style;
        }

        private static CellStyle moneyStyle(SXSSFWorkbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            return style;
        }

        private static CellStyle plainStyle(SXSSFWorkbook workbook, String format) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            return style;
        }
    }
}
