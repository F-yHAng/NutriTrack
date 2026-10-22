# NutriTrack 长期项目上下文

> **2026-10-22 公开副本后续决策：** 本目录无原 Git 历史；下文保留原开发项目的历史背景。Settings 现已与主流程一样使用 ViewModel / StateFlow，只暴露 SettingsAccount 展示字段；无 Firebase 初始化时 UI 明确标示可用性并禁用新生成，仍允许管理已存草稿。人工种子评分明确标为 baseline；水果查询对所有样例画像可用，不再自动请求随机图片。首次注册提供人工号码填入辅助但保留身份校验。上述改变不触及 Room Schema 或 Agent 协议。依据和验证见 [PUBLIC_RELEASE.md](docs/PUBLIC_RELEASE.md)。

> **2026-10-22 后续决策：** 为公开源码移除个人身份，命名空间/应用 ID 与源码、测试、Schema 目录统一为 `com.example.nutritrack`；八份历史 Schema 内容及数据库版本不变。应用 ID 变化产生独立安装，不迁移原安装数据。真实 Firebase 客户端配置不再分发，无配置时仅关闭 AI 能力并允许本地构建；模型、SDK 和变体 App Check 机制未改。CSV 全部使用人工记录。Clinician 的客户端固定口令与全患者统计入口已移除；未来必须有服务端角色/范围授权才能恢复。历史叙述中的包名/项目值已脱敏；旧验收不构成新包名的在线验收。共享忽略规则保护私有交接目录；无 Git 历史的导出是公开边界。以下旧日期内容保留历史语境，当前风险以 CURRENT_STATUS 与 PRIVACY_REVIEW 为准。

初始化核对日期：2026-10-18（Asia/Kuala_Lumpur）。本文件保存背景、架构与决策；易变的分支、工作区、测试与下一步见 [CURRENT_STATUS.md](CURRENT_STATUS.md)，开发规则见 [AGENTS.md](AGENTS.md)。

## 1. 来源、继承与证据等级

三份交接报告已逐段完整读取并保留原文件，均位于本地 `docs/context-handoff/`。该目录由当前仓库的 `.git/info/exclude` 排除，不随本次提交或新克隆分发；下表是本地来源索引，新检出无需具备原报告也能使用本文：

| 来源 | 角色 | 本次核对结论 |
| --- | --- | --- |
| `legacy-main.md`（本地） | 共同历史：P0/P1、Agent 演变、P2-2、真实验收与正常 App 演示 | 以其组织开发历史，再以源码、Git 和仓库验收文档判定实现 |
| `legacy-fork-2.md`（本地） | 增量：Agent Debug 六阶段日志 | 3 个已跟踪文件修改、5 个新增文件均在当前工作区，尚未提交 |
| `legacy-fork-3.md`（本地） | 增量：原生 Compose UI 美化及提交 | `3b7db21` 的 18 个文件完整保留在当前分支历史和工作区 |

两个 Fork 的精确聊天分叉位置未知，不能由提交父子关系反推；它们也不是两条待合并的 Git 分支。Android 实现核对基线为 `… → c916642 → e4d2d01 → 3b7db21`；随后仅提交三个根记忆文档，不改变该代码基线。日志增量仍属于未提交工作，不包含在文档提交中。

采用四种证据表述：**源码确认**指本次静态检查存在实际实现；**历史验证**指有旧文档/交接记录或已有测试产物；**待验证**指本次未复现或证据不足；**未实现/仅契约**指当前没有对应产品实现。源码存在不等于所有运行场景已通过。

初始化时只读核对代码、配置、Git、文档和已有报告；交付时清理并提交三个根 Markdown，同时设置本地排除规则。未修改 Android 代码、Gradle、测试或原报告，未运行构建、ADB、App、在线服务或新测试。外部记忆只能辅助定位，旧摘要中的“Firebase 始终阻塞”不能覆盖仓库 2026-10-02 的成功记录。

