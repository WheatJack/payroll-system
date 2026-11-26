# 工资核算系统

内网私有化的工资核算系统。技术栈：Spring Boot 3.3 / Java 17 / MyBatis-Plus / Vue 3 + Element Plus / MySQL 8。

## 文档

- 详细设计（需求解析、表结构、计算引擎、权限模型、接口清单）：[docs/详细设计.md](docs/详细设计.md)
- 建表脚本：[db/schema-mysql.sql](db/schema-mysql.sql)

## 本地环境

```bash
cp .env.example .env   # 首次：填入真实凭证
docker compose up -d
```

| 服务 | 地址 | 账号 |
|---|---|---|
| MySQL | `127.0.0.1:3307`（**仅回环，不对局域网暴露**）/ 库 `payroll` | 见 `.env` |

凭证统一放在 `.env`（已 gitignore），compose 缺失时直接报错退出，不提供默认值。运行应用前需要把 `.env` 导入环境变量：

```bash
set -a; source .env; set +a
```

导入的原始 Excel 默认存本地目录 `./data/import/`（`storage.type=local`）。若要改用 MinIO（镜像需自行获取，见 compose 中的 `minio` profile），把 `storage.type` 切到 `minio` 即可，存储层已做接口抽象。

`db/schema-mysql.sql` 只在数据卷为空时自动执行一次。需要重建：

```bash
docker compose down -v && docker compose up -d
```

> 本机若用 `brew services` 启动过 mysql@8.0，会占用 3306；本项目固定用 **3307**，不冲突。

## 构建

本机 Maven 默认跑在 JDK 23 上，而 JDK 23+ 不再执行 classpath 上的注解处理器，会让 Lombok 静默失效（报"找不到符号 log"）。`pom.xml` 已显式声明 `annotationProcessorPaths` 兜底；构建时仍建议固定 JDK 17：

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home
JAVA_TOOL_OPTIONS="-DsocksProxyHost= -Djava.net.useSystemProxies=false" mvn -DskipTests compile
```

## 关键约束（改代码前必读）

1. 金额一律 `DECIMAL(18,4)`，**禁止 double/float**；接口下发用字符串
2. 合计 = 明细加总，**不做二次舍入**
3. 同一人同一期间只允许一条有效核算记录（`active_key` 生成列 + 唯一索引，改表结构切勿破坏）
4. 工资项由 `pay_item` 字典驱动，不在业务表加固定列
5. 期间锁定后不可写，校验必须在服务端和数据库层做
