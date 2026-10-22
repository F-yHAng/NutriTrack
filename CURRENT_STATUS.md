# NutriTrack 当前开发状态

> **2026-10-22 公开副本改进（本目录的最新状态）：** 本副本没有 `.git`，下方分支、HEAD、stash、提交和“当前工作区”叙述均属于原开发目录历史，不描述此副本。新增样例注册辅助、AI 配置提示与生成按钮控制、SettingsViewModel、明确的样例评分说明、全画像水果查询、README 截图与 CI。Settings 的 R2 边界例外在此副本已关闭；R3/R4 仍待处理。本次 164 项 JVM、21 项隔离 API 35 设备测试与 4 项发布工具测试通过；Debug/仪器 APK 构建、Release Kotlin 编译通过，lint 0 errors / 34 warnings，真实 AI 请求 0。GitHub 远端 CI 尚未运行。详细范围见 [PUBLIC_RELEASE.md](docs/PUBLIC_RELEASE.md)。

> **2026-10-22 隐私清理更新优先于下方旧快照。** 当前包名为 `com.example.nutritrack`；真实 Firebase 配置及本地旧 AI key 已删除，未配置 Firebase 时允许构建并由原 Gateway 拒绝模型请求。种子九行全部改为人工样例；欢迎页作者署名和院校推广信息已移除；Clinician 入口因缺少服务端授权关闭。历史文档中的包名、命令和项目标识也作了脱敏，不能作为旧环境原样重放命令。原有 Agent 日志增量保留。Git 历史、stash、私有交接和本地缓存没有清除，公开请使用无历史导出副本，见 [隐私审查](docs/PRIVACY_REVIEW.md)。本次验证结果与边界在本文末尾更新。

核对日期：**2026-10-18（Asia/Kuala_Lumpur）**。Android 实现核对基线为 `3b7db21`；本文件随其后的项目记忆文档提交交付，不能将下表基线当作最新 HEAD。后续 Thread 必须重新核对 Git。长期背景见 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md)，执行规则见 [AGENTS.md](AGENTS.md)。

本次完成三份历史报告的全文合并与源码核对，清理并单独提交三个根记忆 Markdown；原始交接目录仅通过本地 `.git/info/exclude` 排除。没有修改 Android 业务代码、Gradle、测试、其他工作区文件或原始报告，没有构建、运行设备、真实 AI 请求、切换分支、操作 stash、合并或推送。

## 1. Git 与工作区

| 项目 | 本次核实 |
| --- | --- |
| 根目录 | `.`（包含 `settings.gradle.kts`、`app/`、`gradlew` 的 NutriTrack 仓库根目录） |
| 当前分支 | `refactor/nutritrack-engineering` |
| Android 实现核对基线 | `3b7db21bfeb9a8ec4bcc44736489e9c70abc82b4` |
| 基线内容/时间 | 原生 UI 美化及视觉回归，18 文件；Git 提交时间 2026-10-18 01:28:05 +08:00 |
| 基线父提交 | `e4d2d01549d46883492383726e861d353a7dba82`：Agent Flash Lite 默认配置及验收恢复 |
| 文档提交 | `docs: initialize NutriTrack project memory`；仅三个根 Markdown；最新提交以 `git log -1` 为准 |
| 其他本地分支 | `main` 停在 `e112ee4`；没有切换或合并 |
| 暂存/冲突 | 文档提交准备前暂存区为空、无 unmerged entries；只暂存三个根 Markdown |
| 受保护 stash | `stash@{0}` → `cbcd4cb6ee7db6bc4fc69cc7934c9094f83abbcd`，保留 |
| 远程状态 | 本次未联网核对；Fork 3 仅历史报告称未推送，不推断远程现状 |

下面以 `K/` 简写 `app/src/main/java/com/example/nutritrack/`。

**保留的未提交代码修改：3 个已跟踪文件，均未暂存，属于 Fork 2 日志增量。**

- `K/NutrackApplication.kt`
- `K/agent/NutritionPlanningAgent.kt`
- `K/data/FirebaseAgentModelGateway.kt`

