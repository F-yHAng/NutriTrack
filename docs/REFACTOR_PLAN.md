# NutriTrack 工程化重构计划

> 审计日期：2026-09-04。先稳定本地数据链路，再接 Agent；当前无后端，只保留清晰扩展点。

## 当前基线与事实

- 首次基线：`./gradlew clean testDebugUnitTest assembleDebug --stacktrace`，27 秒成功，45 个任务执行；后续优先增量构建。
- 单元测试仅 1 个模板加法测试；仪器测试仅 1 个包名测试，因无连接设备未运行。
- 告警：Kotlin 2.0 下 KAPT 回退 1.9、2 个 Compose 图标弃用、Gradle 9 兼容性提醒。
- 当前目录不是 Git 仓库；`local.properties` 含构建期 AI key，不得进入版本控制或输出到日志。
- `data.csv` 含表头和 9 条患者记录；只在首次初始化时解析并写入 Room，是种子数据而非运行时存储。
- CSV 异常被内部吞掉后仍会标记完成，坏值默认为 `0`，可能留下永久空库或脏数据。
- Room v5 包含 `Patient/FoodIntake/UserAccount/NutriCoachTip`，但 `exportSchema=false` 且启用破坏性迁移。
- 问卷及完成状态仍在用户级 SharedPreferences；`FoodIntake` 虽有 DAO/Repository，业务 UI 从未创建或查询记录。
- Session、当前用户、末次页面也在 SharedPreferences；项目未使用 DataStore。
- 除 `GenAIViewModel` 外，Composable 直接取 Repository 并启动协程；UI→ViewModel→Repository→Room 链路不完整。
- `generativeai:0.7.0` 已弃用；模型名固定，Gemini Developer API key 被编译进 APK。
- Manifest 允许明文流量和备份；账户以明文密码查询，调试日志包含手机号等 PII。

## 实施约束

- Room 只存业务数据；DataStore 只存当前用户 ID、末次页面等小配置；登录态由 `currentUserId != null` 推导。
- 沿用 Application 手工注入与 ViewModel Factory；暂不引入 Hilt，不增加无复用价值的 UseCase 层。
- 每个 Room 版本只承载一组明确 schema 变化，并在同一提交附 Migration 与测试；P0/P1 完成后再做 Agent。
- 首轮只执行 P0-0～P0-2；确认 schema、迁移与导入测试稳定后，再授权 P0-3A～P0-6。

## P0：数据一致性与测试基线（已完成）

- [x] **P0-0 Git、密钥与可构建基线**
  - 文件/修改：根目录、`.gitignore`；初始化 Git，忽略 `local.properties`、签名文件与 build 目录，提交当前可构建状态并扫描密钥。
  - 收益/风险：支持分阶段回滚；风险是误提交凭据，发现已泄漏的 key 必须先轮换。
  - 验收：工作树干净、敏感文件未跟踪，基线增量 `testDebugUnitTest assembleDebug` 通过。
- [x] **P0-1A 保持 Room v5，仅建立 Schema 历史**
  - 文件/修改：`app/build.gradle.kts`、`NutrackDatabase.kt`、`app/schemas/5.json`；启用 schema 导出，不改 Entity 和版本。
  - 收益/风险：冻结可信升级起点；风险是 v1-v4 schema 已缺失，先确认是否存在旧安装用户并记录支持范围。
  - 验收：生成并提交 v5 schema，数据库结构与基线一致；无旧用户则明确“仅支持 v5 起升级”，有用户则先补完整路径。
- [x] **P0-1B KAPT→KSP 独立迁移**
  - 文件/修改：根/`app/build.gradle.kts`；启用已声明 KSP，移除 `kotlin-kapt` 与 Room kapt compiler，并随提交增加构建回归测试。
  - 收益/风险：消除 Kotlin 2.0 回退并加快生成代码；风险是处理器切换产生非预期 schema 差异。
  - 验收：切换前后 v5 schema 无差异，构建无 KAPT 回退告警，单元测试通过。