## 2. 背景、范围与用户要求

NutriTrack 是原生 Android 营养记录与可审计营养规划 MVP。项目说明必须以代码和验证证据为依据，明确区分实际实现、离线模拟、真实服务验收及未来计划；不增加无实际用途的架构，也不编造性能成果。

当前产品范围：

- 本地账户注册/登录和会话恢复。注册验证已有 Patient 的 ID 与手机号；不是开放互联网注册，也没有云端账户系统。
- 饮食问卷、Room 营养画像、每天多条摄入的新增/编辑/按日期查询、Home/Insights 汇总。
- HEIFA 分数仍来自 Patient 的种子/已有分数字段；真实摄入历史另行聚合。不能说录入摄入会自动重新计算全部 HEIFA。
- FruityVice 水果营养查询、普通 AI 激励文案及历史、NutriCoach 营养规划 Agent。
- 草稿展示、审计、恢复、用户确认/放弃，以及已确认计划的离线到期状态更新。
- Clinician 导航保留为关闭入口（2026-10-22）；旧客户端固定口令、全患者统计及 AI 分析已移除，后续须有服务端角色/范围授权才能恢复。

用户明确约束：仅一般营养与生活方式建议，不做诊断、治疗、用药调整或保证疗效；计划先 DRAFT，确认与生成分离；保留数据、用户隔离、安全校验、审计与恢复；不盲目升级依赖或改写为单 Activity/Hilt 架构。UI Fork 新需求是美化原生界面并保留行为；日志 Fork 新需求是安全观察六个阶段，并在实际运行后逐段讲解，后半项仍未完成。

## 3. 代码地图与实际架构

项目为单一 Android application 模块 `:app`。以下 `K/` 代表 `app/src/main/java/com/example/nutritrack/`，`D/` 代表 `K/data/`，`A/` 代表 `K/agent/`。

| 层/职责 | 主要入口 | 边界 |
| --- | --- | --- |
| 组合根 | `K/NutrackApplication.kt` | 拼写是 Nutrack；lazy 手工注入 Room、Session、Gateway、Agent、ActionDispatcher 和 Scheduler；App Check 在 AI 使用前初始化 |
| 启动/认证 | `K/WelcomeActivity.kt`、`K/LoginActivity.kt`、`K/RegisterScreen.kt`、`D/AuthViewModel.kt` | LoginActivity.kt 当前仅定义 LoginScreen Composable；实际 Activity 为 Welcome、Home、Questionnaire |
| 业务 UI/状态 | Questionnaire、Home、Insights、DailyIntake、NutriCoach；对应 ViewModel | 主流程不可变 StateFlow、事件和生命周期收集；Settings 仍有例外；Clinician 不再访问数据 |
| 导航/样式 | `K/NavigationComponents.kt`、`K/ui/theme/`、`K/ui/components/NutriComponents.kt` | Home NavHost、AppRoute、单顶/保存恢复/未知 route 回 Home；保留多 Activity |
| 数据 | `D/NutrackRepository.kt`、`D/NutrackDatabase.kt`、各 Entity/DAO | Room 业务事实源；Repository 实现细粒度 DataSource 接口；无额外无用途层 |
| 配置/迁移 | `D/SessionManager.kt`、`D/LegacyQuestionnaireMigration.kt`、`D/DatabaseMigrations.kt` | 配置与业务分离，显式迁移和提交后清理 |
| 普通 AI | `D/AiGateway.kt`、`D/FirebaseAiGateway.kt`、GenAIViewModel/NutriCoachViewModel | 与 Agent Gateway 分开；供应商类型封装在实现中 |
| 水果网络 | `D/FruitNutritionModels.kt`、`D/FruityViceFruitNutritionGateway.kt` | 一个 Gateway、一个远程实现；HTTPS、有界超时、显式错误，无自动连接重试 |
| Agent | `A/NutritionPlanningAgent.kt`、AgentModelGateway、AgentToolRegistry/Dispatcher、AgentEvidenceBundle、NutritionPlanOutputParser、AgentRepository | 模型建议工具，App 验证执行；第二次独立生成；事务保存及可恢复 trace |
| 用户动作/生命周期 | `A/NutritionPlanActionDispatcher.kt`、`A/NutritionPlanLifecycle.kt`、`D/NutritionPlanDao.kt` | 确认 actionId 幂等；WorkManager 离线完成，不自动执行营养建议 |
| 构建变体 | `app/src/debug/`、`app/src/release/` | App Check Debug/Play Integrity 分开；Debug trace 与 Release No-op 分开 |
| 验证 | `app/src/test/`、`app/src/testDebug/`、`app/src/androidTest/` | JVM 规则、日志测试、Room/Compose/Worker 仪器测试；真实探针显式 opt-in |