**保留的未跟踪文件：5 个，均不包含在文档提交中。**

- `K/agent/AgentTraceLogger.kt`
- `app/src/debug/java/com/example/nutritrack/agent/AgentTraceLogging.kt`
- `app/src/release/java/com/example/nutritrack/agent/AgentTraceLogging.kt`
- `app/src/testDebug/java/com/example/nutritrack/agent/AgentTraceLoggingTest.kt`
- `docs/AGENT_DEBUG_TRACE.md`

**仅本地保留并排除：**`docs/context-handoff/legacy-main.md`、`legacy-fork-2.md`、`legacy-fork-3.md` 和同目录 `.Rhistory`。本地 `.git/info/exclude` 使用 `/docs/context-handoff/`，这些文件保持原样、不纳入版本控制；本地排除规则不会随克隆传播。

本次文档提交只包含 `AGENTS.md`、`PROJECT_CONTEXT.md`、`CURRENT_STATUS.md`。提交后保留上述 3 个已跟踪修改、5 个未跟踪日志文件及本地排除目录。UI 的主题、共享组件、字体、授权、测试与文档均已在 `3b7db21` 提交中，不再是待提交 UI 修改。

`local.properties` 存在、未跟踪且被忽略；`app/google-services.json` 存在且已跟踪，是客户端配置。未输出配置值或复制到新文档；这不能被解读为整个仓库/历史已完成凭据审计。

## 2. 实际已实现与验证边界

“源码确认”仅说明实现存在；本次没有重新运行它们。

| 功能 | 实际状态/代码依据 | 验证边界 |
| --- | --- | --- |
| 本地注册/登录、会话恢复 | AuthViewModel、NutrackRepository、PasswordHasher、SessionManager | 基于已有 Patient 注册；非云端认证 |
| CSV 种子、旧问卷迁移 | 严格 CsvParser、SeedMetadata 事务、LegacyQuestionnaireMigration 提交后清理 | P0 历史验收；未重新迁移设备库 |
| Room v12 | 9 张表、5–12 Schema、连续显式迁移 | 不支持声称 v1–v4 升级已验证 |
| DataStore | 仅 current_user_id/last_page；Loading/ReadError 与安全 route 回退 | 没有独立 isLoggedIn/问卷完成持久布尔值 |
| 问卷和实际每日摄入 | Questionnaire/DailyIntake ViewModel、Repository、按用户/日期 DAO、新增与编辑 UI | 每日多条；没有据此自动重算 HEIFA |
| Home/Insights/导航 | DashboardViewModel + 真实摄入聚合、NavHost/状态恢复 | HEIFA 基线仍来自 Patient 字段 |
| 水果查询/普通 AI | FruitNutritionGateway、FirebaseAiGateway 和对应状态处理 | 实时外部服务今天未验证 |
| Agent 两阶段闭环 | 模型选工具→受控执行→独立 JSON→本地校验→DRAFT/SUCCEEDED 同事务 | 历史真实 C 已成功；不是当前全场景重新验收 |
| 审计、失败、取消、重启恢复 | AgentRepository、NutriCoachViewModel、失败 codec；遗留 RUNNING 收敛 | 恢复不自动请求；历史 Debug 有零新增请求证据 |
| 确认/放弃/到期完成 | ActionDispatcher、唯一 actionId、离线 WorkManager | 确认仅更新计划，不执行建议；见 R3/R4 |
| 原生 UI 美化 | `3b7db21` 18 文件完整在 HEAD，Geist/授权/共享组件均存在 | 历史离线 UI 验证；未重新真实调用 Agent |
| Debug 六阶段 trace | 8 文件增量已接入正常 Application，Release No-op | 尚未提交；实际 Logcat 展示/逐段教学未完成 |
| Clinician 页面 | 2026-10-22 已关闭访问入口，固定口令、全患者统计加载与 AI 分析界面已移除 | 没有服务端角色/范围授权，不能称完整临床权限系统 |

生产代码中未发现旧 generativeai import、FunctionResponsePart 或破坏性迁移调用。ACTIVE 仅为预留枚举/兼容状态，并非已实现的激活流程。