- [x] **P0-2 SeedMetadata 与幂等 CSV 导入，Room v5→v6**
  - 文件/修改：`CsvParser.kt`、Repository/Application、PatientDao、新 `SeedMetadata/Dao`、`MIGRATION_5_6`；校验表头、列数、ID/数值，事务写入患者和种子版本，仅成功后完成。
  - 收益/风险：导入失败可见、可重试且不重复；风险是严格校验暴露历史脏值，同一提交加入 parser、迁移与幂等测试。
  - 验收：正常文件精确导入 9 人；缺列/坏值/重复 ID 全部回滚；连续初始化两次记录数不变；v5 数据升级保留。
- [x] **P0-3A QuestionnaireProfile 迁移，Room v6→v7**
  - 文件/修改：Questionnaire/Welcome、Repository、新 `QuestionnaireProfile/Dao`、`MIGRATION_6_7`；先幂等 UPSERT Room，成功后写迁移版本标记并删除旧键，完成状态由有效 Room 记录推导。
  - 收益/风险：问卷成为 Room 业务数据；Room 与 SharedPreferences 无法共享原子事务，必须支持任意步骤崩溃后重试。
  - 验收：模拟写 Room 后、删旧键前崩溃仍可安全重跑；升级/重启/登录后数据不丢；v6→v7 测试通过。
- [x] **P0-3B 接通真实每日摄入功能**
  - 文件/修改：`FoodIntake.kt/Dao.kt`、Repository、Home/Insights、新 `DailyIntakeViewModel` 与最小保存、编辑、历史入口；不与问卷迁移混在一次提交。
  - 收益/风险：趋势和 Agent 拥有真实历史；风险是日期、时区和重复记录，需确定“一人一天一条”约束或显式多餐模型。
  - 验收：可新增/编辑/按日期查询本人记录，杀进程后仍存在；Insights 读取摄入历史而非仅展示 CSV 基线分数。
- [x] **P0-4 小型配置迁移到 Preferences DataStore**
  - 文件/修改：`SessionManager.kt`、Repository、Welcome/Home、Gradle；用 `SharedPreferencesMigration` 迁移 currentUserId/lastPage，lastPage 改受限 route/enum 并提供未知值回退。
  - 收益/风险：配置异步、事务化且可观察；风险是启动导航由同步变异步，问卷完成状态不得复制进 DataStore。
  - 验收：旧安装自动迁移；登录态仅由 currentUserId 推导；退出、末页恢复和未知 route 回退测试通过。
- [x] **P0-5 逐屏接通 UI→ViewModel→Repository→Room**
  - [x] **P0-5A Auth**：Login/Register 认证、注册、加载与错误状态迁入 `AuthViewModel`。
  - [x] **P0-5B Questionnaire**：问卷加载、校验、保存与完成状态迁入 `QuestionnaireViewModel`。
  - [x] **P0-5C Home/Insights**：患者、画像、摄入摘要与聚合状态迁入 `DashboardViewModel`。
  - 文件/修改：Login/Register/Questionnaire/Home/Insights、新 `Auth/Questionnaire/DashboardViewModel`；StateFlow+事件、轻量 Factory、生命周期感知收集；临时 UI 状态仅按需用 SavedStateHandle。
  - 收益/风险：业务状态从 Room 恢复且逻辑可单测；风险是迁移期双状态源，每屏提交同时加入 ViewModel 测试并删除旧状态。
  - 验收：数据型 Composable 不获取 Application Repository、不用 Activity `lifecycleScope`；旋转正确，进程重建后持久状态由 Room 恢复。
- [x] **P0-6 整体测试审计与关键 UI 冒烟**
  - 文件/修改：`app/src/test/`、`app/src/androidTest/`、Gradle；审计前述任务随提交的测试，替换 `Example*Test`，只补一条登录→问卷→摄入关键 Compose 流程。
  - 收益/风险：覆盖最终集成缺口；风险是仪器测试慢，纯规则继续放 JVM 测试而不是拖到本阶段。
  - 验收：49 个 JVM 与 29 个 API 35 仪器测试全绿；关键流程连续通过两次；lint 0 错误、71 个既有警告。

