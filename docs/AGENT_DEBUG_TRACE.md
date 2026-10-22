# Agent Debug 日志对照

## 怎么运行一次

1. Android Studio 的 Build Variant 选 `debug`，Run 当前 App（不卸载、不清数据）。
2. Logcat 选正在运行的设备，过滤：`package:com.example.nutritrack tag:NutriTrackAgentTrace`。
3. 登录后进入 NutriCoach，输入不含身份信息的示例需求，例如“根据我的记录给出一般饮水建议”，点击 Generate Nutrition Plan Draft 一次。
4. 按下表看日志。只复制这个 tag 的日志；不要导出整个 Logcat，其他 SDK 的日志可能含 Debug secret。

本文件中的说明不是一次真实模型运行结果。加入日志不会自动发起请求。现有瞬时失败重试策略未改，故一次点击不一定只对应两次网络尝试。

## 六个阶段

| 标记 | 来源 / 该看什么 | 不能据此断言什么 |
| --- | --- | --- |
| `PROMPT_01` | Firebase Gateway 实际调用前的系统约束、用户 prompt 模板；动态 JSON 值已隐藏 | 看到此行仅说明即将尝试请求，不代表服务器收到或成功返回 |
| `FUNCTION_CALLS_02` | 模型返回的工具名和参数结构，保持返回顺序；例如 `get_nutrition_profile` / `get_intake_history` | 这是模型提议，还未通过本地白名单/参数校验，也未执行 |
| `TOOL_RESULT_03` | 每个本地工具实际执行、审计后的结果结构；成功或失败及固定错误码 | 不会打印用户 ID、画像值、历史日期/摄入数值；失败不会造出结果 |
| `EVIDENCE_04` | 真正用于第二次请求的 evidence 结构，含 `e1/e2`、工具名和 `truncated` 标记 | 这里的别名不是数据库 ID；这不是 FunctionResponse 回传或新增一轮工具选择 |
| `RAW_PLAN_05` | **解析/校验前**模型文本的脱敏 JSON 结构，包括字段、分类/单位枚举和 evidence 别名 | 不是未经处理的原文；非 JSON、过长/过深内容只记录省略原因，不回显原文，也不表示已通过校验 |
| `VALIDATION_06` | `PASSED` 表示真实 parser 及领域/安全/evidence 校验通过；随后 `SAVED_DRAFT` 表示事务返回成功 | `PASSED` 不等于已落库；`SAVED_DRAFT` 不等于用户已确认采用 |

出错或取消时 `VALIDATION_06` 会显示终止 `stage`、`errorCode`。解析失败另外有 `validationError`；医疗/PII 校验拒绝时有 `ruleId`、`fieldPath` 和 `failureCategory`，不会显示触发原文。
若错误发生在模型/工具阶段，编号会跳过未到达的后续阶段；不要把日志缺失理解为生成了空计划。记录器本身或系统 Logcat 丢日志也不能替代 UI / Room 的结果证据。

## 对照时按这个顺序解释

- 01 → 02：用户请求经模板传给模型，模型自行选择已声明的工具；并非 App 硬编码某个调用列表。
- 02 → 03：App 校验工具名和参数，可信上下文提供用户 ID，执行真正的查询/计算，然后写入工具审计。
- 03 → 04：结果变为精简 evidence；`e1/e2` 对应本次成功工具，第二阶段不收到内部审计 ID。
- 04 → 05：独立的模型请求按 JSON Schema 生成计划。字段完整只是第一步，不代表内容可信。
- 05 → 06：本地再检查格式、未知字段、日期、枚举、数值、引用和安全规则；通过后同一 Room 事务保存 DRAFT 与 SUCCEEDED。

## 隐私与发布边界

- 新 tag 仅在 `src/debug` 实现；`src/release` 使用 No-op，连 payload lambda 都不求值。
- 采用白名单保留结构：仅输出固定工具名、固定错误/状态/规则、已知字段、受限 evidence 别名；自由文本、计划正文、健康数值、日期、查询词和 ID 均替换为 `[VALUE_REDACTED]`。
- 用户或模型能提供的未知字段名也不会原样输出；仅源码中的精确系统指令/模板可保留。长数组/对象有 `[TRUNCATED]`，过深/过长/不可解析内容明确标记省略。
- 每个事件按 `event` 和 `part=序号/总数` 分段，先脱敏再分段，不以 Logcat 自动截断作为防泄漏措施。
- 日志是结构学习视图，不是可重放的原始模型转储，也不上传到监控或写入文件。现有 `NutriTrackAgentDiag` 仍可单独查看模型阶段耗时/重试诊断。
- 本改动不修改模型、prompt 文本、超时、重试、Room、App Check 或用户业务数据。