## 3. 当前配置快照

依据 `gradle/libs.versions.toml`、wrapper、根与 app Gradle、模型/数据库源码，核对的是**声明配置**，不是本次运行依赖解析。

| 配置 | 当前值 |
| --- | --- |
| module / applicationId | `:app` / `com.example.nutritrack` |
| Gradle / AGP | 8.10.2 / 8.8.0 |
| Kotlin / KSP | 2.0.0 / 2.0.0-1.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 35 |
| Java/Kotlin 字节码目标 | 11；不是实际 Gradle 运行 JDK 的证明 |
| Compose BoM / Navigation | 2024.04.01 / 2.7.7 |
| Room / DataStore / Coroutines | 2.6.1 / 1.2.1 / 1.9.0 |
| Retrofit / OkHttp | 2.9.0 / 4.12.0 |
| WorkManager | 2.12.0 |
| Firebase BoM / Google Services plugin | 34.18.0 / 4.5.0 |
| firebase-ai | 由 BoM 管理；历史实际解析为 17.16.0，本次未重新解析 |
| Agent / 普通 AI 默认模型 | `gemini-3.5-flash-lite` / `gemini-3.8-flash` |
| Agent 请求/总时限 | 60 秒 / 128 秒；最多 4 个提议工具，两个阶段各至多两次尝试，重试等待 7 秒 |
| 结构化生成 | temperature 0.1，maxOutputTokens 2048；无 usageMetadata 计费统计 |
| Release | minify=false；存在历史 unsigned APK，不表示发布签名/安装/Play Integrity 验收 |
| App Check / Manifest | Debug 与 Release Provider 分开；allowBackup=false、usesCleartextTraffic=false |

## 4. 正在进行、未完成与仅讨论

1. **日志开发已在工作区，尚未收尾交付。** 待完成一次获授权的实际 Debug 运行，核对六阶段日志，然后逐段解释 prompt→工具选择→执行→evidence→JSON→校验落库。日志源码存在、测试通过记录不代替这一步。
2. **新 UI + 新日志组合的真实 Agent 路径未重新验证。** 两 Fork 都记录真实模型请求数 0；不得把旧成功迁移成组合版本的新验收。
3. **项目记忆初始化与文档交付。** 三个根文档单独纳入 `docs: initialize NutriTrack project memory` 提交；历史报告仅本地保留并排除，原有代码/日志修改不纳入提交。
4. **未实现/仅设计：** 真实后端同步、服务端账户/token 交换、同步 outbox/上传队列、多设备冲突合并；`SYNC_CONTRACT.md` 不是实现。
5. **仅讨论或范围外：** 精确提醒/通知、自动执行营养建议、显式 ACTIVE 激活、RAG/向量检索、多 Agent/自动多模型回退、降低 minSdk。不能默认开工。

## 5. 已知差异、风险与待验证问题

本次只记录，不修复。R1/R2 为静态可确认的现状；R3 为已确认代码语义不一致但未运行复现；R4 为基于调用链识别的恢复风险。

| ID | 发现 | 证据与影响 |
| --- | --- | --- |
| R1 | 历史固定口令风险已于 2026-10-22 封闭 | Clinician 不再读取任何患者统计，仅显示访问不可用；服务端身份/角色校验仍未实现，未来恢复入口必须先补授权 |
| R2 | Settings 仍有旧 UI 数据边界例外 | Settings 仍直接取 Repository/LaunchedEffect 读取；2026-10-22 Clinician 已移除数据访问并加入 UiDataBoundaryTest，覆盖八个页面。Settings 暂不在此检查范围 |
| R3 | 到期日期判定不一致 | `K/agent/NutritionPlanLifecycle.kt`：Scheduler 在 `endDate.plusDays(1)` 本地零点安排；Processor 仅在 `today.isBefore(endDate)` 时 NotDue，故结束日当天也可完成。现有测试主要覆盖昨天/明天，未证明当天边界；正常调度不一定触发，但手动调用/时间变化场景有风险。应先明确包含结束日的语义 |
| R4 | 非当前用户的到期工作可能缺少完整恢复 | Processor 在登出/换用户时 NoOp，Worker 视为 success；VM 恢复只 reconcile 最新 Run 的计划。旧已确认计划如不再是最新计划，重新登录后是否重新安排未获证据。不能取消用户隔离来修复，应先验证退出→到期→重登及多计划路径 |
| R5 | 旧文档时间口径混杂 | REFACTOR_PLAN 首段仍是非 Git/v5/旧 SDK；AGENT_UI_ACCEPTANCE 下文有旧“最新阻塞”；当前先读其顶部 2026-10-02 成功，再按历史日期解释 |
| R6 | 本地测试产物不绑定当前全部源码 | 最新残留 Debug XML 是选定 40 项；旧 UI 148/18 及旧 Release 结果不等于当前全量已通过，APK 存在也不证明最新构建成功 |
| R7 | 环境与服务边界未复核 | Firebase Console enforcement/AI Monitoring/配额、模型当前可用性、受保护设备状态/测试 APK、Release 真机及长期稳定性均未访问 |

