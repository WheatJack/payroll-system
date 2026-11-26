package com.insbean.payroll.report;

import java.nio.file.Path;

/**
 * 已写好的导出文件。
 *
 * <p>Controller 拿到它之后才设置响应头并流式输出，在此之前任何失败都还能返回
 * JSON 统一错误包（响应一旦提交就无法改回 JSON）。
 */
public record PreparedExport(Path path, String filename, long rows) {
}