### 数据与迁移

Room 当前 v12，共 9 张业务/审计表：Patient、FoodIntake、UserAccount、NutriCoachTip、SeedMetadata、QuestionnaireProfile、AgentRun、AgentToolCall、NutritionPlan。Schema 在 `app/schemas/com.example.nutritrack.data.NutrackDatabase/`，5–12 均存在；迁移链在 Database builder 全部注册，无破坏性回退。没有 v1–v4 Schema/迁移证据，不能宣称支持这些旧版本升级。

| 迁移 | 保留的决策及原因 |
| --- | --- |
| 5→6 | SeedMetadata；CSV 严格校验，患者和种子版本同事务写入；失败可重试，不静默填 0 |
| 6→7 | QuestionnaireProfile；旧问卷先提交 Room，再删旧业务键；崩溃重试只清旧键，不覆盖已迁移画像 |
| 7→8 | FoodIntake 新增 foodName 和用户/日期索引；同用户同日允许多条，不把问卷当摄入 |
| 8→9 | 重建 UserAccount，移除明文 password 列，迁移为版本化带盐哈希；坏旧记录让迁移失败而非清库 |
| 9→10 | AgentRun、AgentToolCall、NutritionPlan；Run/工具顺序及草稿唯一关联 |
| 10→11 | confirmedAtEpochMillis、confirmationActionId 唯一索引；用户确认幂等 |
| 11→12 | updatedAtEpochMillis、completedAtEpochMillis；旧更新时间从确认/创建时间回填 |

密码实现是 PBKDF2WithHmacSHA256、每账户 SecureRandom salt、参数版本化、常量时间比较；它保护本地账户密码，不能代指 Clinician 或云端认证完成。

SessionManager 的逻辑字段是 currentUserId/lastPage，实际键为 `current_user_id`、`last_page`；DataStore 名 `nutritrack_preferences`，旧配置文件 `nutritrack_session`，白名单迁移。状态含 Loading/LoggedOut/LoggedIn/ReadError；无独立 isLoggedIn 或问卷完成布尔持久化。

旧问卷文件为 `user_<userId>_prefs`；业务键为九种 food 类别对应的 `food_<category>`、`selected_persona`、`biggest_meal_time`、`sleep_time`、`wake_up_time`，以迁移类的 BUSINESS_KEYS 为准。FoodIntake.submissionDate 是 epoch milliseconds，按设备本地时区做日期区间；计划有效期存 epoch day，模型 JSON 使用 ISO 本地日期。

## 4. Agent 固定两阶段合同

`NutriCoachScreen → NutriCoachViewModel → NutritionPlanningAgent → FirebaseAgentModelGateway`，后续由本地 Dispatcher、Parser、Repository 执行边界。