其他重要差异：主报告称“保留 LoginActivity”，实际文件只含 LoginScreen，由 WelcomeActivity 承载；原 UI 默认动态配色已被 Fork 3 改为默认品牌色。DataStore 逻辑字段名与磁盘 snake_case 键名不同，不应混淆。工具数量已按最新需求取消“至少两个”门槛。

Git 无未合并条目；两个 Fork 的现有成果未发现必须择一覆盖的冲突。聊天分叉点仍未知；应用真实行为不能仅凭这一结论断言完全无冲突。

## 6. 构建与测试证据

**本次执行：**只读检查及文档交叉校验；新 Gradle/测试/设备任务 **0**，真实 AI 请求 **0**。因此不宣称“当前工作区已重新构建/全量测试通过”。

| 来源 | 能确认的结果 | 不能推出的结论 |
| --- | --- | --- |
| 现存 `app/build/test-results/testDebugUnitTest/TEST-*.xml` | 5 个 suite，共 40 tests，failures/errors/skipped 全 0：模型配置12、Parser12、重试5、日志10、敏感日志边界1 | 不是全量 148 项；未重新执行；不能单独绑定当前全部源码 |
| Fork 2 日志交接 | 40 项选定 JVM、assembleDebug、compileReleaseKotlin、compileDebugAndroidTestKotlin、lintDebug 历史通过 | 仪器源码编译不等于设备测试，未实际验证 Logcat |
| Fork 3 + `docs/UI_REFRESH.md` | 2026-10-13 记录 JVM148；选定 API35 设备18；视觉6项复跑；Debug/AndroidTest 构建与编译通过 | 6项是复跑，不能叠加为24个独立测试；0真实AI；不是全部尺寸/无障碍覆盖 |
| 现存 `app/build/reports/lint-results-debug.xml` | 0 errors、69 warnings，与历史 UI 报告一致 | 不是本次 lint；剩余警告仍存在 |
| 现存 connected XML | 仅见一份旧 Medium_Phone_API_35 的单项通过结果 | 不作为新日志设备验收或 UI18项完整独立证明 |
| `docs/AGENT_UI_ACCEPTANCE.md` 顶部 | 2026-10-02 真实 C 成功、2次模型调用、1份DRAFT、重启0新调用，`e4d2d01` 保留结果 | 非 Release/真机、非全工具外网、非长期可靠性；水果/scheduler 存在替身 |
| 主交接报告后续正常 App 演示 | 历史记录登录→生成→同草稿恢复、两个本地工具、未确认采用 | 本次未读取设备/截图；不能替代新的实际运行 |

Debug XML 的原始 timestamp 是 `2026-10-17T17:16:34`（未在 XML 中显式标注时区，此处不擅自换算）。UI 文档的验证日期 10-13 与提交日期 10-18 是不同事件，不自行改写为同一天。

需要代码验证时再运行与任务相称的增量任务；先查看当前差异与真实探针开关，避免默认真实网络。当前已有 migration、CSV、会话、用户隔离、Agent/Parser/重试、ActionDispatcher、Worker 和 Compose 测试源码，本次只检查覆盖入口，未执行它们。