## P1：结构、依赖与安全收敛

- [x] **P1-1 Gradle 与依赖治理**
  - [x] **P1-1A Version Catalog**：插件及直接依赖版本已统一收口，解析依赖图保持不变。
  - [x] **P1-1B 构建告警**：`build --warning-mode all` 无 Gradle 9 弃用告警；Compose 图标告警留待 P1-2。
  - [x] **P1-1C 谨慎升级**：Coroutines 对齐实际解析版本 1.9.0，AndroidX JUnit/Espresso 升至 1.3.0/3.7.0；DataStore 1.2.1 已是稳定版本。
  - 版本/延期：保持 Gradle/AGP/Kotlin/KSP 8.10.2/8.8.0/2.0.0/2.0.0-1.0.21；Room 2.8.4 与旧 AI 依赖存在序列化 ABI 冲突，Lifecycle 2.10.0 会连带抬升 Compose，二者回退；Retrofit/OkHttp 大版本和旧 AI SDK 留待对应专项。
  - 产品决策：`minSdk`、`targetSdk`、`compileSdk` 均保持 35；降低 `minSdk` 需先明确设备兼容范围。
  - 文件/修改：根/`app/build.gradle.kts`、version catalog、wrapper；统一版本，删除残留；逐组升级并回归；降低 minSdk 仅作为有设备兼容需求时的产品决策。
  - 收益/风险：依赖来源清晰；风险是 AGP/Kotlin/Compose 联动，禁止一次性全量升级。
  - 验收：`assembleDebug testDebugUnitTest lintDebug` 通过；所支持 API 模拟器完成核心流程冒烟。
- [x] **P1-2 UI、导航和水果网络边界**
  - [x] **P1-2A 导航与必要 UI 整理**：Home 底栏改由 Navigation Compose `NavHost` 管理，复用 `AppRoute`，支持单顶、页面状态保存/恢复、末页恢复、返回栈与登出保护；3 处图标弃用已修复。
  - [x] **P1-2B FruitNutritionGateway 网络边界**：DTO、HTTP 与异常仅存在于单一远程实现；Application 注入 ViewModel，UI 使用领域模型与加载/成功/空/错误状态，仅响应用户查询或重试。
  - 文件/修改：Home/Questionnaire/Navigation/NutriCoach、FruityVice API；底栏改 NavHost，只拆超大 UI 区块；仅抽象一个被实际使用的 `FruitNutritionGateway`，统一超时和错误映射。
  - 收益/风险：导航与网络可测且无重复层；风险是返回栈变化，保留现有登录/问卷 Activity，不强推单 Activity 重写。
  - 验收：返回键/末页恢复正确；UI 不创建 Retrofit；网络错误、空结果和超时均有稳定状态。
- [x] **P1-3 Manifest、本地认证与日志加固**
  - [x] **P1-3A Manifest、备份、网络与日志**：合并 Manifest 禁止应用数据备份与全局明文流量；移除无效备份规则及 PII/异常对象日志，并加入可重复边界测试。
  - [x] **P1-3B 本地密码安全与 Room v8→v9**：每账户使用 SecureRandom 盐与 PBKDF2-HMAC-SHA256（版本化参数、常量时间比较）；显式迁移原子重建账户表并移除明文列，非法旧记录失败回滚。
  - 文件/修改：Manifest、备份规则、`UserAccount*`、日志点、`MIGRATION_8_9` 与 v9 Schema；关闭全局明文流量和备份，移除 PII 日志，本地演示密码改带盐哈希。
  - 收益/风险：降低健康数据与密码泄漏；风险是账户迁移失败，需 Migration 测试及测试账号恢复方案。
  - 验收：数据库无明文密码，HTTP 被拒，备份规则检查通过，旧账户升级后仍可登录。