1. 用户需求先规范化/限长并检查明显身份/凭据特征；ViewModel 使用可信 Session 用户，当前 UI 默认请求七天计划。
2. 第一阶段独立模型工厂声明只读工具，不设置最终 JSON MIME/schema；通过新 Chat 的一次 sendMessage 获取全部所需函数调用，随后丢弃 Chat。
3. Registry 拒绝未知工具、额外字段、模型提供的 userId、非法日期/查询。去重后按顺序执行，并持久化成功/失败审计。
4. Evidence builder 将结果最小化、限长、聚合，使用 `e1..e4` 临时引用，不把内部 Run/ToolCall ID、用户 ID 或供应商 call id 送入模型。营养数值仍会随必要 evidence 发往云模型，不是全部离线或“健康数据完全不外发”。
5. 第二阶段用另一工厂直接 generateContent：无 tools/toolConfig，JSON MIME + v1 responseSchema，只接收请求信息与最小化 evidence。
6. Parser 校验精确字段集、schemaVersion、日期、枚举与单位配对、有限数值范围、文本/数组长度、本 Run 成功 evidence、PII/医疗规则；随后把别名映射回审计 ID。
7. 同一 Room 事务保存一份 DRAFT 并把 Run 更新 SUCCEEDED。失败保留 trace，不造草稿；同用户活动 Run 防重。
8. 页面/进程恢复读 Room，不自动请求模型。遗留 RUNNING 以 PROCESS_INTERRUPTED 收敛；用户 Retry 开新 Run，不覆盖旧审计。

四个 wire 工具名及职责：

| 工具 | 输入/数据范围 |
| --- | --- |
| `get_nutrition_profile` | 无模型身份参数；当前用户最小营养画像 |
| `get_intake_history` | startDate/endDate，最多 31 天（含首尾），聚合实际摄入 |
| `calculate_nutrition_trend` | 同日期边界，本地确定性趋势计算 |
| `search_fruit_nutrition` | 有界水果名称，复用 FruitNutritionGateway |

当前调度要求至少一个有效工具、最多四个提议调用；不强制两个工具或固定画像+历史组合。空历史是缺乏证据，不能据此推断零摄入/营养缺乏。

当前代码配置：Agent `gemini-3.5-flash-lite`；普通 AI `gemini-3.8-flash`，均在 AiModelConfig。温度 0.1，输出预算 2048 token，单请求 60 秒，总 deadline 128 秒；两个阶段各至多重试一次，固定可取消等待 7 秒，成功工具与 evidence 不重复。`MAX_MODEL_REQUESTS=2` 表示两个基础阶段/总预算计算，不代表重试后绝不超过两次调用。

仅 SDK 超时/网络、明确公开 5xx 或有限瞬时语义可触发重试；普通 SERVER 标签不能作为依据。认证、配置、配额、解析、领域错误不应通过无界重试绕过；实际分类细节以 Gateway 为准。SDK 参数仍有历史 turn-limit 常量 6，但生产无 FunctionResponsePart/后续工具循环，不等于六轮 ReAct。

输出字段为 `schemaVersion/summary/validFrom/validTo/recommendations/cautions/disclaimer/evidenceToolCallIds`；recommendation 为 `category/title/action/targetValue/targetUnit`。JSON schema 不替代本地验证；也不能宣称这些规则已完成医学有效性验证。未实现 RAG/向量检索、多 Agent、自训练或自动多模型路由；当前未采集 usageMetadata，不能从输出预算推出实际 token 或精确费用。

## 5. 确认、生命周期与未来同步