## 7. 下一步建议与上下文恢复

建议顺序（不是已授权的自动开发计划）：

1. 保留日志增量，优先完成用户未完成的实际日志讲解任务；真实请求前明确当前任务预算、核对设备身份，只读取 `NutriTrackAgentTrace`，不导出完整 Logcat。
2. 如继续工程化，先处理 R1/R2 的 Clinician 认证与 UI 数据边界，再以定向测试验证 R3/R4；本次不预先替用户决定到期边界或改动代码。
3. 依据变更做 JVM/构建/lint 和独立设备回归；需要发布时另做 Release Play Integrity 真机验收。不要为接手上下文重新消耗模型额度。
4. 未来需要同步/新能力时，先确认产品范围，再基于现有合同设计，不建立空实现。

新 Thread 应将 Project 指向本仓库根目录，并按 `AGENTS.md → CURRENT_STATUS.md → PROJECT_CONTEXT.md` 恢复；随后按任务读取专项文档和实际代码。不要把其他目录的 master 分支当成本项目分支。原始报告是本地可选参考，不在新克隆中也不影响读取这三个根文档。

可直接使用的接手提示：

> 请先阅读根目录 AGENTS.md、CURRENT_STATUS.md、PROJECT_CONTEXT.md，并只读核对 Git 分支、HEAD、已有差异及受保护 stash。当前 UI 提交为 3b7db21，Agent Debug trace 仍是工作区增量；以现场代码重新确认。先说明你理解的状态和本次任务边界，随后在我授权的任务范围内继续。不要自动调用真实 AI、清理数据、合并或提交。

## 8. 初始化自检

- 三份交接报告全文读取，主历史与两个 Fork 增量去重整合；原文件保持原样，仅本地查阅并由仓库本地排除规则保护。
- 三个根文档职责分开：稳定指令、长期决策、易变状态；共同使用同一 Android 核对基线、Room/模型/日志状态及验证边界，文档提交不改变代码实现。
- P2-2 未被误写为尚未开发；同步、ACTIVE 激活、Release 真机及新日志实跑未被写成已完成。
- 重大差异记录在 PROJECT_CONTEXT C1–C6 与本文件 R1–R7，没有擅自改代码或裁决未确认业务语义。
- 不复制凭据、固定口令、Token、用户健康数据或原始模型内容；新文档仅保留必要标识/路径和脱敏结论。
- 初始化阶段曾对 199 个既有非缓存文件做哈希核对，内容、HEAD、Git index 和 stash 均未变。本次文档提交阶段重新核对这些受保护文件及原有代码差异，保留原始报告、其他未跟踪内容和 stash；仅三个根 Markdown 纳入新提交，本地排除规则独立留在 `.git/info/exclude`。哈希核对不包含 Git 内部文件、Gradle/IDE 缓存及 build 目录。

## 9. 2026-10-21：Agent 源码讲解核对

- 为解释既有实现，重新静态核对两阶段 Gateway/编排、工具声明与 Registry/Dispatcher、确定性趋势、evidence、Parser、Room 事务/恢复、确认幂等及相关测试源码；架构与功能范围没有变更。
- 现场分支为 `refactor/nutritrack-engineering`，HEAD 为 `85dad7a894dcba44ceb839af05f2aa7faba3cac7`。原有三个已跟踪代码修改和五个未跟踪日志文件保留；本次只向本文件追加核对记录，讲解材料保存在仓库外。
- 本次未运行构建、JVM/设备测试或真实 AI 请求。2026-10-02 的真实 Debug 隔离成功与零请求恢复仍为历史证据，不构成当前组合版本的新验收。
- 讲解特别区分：正常路径两次模型调用与每阶段有限重试、完成草稿恢复与中断任务失败收敛、合法 evidence 引用与建议事实正确性、结构/范围规则通过与医学有效性。第 5 节的已知风险及第 4 节的待验证工作仍未解决。


## 10. 2026-10-22：公开源码隐私清理与验证