- [x] **P1-4 提前迁移 AI SDK 与安全接入**
  - [x] **P1-4A 隔离 AI 边界**：新增供应商无关 `AiGateway`、结构化请求/响应与错误类型；`GenAIViewModel` 仅依赖网关并覆盖空响应、超时、网络、SDK、取消和防重复请求。
  - [x] **P1-4B Firebase AI Logic 本地迁移**：使用 Firebase BoM 34.18.0、`firebase-ai` 17.16.0 与 Google Services 插件 4.5.0；接入 `FirebaseAiGateway` 和集中模型配置，移除旧 SDK、适配器、BuildConfig Gemini key 与 Gradle key 注入。
  - [x] **P1-4C App Check 设备验收**：Play Integrity 与 Debug Provider token 均已注册；API 35 上使用无用户/健康数据提示完成一次受保护的真实成功请求，并在临时断网时验证 Error 状态、无假结果且应用不崩溃，随后恢复网络。
  - 文件/修改：Gradle、`GenAIViewModel.kt`、`AiGateway/FirebaseAiGateway`、Firebase/App Check；Debug 源集仅用 Debug Provider，release 源集仅用 Play Integrity，配置未完成时返回可恢复错误。
  - 收益/风险：在 Agent 前关闭 key 暴露并让 AI 可伪造测试；Firebase 客户端配置可在 APK 中，但不得当作秘密，风险为配额和环境配置。
  - 验收：Debug/Release APK 不含旧 Gemini Developer API key 或旧 SDK，Fake gateway 测试通过；App Check 受保护的真实成功请求及断网失败态均已验证。

## P2：Agent 与后端扩展点

- [x] **P2-1 可审计的营养规划 Agent**
  - [x] **P2-1A 持久化、审计与只读工具基础**：Room v10 新增 `AgentRun/AgentToolCall/NutritionPlan`，显式 `MIGRATION_9_10` 保留 v9 数据；通过可注入 Clock/ID、强类型白名单和可信 `AgentContext` 提供画像、31 天内摄入历史、确定性趋势及水果营养四个只读工具。审计仅保存规范化参数与无身份字段的最小结果，计划仅允许 DRAFT，计划写入与 SUCCEEDED 状态更新保持同一事务。
  - [x] **P2-1B 模型调度与 Agent 交互**
    - [x] **P2-1B1 可审计模型调度核心**：建立供应商无关模型边界、只读工具白名单、严格 v1 JSON 与 evidence 映射；当前单次模型请求时限为 60 秒、两阶段整体时限为 128 秒，最终计划只有通过字段、枚举、数值、日期和医疗安全校验后，才在原事务中保存 DRAFT 并完成 Run，所有失败保留脱敏 trace。
    - [x] **P2-1B2 Agent 交互验收**
      - [x] **P2-1B2A ViewModel/UI 与恢复**：独立 NutriCoachViewModel 状态机支持生成、取消、重试、最近 DRAFT/trace 恢复和遗留 RUNNING 安全终止；UI 仅展示草案与脱敏工具摘要，继续禁止写业务数据。
      - [x] **P2-1B2B1 安全失败追踪**：在 v10 现有错误字段中保存版本化失败阶段与稳定错误码；Firebase 阻断、非正常结束和反序列化错误显式分类，模型 evidence 别名仅在调度器内映射为审计 ID，并补齐失败持久化、恢复、UI 脱敏及业务数据不变回归。
      - 历史 P2-1B2B2 原生回传路径：2026-09-12 形成 FAILED Run、3 条成功 ToolCall、DRAFT=0，失败为 FUNCTION_RESPONSE_MAPPING/MODEL_SDK；此路径已移除并由以下两阶段实现替代，不作为当前未完成验收项，也不改写历史失败结果。
      - [x] **P2-1B2C 两阶段闭环收口**
        - [x] **P2-1B2C1 本地实现与自动验收**：生产路径改为“单次 Function Calling 选择全部工具 → 本地受控执行 → 独立严格 JSON 生成”，最多 4 个工具；两个模型阶段各最多两次尝试，但只有明确 SDK 超时/网络类型、公开 5xx 或有限公开瞬时语义才等待 7 秒重试，受既有 128 秒 deadline 约束。重试不重建 Run、不重复成功工具/evidence，也不重复保存 DRAFT；证据使用 `e1..e4` 临时别名并脱敏、限长、聚合，旧 Chat/FunctionResponsePart 路径已移除。调整原因是原生函数结果回传持续在 SDK/后端边界失败且无可诊断状态码。
        - [x] **P2-1B2C2 真实两阶段验收**：2026-10-02 唯一候选 `gemini-3.5-flash-lite` 在生产 Gateway/Agent/ViewModel 与隔离磁盘 Room、合成数据下完成一次真实 C；选择/生成各 1 次（3,254/2,053 ms），Run=SUCCEEDED、ToolCall=2/2 SUCCEEDED、DRAFT=1，生产页面显示草稿与依据。页面重建及新进程启动均恢复同一草稿，恢复调用=0、业务指纹不变；随后该模型成为集中 Agent 默认，普通 AI 仍为原模型。60 秒/128 秒、SDK、提示/schema/安全规则不变；Debug 成功不代表 Release 真机验收或长期稳定性，水果网络使用替身。旧模型失败与原生回传阻塞证据保留，不据单次成功归因。详见 [AGENT_UI_ACCEPTANCE.md](AGENT_UI_ACCEPTANCE.md)。
  - 文件/修改：新 `agent/`、`AgentRun/AgentToolCall/NutritionPlan` Entity/DAO、Coach ViewModel；首版只含读取画像、摄入区间、趋势和水果营养工具，schema 约束计划输出并记录 trace。
  - 收益/风险：Agent 基于真实数据且可解释；风险是幻觉、健康越界和 PII 外发，需最小化输入、规则校验与免责声明。
  - 验收：固定输入可重放 trace；非法工具参数/JSON 被拒；离线、超时和拒答均记录失败且不破坏数据。