- Agent 只能新建 DRAFT；`RoomNutritionPlanActionDispatcher` 通过 `confirm:<planId>`、唯一索引及条件更新转为 CONFIRMED，重复确认返回已有结果。放弃草稿或停止已确认计划转为 ABANDONED，保留 Run/ToolCall。
- 确认只保存状态、确认时间与 actionId，不修改 Patient、QuestionnaireProfile、FoodIntake 或 UserAccount，不自动把建议写成摄入。
- 状态支持 DRAFT、CONFIRMED、ACTIVE、COMPLETED、ABANDONED；**ACTIVE 是预留状态**，当前无明确用户激活路径，不能把枚举存在写成已上线功能。
- `WorkManagerNutritionPlanLifecycleScheduler` 使用 `nutrition-plan-complete:<planId>` + KEEP，按结束日次日的本地零点安排可延迟工作；`NutritionPlanCompletionWorker` 重新核对 Session、计划归属、状态、日期并幂等更新 COMPLETED。无网络约束需求，不调用 AI/Firebase/通知/AlarmManager。
- 当前日期判断存在调度器与 processor 的边界差异；退出/换用户时 Worker NoOp 后的补调度覆盖也有待验证，详见当前状态 R3/R4。不将历史测试通过解释成无边界缺陷。
- [SYNC_CONTRACT.md](docs/SYNC_CONTRACT.md) 仅为未来 `nutrition-sync/v1`：认证主体隔离、稳定 ID/actionId、时间格式、不可逆状态、版本冲突、离线上传确认和审计最小化；本地 ABANDONED 映射未来 DISCARDED。无服务端 API、outbox、同步表、上传队列或多设备同步实现。

## 6. 重要决策的演变

| 旧状态/方案 | 后续决策与原因 | 可追溯依据 |
| --- | --- | --- |
| CSV/SharedPreferences/Room 边界分散 | Room 收拢业务，CSV 仅种子，DataStore 两键；提高一致性及可恢复性 | P0、数据层、5–9 Schema |
| KAPT + Kotlin 2.0 回退 | 独立迁 KSP，先冻结可信 v5 Schema，避免混入结构变更 | `bc28dfb`、`cd079d3` |
| UI 直接访问数据 | 主流程迁 ViewModel/StateFlow；保留手工注入，避免无用途层；剩余例外不隐瞒 | `3cf141d`、`7ecc77a`、`401bd40` |
| 字符串切换 Home 页面 | NavHost + 稳定 AppRoute、单顶和恢复，不全面改单 Activity | `2ead4ad` |
| 旧 generativeai SDK/构建期 Gemini key | 独立 Gateway → Firebase AI Logic + 变体 App Check，删除旧 SDK/构建注入 | `7c36d13`、`735806f`、`4005555` |
| 原生多轮 FunctionResponse 回传 | 持续在 SDK/后端边界失败且缺可诊断状态；停止该方向，改独立两阶段 | `2ccaa18` 后仍失败；`3ade688`、`0da886d` |
| 历史要求至少两个工具 | 用户后来明确按任务选工具；当前只要求非空、有界、合法 | 主报告第 3.8 节、当前 Agent/Registry |
| 26 秒请求/60 秒总时限，无重试 | 60 秒/128 秒；后加严格阶段内单次重试，不重做成功工具/草稿 | `06fad94`、`c916642` |
| Agent 使用普通 AI 模型 | 单候选真实 C 成功后采用 Agent 专用 Flash Lite，普通 AI 保持原模型 | `e4d2d01`、验收记录 2026-10-02 |
| P2-1 未验收、旧阶段曾禁止 P2-2 | 后续授权 P2-2 已落地，P2-1 最终真实成功；旧停止条件不再描述当前进度 | `0f872ea`、`5c9733e`、`e4d2d01` |
| 数据库仅 DRAFT | v11/v12 增加用户动作与离线状态生命周期；Agent 生成仍仅 DRAFT | ActionDispatcher/生命周期代码 |
| 默认 Material 动态配色、辅助查询优先 | 品牌亮暗主题、dynamicColor 默认 false、打包 Geist、Agent 优先布局 | Fork 3、`3b7db21`；保留显式动态色能力 |
| 不输出完整 prompt/response | Fork 2 增加仅 Debug 的白名单结构视图，未撤销保密规则；Release 不求值 payload | 工作区日志增量、AGENT_DEBUG_TRACE.md |