- 本轮分支仍为 `refactor/nutritrack-engineering`，HEAD 仍为 `85dad7a894dcba44ceb839af05f2aa7faba3cac7`；没有暂存、提交、切换分支、推送或操作 stash。受保护 stash 保留；原有 Agent 三个集成文件及五个新增日志文件均在通用包名下保留，除命名空间/路径替换外内容哈希一致。此前 CURRENT_STATUS 的追加说明也保留。
- 清理范围与剩余边界见 [PRIVACY_REVIEW.md](docs/PRIVACY_REVIEW.md)。应用包名变化意味着独立安装；未安装、卸载、迁移或读取原设备数据。真实 Firebase 配置和本地旧 AI key 已删除，没有修改控制台、模型、依赖或签名配置。
- **本次执行（2026-10-22）：** `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain` 最终成功。28 suites / 158 JVM tests，0 failures/errors/skipped；lint 0 errors/fatal、34 warnings。Debug APK 构建、Release Kotlin 编译、仪器源码编译通过；没有 Release APK 构建、设备测试或真实 AI 请求。
- 初轮全量测试暴露原有 Agent 日志增量与旧源码边界断言不一致：旧断言要求直接内联 `buildToolSelectionPrompt`，当前实现已先绑定 prompt 用于安全日志。更新测试分别断言该绑定及恰好一次发送，保留两阶段/无 FunctionResponse 的限制，没有修改 Gateway 请求行为或删掉断言。Clinician 加入数据访问边界检查，并修正测试参数列表中默认回调花括号会被误作函数体的问题。
- 使用全新白名单导出目录，在没有 `local.properties`、真实 Firebase 配置或旧 build 输出的情况下，通过环境变量指定本机 SDK，执行 `--offline :app:assembleDebug`：36 项任务全部实际执行，成功。该临时构建目录不作为最终公开副本。
- 当前发布选择范围共 184 个文件。通用敏感模式、原身份/原种子电话/已识别项目配置的定向字节扫描均无匹配；八份历史 Schema、字体、图像和字体许可内容不变。Debug APK 解压后的各条目也未匹配原身份/配置值。导出器的敏感模式和排除路径防护已用人工夹具验证。
- 最终公开目录与 ZIP 由导出器生成，复制后复扫、逐条读回 ZIP 对比内容并检查 CRC；包含当前未提交源码但不包含旧 Git 历史或本地私有文件。**原开发仓库历史与私有产物仍未脱敏，不能直接推送或整目录打包。** 公开目录内重新初始化仓库时另选公开作者名/no-reply 邮箱。项目级 LICENSE 和课程素材再发布权仍未确定。

## 11. 2026-10-22：公开副本体验与 GitHub 展示改进

- 改动范围是无历史的公开副本，未回写原开发仓库、提交或推送；本目录不具有旧历史分支或受保护 stash。
- Settings 从 Composable 直接取账户改为按会话用户隔离的 ViewModel，只把展示字段送入 UI，补齐安全错误、重试与取消边界；UiDataBoundaryTest 覆盖该页。
- AI 配置缺失在进入页面时说明，阻止新生成但保留已有草稿和计划的本地操作；未改变 Gateway、工具预算、恢复、确认或生命周期协议。
- 详细实现、命令对应结果、临时模拟器与截图来源、已知未完成项均在 PUBLIC_RELEASE.md。R3/R4 仍不能称已解决；LICENSE 与素材权利尚待作者确认。

## 12. 2026-10-22：README 展示结构调整

- 按用户确认的 GitHub 参考方向重写 README：产品介绍、技术徽章、四张核心界面、功能、技术栈、设计、快速开始、状态/路线图和文档入口。详细验收、历史问题和源码发布说明由既有专项文档承接。
- 保留样例数据、AI 配置、测试范围及许可证状态的必要说明；没有添加未经验证的 CI 成功或许可徽章，没有改变应用功能。
- 本次仅修改 README 与本状态文档，并同步到公开副本、更新分发 ZIP；执行文档链接/结构、敏感模式和归档一致性检查，未运行 Gradle、设备测试或真实 AI。前文 164/21/4 结果属于本日先前的实现验收，不是本次文档修改重新执行。