- [x] **P2-2 用户确认、后台任务与同步契约**
  - [x] **P2-2A 用户确认与幂等执行基础**：Room v11 将计划状态扩为 `DRAFT/CONFIRMED/ABANDONED`，显式 `MIGRATION_10_11` 保留全部 v10 数据；`RoomNutritionPlanActionDispatcher` 使用确定性 `confirm:<planId>` actionId、唯一索引和条件更新，保证重复点击、重建或重启后的确认最多生效一次。确认仅持久化计划状态、确认时间和 actionId；放弃仅转为 `ABANDONED`，两者均不删除 Run/ToolCall，也不改 Patient、QuestionnaireProfile、FoodIntake 或 UserAccount。NutriCoach UI 明确显示“Confirm Adoption”与“Discard Draft”，并恢复终态草稿而不重新调用模型。
  - [x] **P2-2B 后台生命周期与同步契约**：Room v12 以显式 `MIGRATION_11_12` 保存 `updatedAtEpochMillis/completedAtEpochMillis`，确认/激活计划会用 WorkManager 唯一工作 `nutrition-plan-complete:<planId>`（`KEEP`）在既有结束日期后进行完全离线的到期检查。Worker 每次重新核验当前 DataStore 会话用户、计划归属、`CONFIRMED/ACTIVE` 状态与结束日期，并用条件更新幂等写入 `COMPLETED`；停止尚未完成的计划会取消同名工作，绝不调用 Agent、Firebase、网络、通知或 AlarmManager。`docs/SYNC_CONTRACT.md` 仅定义未来后端的 ID、用户隔离、actionId、状态机、冲突和离线恢复边界；当前无后端、同步表或后台网络调用。
  - 收益/风险：建议与副作用分离；已用持久 actionId/条件更新降低重复执行风险。确认状态不等同于自动执行健康建议，任何后续业务副作用仍需独立授权与设计。
  - 验收：未确认草稿无业务副作用；首次确认、重复确认、放弃后禁止确认、确认后的唯一任务、到期/未到期、跨用户、重复执行、重建恢复与 v11→v12 迁移均由本地/隔离 API 35 测试覆盖。

## 参考依据

- Room 迁移：<https://developer.android.com/training/data-storage/room/migrating-db-versions>
- DataStore：<https://developer.android.com/topic/libraries/architecture/datastore>
- Firebase AI Logic 迁移：<https://firebase.google.com/docs/ai-logic/migrate-from-google-ai-client-sdks>