依赖升级曾尝试 Room 2.8.4（与当时旧 AI 序列化 ABI 冲突）和 Lifecycle 2.10.0（牵动 Compose），随后回退；这是历史兼容性记录，不是永久禁止升级。降低 minSdk 是待有明确兼容需求时决定的产品事项，不能为了消除告警顺手修改。

早期主报告缺失的一些提交已从当前 Git 历史补证：基线 `e112ee4`、CSV `1b4b291`、Auth `3cf141d`、Questionnaire `7ecc77a`、P2-2A `0f872ea`。提交存在不等于恢复了当时全部执行日志。

## 7. 历史 Bug、失败尝试与保留结论

| 问题/失败 | 处理与必须保留的限制 |
| --- | --- |
| CSV 失败仍标完成、非法值默认为 0 | 严格 parser + 种子元数据同事务；回滚、重试、幂等 |
| 问卷迁移提交后删旧键前崩溃 | Room 迁移版本守卫；重试清理，不覆盖已保存画像 |
| 登录初始化异步后误导航/重复事件 | Loading/ReadError 状态、有效 Room 问卷推导、可消费导航事件、受限 route |
| 明文账户密码与不安全备份/HTTP | v9 哈希迁移；Manifest allowBackup=false、usesCleartextTraffic=false；旧 Schema 的明文列是历史证据 |
| FunctionResponse 后失败 | 检查同 Chat、call id、name、JsonObject、批量顺序与 role 后仍无根因；改两阶段，不声称证明 SDK 或某字段必然有错 |
| 两阶段请求配置混淆 | 工具工厂与 JSON 工厂分离；工具阶段无最终 schema，生成阶段无 tools |
| ServerException 分类过粗/测试泄漏原异常 | 固定阶段、异常类别、有限 message 语义及真实可用状态；不记录原文/堆栈、不猜 HTTP 状态，项目标签不是服务端 code |
| 否定免责声明误判医疗风险 | `960537a` 调整否定作用域并配正反例/字段诊断；无法证明所有历史 FINAL_SAFETY_INVALID 都源于这一误判 |
| 超时、配额、App Check 历史阻塞 | 区分 SDK/Agent/UI 等待；后有成功凭证刷新和真实 C。旧配额耗尽、控制台条款错误均不直接代表今日故障 |
| A/B 成功而 C 失败 | A=最小 JSON；B=生产 schema+合成 evidence+parser；C=真实生产 Agent/UI/Room。只有完整 C 才覆盖整体路径 |
| IDE unresolved Compose/lifecycle 引用 | 曾讨论 JDK 21→17，但根因未保留；查依赖/Sync/实际构建，不把换 JDK 当通用修复 |
| 验收测试返回非 Unit | `6ea8fa5` 修复 JUnit 返回契约；不能把验证器断言失败伪装成验收通过 |
| UI 卡片空白、图标/选择器问题 | Fork 3 移除滚动卡片垂直 weight、用现有箭头图标、修正歧义文本/route tag、lint 命名及字体授权空格 |
| 日志测试使用概念工具名 | Fork 2 修正样本为真实 snake_case wire 名，未改协议/删断言 |
| ADB/窗口绑定与测试 APK 状态 | 历史经授权改用绝对 SDK 路径 ADB 演示；源代码夹具恢复后，设备测试 APK 可能仍是临时版本，不得盲目重跑准备流程 |

Debug trace 六阶段为 `PROMPT_01 → FUNCTION_CALLS_02 → TOOL_RESULT_03 → EVIDENCE_04 → RAW_PLAN_05 → VALIDATION_06`。先脱敏再分段；未知字段、自由文本、数值、日期、ID 不原样输出。RAW_PLAN 是校验前的脱敏结构，不能重放；PASSED 不等于已保存，SAVED_DRAFT 不等于已采用；失败不补造后续阶段。

## 8. 历史验收与保护边界

- 仓库记录 2026-10-02 单候选真实 C：生产 Gateway/Agent/Parser/VM/Compose，合成会话、隔离磁盘 Room；模型选择/生成各一次，3,254/2,053 ms，2 条工具均成功、1 个 SUCCEEDED Run、1 份 DRAFT。页面重建及跨进程恢复同草稿，新增模型请求 0。水果用替身，scheduler 为 no-op，未确认采用。
- 主报告另记随后正常安装 App 的可见登录与生成演示：选择/生成 3,385/1,962 ms、Run 5,415 ms、两个本地工具、单草稿、重启零新请求。该次无新增提交；截图/设备结果未由本次读取，保留为交接报告中的历史验证，不伪称本次独立复验。
- “业务未变化”以合成账户准备完成后为基线；准备本身新增数据，正常登录更新时间也不应误算 Agent 副作用。
- 受保护历史设备：`Medium_Phone_API_35 / emulator-5554`；UI 使用独立 `Pixel_9_API_35 / emulator-5556`。端口可变，未来先核对身份。本次未检查设备当前状态。
- 受保护 stash：`cbcd4cb6ee7db6bc4fc69cc7934c9094f83abbcd`；当前仍存在。旧删除 NewScreen 的授权不构成操作该 stash 的权限。
- RealAgentAcceptanceSetupTest 源码已恢复原夹具：合成用户 `988_401`、`Agent Demo`，指纹文件 `real_agent_business_fingerprint.txt`、`real_agent_ui_acceptance_status.txt`、`real_agent_draft_fingerprint.bin`。后续演示设备可能保留 `visible_demo_…` 版本；不存密码、不覆盖已有审计/草稿。
- 真实调用预算逐任务明确，历史 16/16 是用户授权上限，不是服务商日配额；不能计算完整历史计费总量，也不能沿用剩余额度。

## 9. 冲突、未确认信息与后续入口

| 编号 | 表述差异/冲突 | 处理 |
| --- | --- | --- |
| C1 | 主报告 HEAD=e4d2d01/干净；Fork 2 UI 未提交；Fork 3 UI 已提交 | 属不同时间快照。Android 核对基线=3b7db21；后续记忆文档单独提交，日志仍未提交，无需执行分支合并 |
| C2 | REFACTOR_PLAN 开头称非 Git/v5/旧 SDK；验收文档中段仍有“最新阻塞” | 原文保留；按日期及源码读，2026-10-02 成功和后续 UI/日志是较新状态 |
| C3 | 主报告“保留 LoginActivity” | 当前仅保留同名文件中的 LoginScreen；实际由 WelcomeActivity 承载，Manifest 无 LoginActivity |
| C4 | “UI→ViewModel 全部完成/安全认证完成”的宽泛解读 | 历史上 Settings/Clinician 直接取 Repository，固定口令未被账户哈希迁移解决；2026-10-22 已移除 Clinician 数据访问/口令，Settings 仍待处理 |
| C5 | 生命周期“结束日期后”与处理器当天允许完成 | 当前源码语义冲突，未裁决正确业务边界；见 CURRENT_STATUS R3 |
| C6 | 真实 Agent 曾成功，但两 Fork 均未重新真实验收 | 不矛盾：Fork 2/3 真实请求均为 0，不能扩展为新 UI+新日志组合已完成真实验收 |

没有发现需自动选择一方的 Git 未合并条目；也不能凭这次静态审计宣布不存在其他业务冲突。未确认项：聊天精确分叉点、未保留的失败响应/根因、模型当天可用性/配额、Console 当前配置、设备安装及测试 APK 版本、Release 真机、所有外部工具与长期稳定性。

优先续办日志实际运行后的学习讲解、当前状态列出的风险验证，再按用户新任务推进；同步、精确提醒、RAG、多模型等不能自动变为已授权路线图。重要任务更新当前状态；只有稳定决策进入本文件，原始交接报告不重写。
