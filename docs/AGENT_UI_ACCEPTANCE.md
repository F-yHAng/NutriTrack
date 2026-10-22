# NutriCoach 真实 UI 验收记录

最新验收：2026-10-02（Asia/Kuala_Lumpur）。`gemini-3.5-flash-lite` 下完成一次真实两阶段 Agent、Compose 页面与跨进程 Room 恢复验收；P2-1 当前路径已完成。以下旧失败记录保留历史语境，不代表当前状态。P2-2 未修改。

## 2026-10-02：唯一候选模型的真实 C 与跨进程恢复（通过）

- 起点：`c916642c3c78822f4b318496514ce3c22a0b2e0f`。设备明确核实为既有 `Medium_Phone_API_35`、`emulator-5554`、API 35；正常启动原 AVD，使用保留数据的 `adb install -r`，未卸载、清应用数据或重新登记 Debug token。
- 项目 `[LOCAL_CONFIGURATION_REMOVED]`、目标包 `com.example.nutritrack`、默认 FirebaseApp、Google AI backend、实际解析 `firebase-ai:17.16.0` 均不变。Firebase 官方 [模型列表](https://firebase.google.com/docs/ai-logic/models) 与 [Function Calling](https://firebase.google.com/docs/ai-logic/function-calling) 文档列出候选支持；当前源码未显式配置 thinking。实验仅在既有 `AgentModelSettings` 注入点将两个阶段模型名覆盖为 `gemini-3.5-flash-lite`；温度 0.1、maxOutputTokens 2048、60 秒单请求/128 秒总时限、tools/toolConfig、提示、生产 v1 JSON schema、校验和重试规则未改。工厂字段等价测试确认仅 modelName 差异。
- 仅显式执行 C：从实际 `NutriCoachScreen` 输入合成需求、点击生成，经过生产 `NutriCoachViewModel → NutritionPlanningAgent → FirebaseAgentModelGateway → Dispatcher → AgentRepository`。未执行 A/B、独立探针或完整 androidTest 套件。

| 应用层调用 | 模型 | 阶段 | 开始时间（UTC+08） | 耗时 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | gemini-3.5-flash-lite | TOOL_SELECTION | 2026-10-02 19:16:28 | 3,254 ms | 返回合法调用，本地只读执行与审计成功 |
| 2 | gemini-3.5-flash-lite | FINAL_GENERATION | 2026-10-02 19:16:31 | 2,053 ms | 严格 schema、日期/数值、evidence 与医疗安全校验通过 |

- 本轮 **2/4** 次可观测应用层生成调用；两个阶段均首次通过、没有重试。没有测量 SDK 内部传输次数或服务端计费次数。没有异常，不声称已观察 HTTP 状态。
- 结果：隔离数据库内 `Run=SUCCEEDED`、`ToolCall=2/2 SUCCEEDED`、`DRAFT=1`；顺序及本次 Run 关联断言通过。生成前后 Patient、UserAccount、QuestionnaireProfile、FoodIntake 快照相同；未确认计划、未调度后台任务。
- UI：生产 NutriCoach 页面进入 DraftReady，显示 summary、cautions、disclaimer 和有序脱敏 audit。销毁页面/ViewModel、关闭并重开磁盘 Room 后，恢复相同完整草稿；模型计数仍为 1+1。
- 进程恢复：结束目标进程后，使用独立显式恢复入口启动新目标应用进程（4612→4732）。恢复同一磁盘草稿；草稿/业务数据 SHA-256 指纹均相同，UI 仍显示 DraftReady，DRAFT 仍只有一条。恢复包装器在进入 SDK 前拒绝任何生成调用，实测恢复调用数 0。
- 范围限制：合成会话由测试注入，Room 使用独立 `real-agent-smoke.db`，不混入正常账户数据库；真实 Firebase、生产调度/校验/持久化/页面均执行。水果边界使用测试替身，本轮不证明水果外部网络已验收；普通激励文案不发请求，生命周期 scheduler 为测试 no-op。Debug Provider 成功不代表 Release Play Integrity 真机验收；单次成功不证明长期稳定性，也不能归因原模型服务故障。
- 成功后将 `AiModelConfig.AGENT_MODEL_NAME` 集中设为候选，`NutritionPlanningAgentConfig` 使用该常量；Application 的正常 Agent 构造仍复用同一 Gateway/默认 settings。普通 AI 的 `DEFAULT_MODEL_NAME` 保持 `gemini-3.8-flash`。本轮不再为默认配置落地额外发请求。
- 默认配置落地后的 Debug APK 已保留数据更新至原设备；又以**无模型覆盖参数**的恢复入口检查新进程（4921），同一草稿/业务指纹、UI 和零模型调用再次通过。合计真实生成 C 1 次、零生成跨进程恢复检查 2 次，后者不消耗模型预算。
- 离线验证：最后一轮相关 JVM 17 项通过（模型配置 12、有限重试 5）；API 35 开关测试 2 项通过，无开关 C 确认跳过（不计成功）；真实 C 和零请求恢复各 1 项通过。`assembleDebug`、`compileDebugAndroidTestKotlin`、AndroidTest APK 和 `lintDebug` 通过，未运行破坏安装的全量设备任务。Room v12/历史 Schema、Migration、DataStore、App Check、AI Monitoring 与 P2-2 未修改；stash OID 保持 `cbcd4cb6ee7db6bc4fc69cc7934c9094f83abbcd`。

### 本次准确执行入口（仅显式授权时运行）

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell am instrument -w -r -e class 'com.example.nutritrack.RealFirebaseAgentSmokeTest#oneRealFunctionCallingRunPersistsAndDisplaysDraft' -e realFirebaseAgent true -e realFirebaseAgentModelName gemini-3.5-flash-lite com.example.nutritrack.test/androidx.test.runner.AndroidJUnitRunner
# 仅当上一步成功；结束进程，不清数据；恢复步骤禁止模型调用。
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell am force-stop com.example.nutritrack
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell am instrument -w -r -e class 'com.example.nutritrack.RealFirebaseAgentSmokeTest#restoreRealDraftWithoutModelRequests' -e realFirebaseAgentRecovery true -e realFirebaseAgentModelName gemini-3.5-flash-lite com.example.nutritrack.test/androidx.test.runner.AndroidJUnitRunner
```

两个入口默认关闭且要求精确选择一个方法；生成入口只重新创建其自己的隔离 fixture，保留本轮结果供恢复入口读取，绝不删除目标业务数据库或 App Check 数据。正常 App 使用方式：登录并完成问卷 → NutriCoach → 输入不含身份信息的需求 → `Generate Nutrition Plan Draft` → 查看草稿、依据和免责声明；本次不点击采用/执行。

## 2026-10-01：有限瞬时重试后的真实 C 复验（未完成 Agent 验收）

本轮仍在原 API 35 验收模拟器、原 Debug App Check 凭证配置、`gemini-3.8-flash`、
Google AI backend 与 `firebase-ai:17.16.0` 下，只运行了默认关闭的
`RealFirebaseAgentSmokeTest#oneRealFunctionCallingRunPersistsAndDisplaysDraft`。该测试使用合成账户、
隔离数据库、生产 `NutriCoachViewModel`、`NutritionPlanningAgent`、`FirebaseAgentModelGateway` 与 Compose
页面；没有卸载、清数据或读取 token。本表的调用数是可观测的应用层生成调用，不代表 SDK 可能发生的
内部传输次数。

| 尝试 | 阶段 | 应用层调用 | 耗时 | 脱敏诊断与处理 |
| ---: | --- | ---: | ---: | --- |
| 1 | TOOL_SELECTION | 1 | 60,154 ms | `RequestTimeoutException` → `TIMEOUT`；无公开 HTTP status/SDK code；按明确 SDK 超时类型执行一次阶段内重试 |
| 2 | TOOL_SELECTION | 1 | 7,000 ms | `ServerException` → `SERVER`；`messageSemantic=EXPLICIT_RETRY_REQUESTED`；无公开 HTTP status/SDK code；已达到此阶段两次尝试上限，不发起第三次 |

- 生产路径现在只在单次请求具有明确瞬时依据时重试：SDK `RequestTimeoutException`/网络类型、公开的
  5xx 状态，或公开 message 的有限“明确请求重试／临时不可用／过载”语义。普通 `ServerException`、
  权限、配置、配额、解析与领域校验错误均不重试；等待固定为 7 秒、可取消、受既有 128 秒 Agent
  deadline 约束，且不会重建 Run、重复已成功工具、重复 evidence 或重复保存 DRAFT。
- 本次 Run 终态为 `FAILED / TOOL_SELECTION / MODEL_SERVER`；`ToolCall=0`、`DRAFT=0`、业务数据指纹
  不变。未进入本地工具、FINAL_GENERATION、成功 UI 展示或 Room 草稿恢复，因此它们本次均**未验收**。
- 这次失败不能单独证明 Gateway 有缺陷，也不能把所有 `ServerException` 视为可重试。第二次的有限
  公开语义只支持“该调用要求重试”的标签；SDK 没有公开 HTTP status、结构化 error code 或服务端原因，
  故 App Check、权限、配额、模型服务和请求配置仍无法据此定性。
- Fake/隔离回归覆盖：瞬时失败后成功、第二次失败终止、不可重试错误只调用一次、取消/deadline 不再
  请求、最终生成重试不重复工具或草稿。真实 C 保持失败，P2-1/P2-1B/P2-1B2/P2-1B2C2 均未勾选。

## 2026-10-01：有分支的 A/B/C 模型接入诊断（未完成 Agent 验收）

本轮在同一既有 API 35 验收模拟器、同一 Debug App Check 配置、`gemini-3.8-flash`、
Google AI backend 与 `firebase-ai:17.16.0` 上运行。没有卸载或清除目标 App 数据；所有输入均为
合成数据。本表的调用数是**可观测的应用层生成调用**，不代表 SDK 可能发生的内部传输次数。

| 序号 | 用途 | 应用层调用 | 结果与耗时 | 可安全保留的证据 |
| ---: | --- | ---: | --- | --- |
| 1 | A：最小 JSON schema | 1 | 失败，8,353 ms | `ServerException`；`SDK_PUBLIC_MESSAGE`；`messageHint=SDK_MESSAGE_RETRY_REQUESTED`；HTTP status / SDK code 均为 `NOT_EXPOSED_BY_SDK` |
| 2 | A：相同配置的唯一重试 | 1 | 通过，8,916 ms | 非空 JSON 且符合最小 schema |
| 3 | B：原生产 Gateway 路径 | 1 | 失败，5,818 ms | Gateway 将异常安全映射为 `agentError=SERVER`，未向测试泄漏 SDK message |
| 4 | B：同一生产计划工厂的 SDK 诊断路径 | 1 | 通过，5,341 ms | 使用实际 `FirebasePlanGenerationModelFactory`、生产 JSON schema、合成 evidence、真实 parser 与日期／数值／evidence 校验；不读写业务数据库 |
| 5 | C：完整隔离 Agent/UI 流程 | 1 | 失败，14.69 s | `TOOL_SELECTION / ServerException / MODEL_SERVER`；`ToolCall=0`、`DRAFT=0`、业务数据指纹不变；未进入结构化生成 |

- 原 `TEMPORARY_RETRYABLE` 规则过去会把任意 `temporary` 归为可重试；本轮测试侧已改为只有公开
  message 明确请求重试或指明 temporary-unavailable 才给出该结论。历史原文未保留；本轮第 1 次
  A 的标签来自当前 SDK 公开 message 的“请求重试”语义，而非旧的宽泛关键词。测试只输出有限标签，
  不保存或输出原 message、凭据、请求／响应内容或健康数据；公开 HTTP status 与结构化 SDK code 仍未提供。
- B 的第 4 次通过证明：在本次设备/项目/模型条件下，独立的生产 FINAL_GENERATION 请求构造、严格
  schema、合成 evidence 和本地 parser/领域校验可以完成。它**不能**证明完整 Agent 已通过，也不能
  排除瞬时服务波动；第 3 次 Gateway 失败仅说明该时刻 Gateway 捕获到了 `ServerException`。
- C 的隔离烟测使用生产调度器、只读工具、parser、Room 持久化与 Compose 页面；本次在工具选择前就被
  `ServerException` 终止，故没有产生审计 ToolCall、草稿、UI DraftReady 或重启恢复结果。安全日志只保留
  `phase=TOOL_SELECTION`、模型、backend、SDK、异常类型与空 cause；没有公开 HTTP status 或结构化 error code。
- 本轮共使用 5 次应用层模型生成调用，未设置应用层自动重试、备用模型或 provider 切换；余下调用额度
  未使用。未修改生产 Agent、模型、Firebase/App Check 配置、Room、DataStore 或 P2-2。
- **当前结论：**不存在已证实的本地 schema/parser/evidence 缺陷；也没有证据将 C 的工具选择失败定性为
  App Check、配额、权限或服务端故障。P2-1/P2-1B/P2-1B2 保持未勾选。下一次网络实验应先以相同生产
  工具选择工厂的默认关闭、单次 SDK 诊断探针收集安全 message 标签，再决定是否重新运行完整 C，而不是
  无依据重复完整 Agent。

## 2026-10-01：A/B/C 顺序真实验收（在 A 停止）

本轮仅对 A 运行了 `FinalGenerationReproductionTest#minimalStructuredOutput_onlineProbeRequiresItsOwnExplicitOptIn`。
它使用既有目标应用 Firebase 初始化、默认 FirebaseApp、Google AI backend、`gemini-3.8-flash`、
`firebase-ai:17.16.0`、60 秒单次请求时限和无 tools 的最小 JSON schema；输入完全合成。

| 阶段 | 是否实际执行 | 应用层模型生成调用 | 结果 |
| --- | --- | ---: | --- |
| A：最小结构化请求 | 是 | 1 | 失败；未收到内容，未进入 JSON 解析 |
| B：生产计划格式请求 | 否 | 0 | A 未完整通过，未执行 |
| C：完整 Agent/UI/Room 恢复 | 否 | 0 | A 未完整通过，未执行 |

- A 的安全诊断为：`stage=MODEL_GENERATION`、`exception=ServerException`、`causes=[]`、
  `messageSource=SDK_PUBLIC_MESSAGE`、`messageReason=TEMPORARY_RETRYABLE`、
  `httpStatus=NOT_EXPOSED_BY_SDK`、`sdkErrorCode=NOT_EXPOSED_BY_SDK`，模型调用耗时 15,163 ms。
  原始 message、请求/响应、token 和用户数据均未读取或记录。
- 这不是 `RequestTimeoutException`，也没有 SDK 可公开的 HTTP 状态或结构化服务端原因；因此当前
  不能据此断言为 App Check、配额、模型配置或服务端故障。此前同一目标应用上下文的 App Check
  `getAppCheckToken(true)` 已成功，但该结果也不替代模型端点验收。
- 按 A → B → C 的顺序门槛、每阶段不自动重试的约束，以及本次没有可证实的本地构造缺陷，未消耗
  剩余模型调用额度执行 B/C 或重复 A。未改动生产代码、Firebase/App Check 配置、Room 或 P2-2。
- P2-1、P2-1B、P2-1B2 继续保持未勾选；下一次联网实验应先在取得可区分该 `ServerException` 的
  外部状态或新的服务端证据后，重新执行单次 A，而不是跳过 A 直接运行 Agent。

## 2026-09-30：当前验收设备的 App Check 强制刷新

在既有 API 35 验收模拟器 `emulator-5554` 上，仅执行默认关闭的
`AppCheckCredentialProbeTest#forceRefresh_targetAppCredentialExchangeRequiresExplicitOptIn`。
测试使用 Instrumentation 的 **target application** `NutrackApplication`，并通过该应用的
默认 `FirebaseApp` 调用公开 API `FirebaseAppCheck.getInstance(firebaseApp).getAppCheckToken(true)`。

- 目标 applicationId 与 `FirebaseApp` application context 均为
  `com.example.nutritrack`，Firebase project 为 `[LOCAL_CONFIGURATION_REMOVED]`；
  默认 FirebaseApp 名称为 `[DEFAULT]`。
- Firebase AI 网关使用 `Firebase.ai(backend = GOOGLE_AI)` 的默认 FirebaseApp；App Check 探针
  显式取得同一个默认 FirebaseApp。因此两者使用相同 FirebaseApp，而不是 androidTest 包自身的配置。
- 强制刷新成功：JUnit `OK (1 test)`。测试只确认返回的 `AppCheckToken` 对象存在，**不读取、打印、
  保存或提交** token 内容，也不采集 Logcat。
- 本次真实 Gemini/Firebase AI 模型生成调用数为 **0**；未创建 GenerativeModel、未运行 Agent、未写入
  业务数据、未清除应用数据或修改 Firebase/App Check 控制台。
- 此结果仅说明当前设备可取得 App Check 凭证；它不能单独证明受强制执行保护的 Firebase AI 模型请求
  一定会成功，P2-1/P2-1B/P2-1B2 状态不变。

## 2026-09-30：A 的带诊断单次复验（App Check 阻塞）

在不改变模型、provider、Prompt、schema、超时或 Firebase 配置的前提下，A 的测试侧增加了
最小诊断包装：只读取合成请求产生的公开 SDK exception message/cause，用固定分类写入测试日志和
JUnit 失败摘要；不保存或输出原始 message、响应体、请求头、token 或堆栈。人工合成的 permission
错误回归和“原始异常不进入 JUnit”回归均通过。

| 用例 | 是否执行 | 应用层生成调用 | 结果 | 可安全保留的诊断 |
| --- | --- | ---: | --- | --- |
| A：最小 JSON schema（带诊断） | 是；JUnit `tests=1, failures=1, skipped=0` | 1 | 失败；未收到可供 JSON 解析的内容 | `stage=MODEL_GENERATION`；`ServerException`；模型调用开始于 2026-09-30 17:27:05 +08:00、耗时 1,536 ms；`messageSource=SDK_PUBLIC_MESSAGE`；`messageReason=APP_CHECK`；HTTP status / SDK error code 为 `NOT_EXPOSED_BY_SDK` |
| B：生产 FINAL_GENERATION 合同 | 否 | 0 | 未执行 | 已取得 App Check 相关的明确 message 分类，按分支 C 停止额外生成调用 |

- 该 `APP_CHECK` 是从 Firebase AI SDK 公开 message 的固定语义分类得出，不是项目自定义的
  HTTP status，也不是从控制台状态、配额或服务端响应体推断。`firebase-ai:17.16.0` 的
  `ServerException` 公开类型没有 status/code 字段；SDK 内部非 200 转换后只向调用者交出 message/cause。
- 本地 Debug 配置没有发现缺失：debug source set 安装 `DebugAppCheckProviderFactory`，且
  `NutrackApplication.onCreate` 先调用 `FirebaseAppCheckInitializer.initialize`。这不能证明当前
  模拟器产生的 token 已在正确 Firebase 项目及 Android app 登记，只能排除已检查到的本地初始化缺失。
- 本轮确认并修复的是**测试诊断缺陷**，不是生产请求缺陷：此前 A 让原始 `ServerException` 直接进入
  JUnit 报告；现在 `runOnlineProbeStage` 仅保留异常/有限 cause 类型、message 是否存在、来源和
  分类代码，且不附加原始 Throwable。生产日志和业务数据边界未变化。
- 下一步只需在 Firebase Console 对项目 `[LOCAL_CONFIGURATION_REMOVED]` 的 **App Check → Apps → Android app
  `com.example.nutritrack`** 只读核对：当前 `Medium_Phone_API_35` 的 Debug provider
  token 是否已登记在这个 app/项目中，并核对 Firebase AI Logic 的 App Check enforcement 状态。不要把
  token 发给应用或提交到仓库；完成核对前不应重复客户端生成请求。
- P2-1、P2-1B、P2-1B2 继续保持未勾选，P2-2 状态未改。本轮未执行完整 Agent、明文对照或 B。

## 2026-09-30：FINAL_GENERATION A/B 在线最小复现实验

本次基于 `e21a7cf` 的默认关闭复现入口，仅在单一既有 API 35 模拟器
`emulator-5554` 上执行 **A**；设备保留原有数据，未清除应用数据、未重置 AVD、未读取
或记录 Debug App Check token。当前配置为 Firebase project `[LOCAL_CONFIGURATION_REMOVED]`、Google AI
backend、`gemini-3.8-flash`、`firebase-ai:17.16.0`、单次请求 60 秒。网络连通性探测成功，
但这不代表模型服务一定可用。

| 用例 | 指定方法 | 是否执行 | 应用层生成调用 | 结果 | 可安全保留的诊断 |
| --- | --- | --- | ---: | --- | --- |
| A：最小 JSON schema | `minimalStructuredOutput_onlineProbeRequiresItsOwnExplicitOptIn` | 是；JUnit `tests=1, failures=1, skipped=0` | 1 | 失败 | `ServerException`；用例总耗时 38.691 秒；HTTP status / 结构化错误码为 `NOT_EXPOSED_BY_SDK` |
| B：生产 FINAL_GENERATION 合同 | `productionStructuredPlan_onlineProbeRequiresItsOwnExplicitOptIn` | 否 | 0 | 未执行 | A 未完整通过，按一次调用上限停止 |

- A 的测试代码只调用一次 `generateContent`，没有应用层自动重试、备用模型或 provider 切换；
  SDK 内部传输尝试次数不是本项目可观测的计费计数。本表的“1”仅指应用层生成调用。
- A 在直接 `generateContent` 处收到 SDK `ServerException`，不是 skipped、空响应或普通文本断言失败。
  该最小请求不携带 tools、toolConfig、Chat/history 或 function response，也不读取业务数据库或写入
  `NutritionPlan`。因此本次只能说明“当前环境下最小结构化请求未通过”，不能据此归因为 App Check、
  配额、Firebase 服务端或生产 schema。
- 未读取异常 message、响应体、请求体、认证信息或全量 Logcat。Firebase AI 17.16.0 对该异常不公开
  HTTP status 或结构化服务端错误码；不能由 `ServerException` 推断 400、403、429 或 500。
- P2-1、P2-1B、P2-1B2 继续保持未勾选，P2-2 状态未改。本轮不执行 B、不执行完整 Agent、
  不修改模型、provider、超时、Prompt、schema、App Check 或 Firebase 配置。

## 2026-09-30：FINAL_GENERATION 定向离线诊断与最小复现准备

**P2-1 的准确状态：**真实 `FINAL_GENERATION` 验收失败，SDK 分类为 `MODEL_SERVER`；客户端请求配置、SDK 处理及服务端原因仍需通过证据区分。本次没有发送 Firebase/Gemini 请求（0 次），也没有改动模型、provider、App Check、超时、Prompt、schema 语义、工具协议或 Room。

### 已确认的调用链与请求合同

- 第二阶段由 `NutritionPlanningAgent.finishWithPlan` 调用 `AgentModelGateway.generateStructuredPlan`（`app/src/main/java/com/example/nutritrack/agent/NutritionPlanningAgent.kt:324-397`）；只有网关返回 `Output` 后才进入 parser，随后才可能在同一 Repository 事务中保存 DRAFT。因此历史 `FINAL_GENERATION / MODEL_SERVER` 不等同于 parser、安全/evidence 或 Room 失败。
- `FirebaseAgentModelGateway.generateStructuredPlan` 创建独立 `FirebasePlanGenerationModelFactory` 模型并直接 `generateContent(evidenceBundle.canonicalJson)`（`.../data/FirebaseAgentModelGateway.kt:97-128`）；它不复用工具选择 Chat/history，也不附带 FunctionResponse 内容。
- 当前实际配置来自 `AiModelConfig.DEFAULT_MODEL_NAME=gemini-3.8-flash`（`.../data/AiGateway.kt:30-32`）及 `NutritionPlanningAgentConfig`（温度 0.1、最大输出 2048、单请求 60 秒、总时限 128 秒；`.../agent/AgentModelGateway.kt:132-145`）。计划模型明确设置 `responseMimeType=application/json`、实际 `finalPlanSchema(...)`、`tools=null` 与 `toolConfig=null`（`.../data/FirebaseAgentModelGateway.kt:278-325`）。
- 工具结果先由 `AgentEvidenceBundleBuilder` 生成限制长度、去身份化的 `e1..e4` evidence，再把别名只在本地映射回审计 ID（`.../agent/AgentEvidenceBundle.kt:61-105`）；第二阶段不会得到 userId、内部审计 ID、provider call ID 或原始工具记录。
- 本地缓存解析的 Firebase AI SDK 是 `firebase-ai:17.16.0`。该版本的 `ServerException` 公共类型只有 message/cause（`type/Exceptions.kt:91-93`）；`APIController.validateResponse` 对非 200 响应先在 SDK 内部解析错误体/特例，再将其余情况构造为 `ServerException(message)`（`common/APIController.kt:428-483`），没有把 HTTP status、gRPC code、reason 作为公共字段交给应用。

### SDK 诊断边界

- **A，SDK 未公开：**对 `ServerException`，HTTP status 与结构化服务端错误码在 17.16.0 的公共异常 API 中不可读取。SDK 内部虽读取响应状态和错误体，但不会将这些字段附到该异常上。
- **B，项目保留：**`FirebaseAgentModelGateway` 仅记录白名单的阶段、模型、backend、SDK 版本、异常类型和首层 cause 类型（`.../data/FirebaseAgentModelGateway.kt:379-425`）；本次把日志标记从含糊的 `NOT_PROVIDED` 改为 `NOT_EXPOSED_BY_SDK`。随后网关只返回 `AgentModelFailure`，调度器将其持久化为 `FINAL_GENERATION / MODEL_SERVER`。
- **C，项目刻意不保留：**异常 message、响应体、stack trace、请求体及认证信息都不记录，因为 SDK 的 message 在某些路径可能含响应内容。
- **D，历史不可恢复：**先前 FAILED Run 中只保留了阶段/错误码和脱敏 trace；历史 `ServerException` 的原始 message、cause message、HTTP status 与服务端 reason 没有合法持久化，无法从现有数据库或本记录还原。

### schema / parser 核对

- Firebase 17.16.0 `Schema.obj` 默认把所有 properties 设为 required（本地 source `type/Schema.kt:219-247`），与 `NutritionPlanOutputParser` 的精确 root/nested 字段要求一致（`.../agent/NutritionPlanOutputParser.kt:103-180`）。新增离线测试直接检查生产 `finalPlanSchema` 并用当前 parser 解析合成合法输出。
- [Firebase 官方 Android 结构化输出文档](https://firebase.google.com/docs/ai-logic/generate-structured-output)要求 JSON 结构化输出同时配置 `application/json` 和 `responseSchema`，且 Firebase Schema 默认字段为 required；当前生产构造与此一致。文档没有证明本项目整份 schema 在本次服务端请求中一定被接受，因此这不是对历史失败根因的结论。
- 本轮未发现可由源码和离线测试证实的生产请求构造缺陷；没有放宽未知字段、evidence、日期、数值或健康安全校验。

### 默认关闭的 A/B 复现入口（本轮均未在线执行）

两个入口位于 `app/src/androidTest/java/com/example/nutritrack/FinalGenerationReproductionTest.kt`，只有同时传入 `enableFinalGenerationReproduction=true` 和精确 case 时才在获取 Application/Firebase 模型后运行。默认 `test/build/connectedDebugAndroidTest` 只使用 Fake transport，不会调用 Firebase；每个 case 最多一次 `generateContent`，不重试、不串联另一 case、不写入业务数据库。

Firebase 17.16.0 的 HTTP client/APIController 没有项目可安全注入的公开替换点；本轮没有反射或修改 SDK。离线测试因此在 SDK 调用前以 Fake transport 验证开关、请求合同和 production parser，而不是把 Fake 误称为 SDK 内部 HTTP 拦截。两个 online 方法是未来单独授权时才会走真实 SDK 的入口。

1. **A：最小结构化输出。**复用 target Application 的 Firebase 初始化、Debug App Check 变体、`GOOGLE_AI` backend、当前模型和 60 秒 timeout；只发送无个人信息文本、一个 `summary` 字段的 object schema、无 tools/toolConfig/history。未来只能在隔离测试模拟器上显式运行：

   ```sh
   ANDROID_SERIAL=<isolated-api35-emulator> ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.example.nutritrack.FinalGenerationReproductionTest#minimalStructuredOutput_onlineProbeRequiresItsOwnExplicitOptIn \
     -Pandroid.testInstrumentationRunnerArguments.enableFinalGenerationReproduction=true \
     -Pandroid.testInstrumentationRunnerArguments.finalGenerationReproductionCase=minimal
   ```

2. **B：当前生产最终生成。**使用生产 `FirebasePlanGenerationModelFactory`、实际 evidence schema、`AgentEvidenceBundleBuilder` 和 `NutritionPlanOutputParser`，但输入为固定日期、固定 `e1/e2` 引用的合成 evidence；不选工具、不读取用户数据库、不写入 Room。未来只能在隔离测试模拟器上显式运行：

   ```sh
   ANDROID_SERIAL=<isolated-api35-emulator> ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.example.nutritrack.FinalGenerationReproductionTest#productionStructuredPlan_onlineProbeRequiresItsOwnExplicitOptIn \
     -Pandroid.testInstrumentationRunnerArguments.enableFinalGenerationReproduction=true \
     -Pandroid.testInstrumentationRunnerArguments.finalGenerationReproductionCase=production
   ```

先运行 **A**：成功只能证明同一 Firebase/App Check/provider/model 的最小 JSON 请求可完成，不能证明生产 schema、Agent 或 UI；失败只能缩小到该最小请求与外部环境的边界。A 成功后再单独授权运行 **B**：成功才证明生产最终请求构造及其本地 parser/evidence 合同可走通，仍不能替代完整 UI/工具选择验收；B 失败则能把问题限定在生产最终请求或服务端边界，不能仅据 `MODEL_SERVER` 指定原因。

### 本轮离线验证与保留状态

- `:app:testDebugUnitTest`（仅 `FirebaseAgentModelConfigurationTest`、`NutritionPlanOutputParserTest`、`AgentModelBoundaryTest`，另加 `FirebaseAgentStageTimingTest`）通过；新增 schema/parser 合同与安全诊断断言通过。`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin` 与 `:app:lintDebug` 也通过。
- `:app:compileDebugAndroidTestKotlin` 通过；没有运行连接设备测试，因为当前非交互 shell 找不到 `adb`，且不应在受保护的验收模拟器上安装测试 APK。
- 本轮真实模型请求：**0**。P2-1/P2-1B2 保持未勾选；P2-2 状态未改。

## 最新：gemini-3.8-flash 两阶段真实验收的外部服务阻塞

- 生产模型已集中切换为 `gemini-3.8-flash`（`2fb03a7`）；单次模型请求时限为 60 秒、Agent 整体时限为 128 秒、显式真实烟测 UI 等待为 143 秒（`06fad94`）。应用没有模型级自动重试。
- 使用同一 API 35 验收模拟器、已登记的 Debug App Check、合成账户和生产 NutriCoach UI 路径，工具选择在 18,912 ms 成功；本地两个只读工具均执行成功并写入脱敏审计。
- 独立结构化计划生成在 25,539 ms 返回 `FINAL_GENERATION / ServerException / MODEL_SERVER`；SDK 对该异常未提供 cause 类型、HTTP 状态或结构化错误码。Run=FAILED、ToolCall=2（均 SUCCEEDED）、DRAFT=0，业务表指纹不变。
- 本轮共发起 2 次模型请求，累计达到 16/16；未自动或人工重试。没有真实草稿，因此成功 UI 展示和草稿重启恢复未验收。
- 该结果记录为 Firebase 外部服务验收阻塞，而非协议放宽依据；禁止为了此 `MODEL_SERVER` 错误继续改写工具协议、Prompt、JSON Schema 或安全校验。后续仅在有新的外部服务证据和明确授权时重新验收。

## 历史：超时排查、离线完整闭环与第 11 次请求

### 实际时限与取消链路

| 边界 | 生效值与来源 | 核对结果 |
| --- | --- | --- |
| 单次模型请求 | `NutritionPlanningAgentConfig.modelSettings` → 两个 Gateway 工厂 → SDK `RequestOptions`：26,000 ms | 没有 Application/VM 额外覆盖；本轮未调大 |
| Agent 整体 | `withTimeout(60_000)`，并以注入 Clock 检查 deadline | 两个名义请求预算 52 秒，保留 8 秒本地预算；未发现上次触及 60 秒 |
| SDK HTTP/socket | 实际解析 17.16.0 源码 APIController：request 26 秒，socket 至少 180 秒 | request 限制先于 socket；HttpRequestTimeoutException 转为 RequestTimeoutException，丢失原 cause/HTTP 状态 |
| App Check 请求头 | SDK AppCheckHeaderProvider：10 秒 | SDK 超时时可继续发送无该 header 的请求；项目未关闭 App Check。未读取 token，不能判断历史 header 等待耗时 |
| 生产 UI/ViewModel | Agent 生成无额外等待超时 | 普通激励文案的 timeout 不包裹 Agent；UI 仅观察状态，无自动 Retry |
| 测试等待 | Fake Compose 标签 10 秒；显式真实 smoke 终态等待 70 秒 | 真实验收通过生产 UI 点击，不使用 smoke 等待器，因此不是测试先超时 |

- 第 10 次请求：Run 耗时 27,608 ms，专用日志在 TOOL_SELECTION 捕获 `RequestTimeoutException`。结合 SDK 的映射以及当前不绑定 SDK 自动执行函数（Chat 首轮返回即退出），与单次 HTTP request 时限一致；不是外层 60 秒产生的 TimeoutCancellationException。仍无法分解连接、鉴权、服务生成或初始化耗时，不能宣称 26 秒过短或服务故障。
- 两阶段总时间还包含模型构建、请求头准备、工具和持久化；没有本次两阶段时间数据前，不把名义 52+8 秒预算当成所有开销必然适配的证明，也不盲目增大时限。
- 网关、Dispatcher、Agent 保留取消传播；Agent 在 NonCancellable 中收敛 CANCELLED，VM 在 Job 边界读取已保存 trace 展示取消，不转换为成功。外层总超时单独保存 MODEL_TIMEOUT。相关隔离测试验证不同终态。
- 应用无自动重试。SDK APIController 没有模型 retry 循环/HttpRequestRetry；Chat 未绑定自动函数执行，不回传 FunctionResponse。OkHttp 默认传输恢复不等于模型级自动重试；没有抓包，不声称 SDK 调用计数等于底层 HTTP 次数。
- `33e3b94` 使用单调时钟为每个 Gateway 阶段记录 START/END、配置时限和实际耗时；END 仅表示退出，不冒充成功。异常仍经原有安全分类记录；不输出 prompt、response、token、健康数据或异常 message。

### 离线成功路径（不代表真实 Firebase 成功）

- 增强现有 `NutriCoachAgentComposeTest.fakeAgentFlowPersistsDraftAndDisplaysOnlySanitizedAudit`：只 Fake 模型/外部水果边界，实际工具 Dispatcher、Parser、安全规则、AgentRepository、Room 事务、ViewModel 和 AgentPlanCard 跑通。
- 使用独立随机文件数据库与独立 DataStore；生成后确认 SUCCEEDED、DRAFT、两条工具 trace 和脱敏 UI，再销毁 ViewModel、关闭并重新打开数据库、创建新 ViewModel。恢复同一完整草稿并展示建议/免责声明/审计，选择与生成计数仍各 1，无额外调用。测试结束只清理自身随机文件，不操作验收数据库或配置。
- 正式输入包含已修复的合法否定句免责声明；Parser 成对回归继续保证对应肯定诊疗、停药等内容被拒绝。
- 35 项相关 JVM 测试通过；Debug 和 AndroidTest APK 构建/编译通过。手动指定 API 35 的上述完整 Compose 流程以及 `timeoutAndCancellationReachDistinctTerminalStates`，2 项均通过。未重跑全量仪器测试、未卸载/清除 App 数据。

### 唯一真实复验及剩余阻塞

- 用户将累计预算提高至 14，本轮从 10 开始，只新增请求 11（工具选择）；结构化生成 0。累计 **11/14**，剩余 3 次未使用，没有自动或人工重试。
- 同一 Medium_Phone_API_35、原 Debug 安装、原已登记 token、合成账户，实际 NutriCoach UI 输入并点击 Retry，走生产 VM/Agent/Gateway。
- 模型 `gemini-3.7-flash`，backend `GOOGLE_AI`，SDK `17.16.0`；工具选择阶段 START 13:31:54.384、END 13:32:01.829（设备日志时间），单调耗时 **7,445 ms**。明确返回 `QuotaExceededException / MODEL_QUOTA`，不是超时。cause、HTTP 状态和 SDK 结构化错误码均未提供，未推测限额类型或重置时间。
- Room 最新 Run=FAILED（耗时 7,534 ms），Run 总数=8，ToolCall=0、DRAFT=0，业务表指纹不变。未到达 Parser，ruleId/fieldPath 不适用。旧第 9 次安全失败的真实字段依然未知。
- UI 正确呈现失败并允许人工 Retry；本轮不点击。重启恢复同一错误、Run 总数仍为 8，没有额外模型请求。只读验证器的 SUCCEEDED 断言如实失败；草稿保存、成功 UI 和草稿重启恢复仅有离线证明，没有真实通过。
- 最小真实复现：原验收安装使用相同合成需求，通过 NutriCoach 单次工具选择即可触发本轮配额分类，无需工具执行或计划生成。已排除本次 UI 等待、外层 60 秒取消；不把配额错误倒推为此前超时根因。
- 下一项必要操作：由用户在当前 Firebase AI Logic 所用项目核对该模型配额/限流及可恢复时间，确认可用后再继续；无需重新登记 Debug token，不擅自启用计费或变更模型。明确配额阻塞期间停止请求，不修改后续计划生成逻辑。
- Room v10/Schema/Migration、DataStore、App Check 和 AI Monitoring 未变；stash OID 仍为 `cbcd4cb6ee7db6bc4fc69cc7934c9094f83abbcd`。

以下为前轮记录，保留历史判断，不覆盖当前配额证据。

## 历史：字段级安全诊断与第 10 次请求

- 用户授权累计上限 11（旧计数 9），本轮只从生产 NutriCoach UI 发起一次 Run。新增 1 次工具选择请求，结构化请求 0；累计 10/11。选择阶段超时后停止，没有自动或手动重试；剩余 1 次未使用。
- 安全诊断：`TOOL_SELECTION / MODEL_TIMEOUT`，`RequestTimeoutException`；model=`gemini-3.7-flash`，SDK=`17.16.0`，backend=`GOOGLE_AI`；cause/HTTP 状态/SDK 结构化错误码均未提供。不能据此确定网络、服务、配额或权限根因。
- Room：最新 Run=FAILED，Run 总数=7，耗时 27,608 ms，ToolCall=0，DRAFT=0，业务表指纹不变。UI 展示可重试错误，无假草稿。强制停止并重启后恢复同一错误，再次只读核对 Run 总数仍为 7，没有新增请求。此次根本未进入 parser，故无 safety ruleId/fieldPath，不得称为安全修复的真实通过或复现。
- 仍保留同一模拟器、应用数据与已登记 token；只覆盖安装 APK，不卸载/清数据。没有读取 token、原文响应、原始健康数据或完整 Logcat。

### 离线定位结果（不是旧响应的根因认定）

- 旧失败响应未合法持久化：Room 无该 Run 的 NutritionPlan，验收辅助文件只含状态与业务指纹；Gateway 不保存原始输出。因此不能重放请求 3/9 的原文，也不能确定其具体字段。
- 实际链路仅 `NutritionPlanOutputParser.validateText` 的 PII/医疗检查产生 `UNSAFE_CONTENT`，再由 `NutritionPlanningAgent` 映射为 `FINAL_SAFETY_VALIDATION / FINAL_SAFETY_INVALID`。Firebase 内容阻断是独立的 `MODEL_SAFETY_BLOCKED`，日期/数值/字段/evidence 错误也不冒充此错误。
- 检查字段：`summary`、`recommendations[0..7].title/action`、`cautions[0..7]`、`disclaimer`。按字段顺序记录首个失败，不保存匹配词或原文。
- PII 规则：`PII_EMAIL`（邮箱模式）、`PII_CREDENTIAL`（密码/API key/token 特征词）、`PII_PHONE`（8–15 位数字模式，排除有效 ISO 日期）。类别为 `IDENTITY_OR_CREDENTIAL`。
- 医疗规则：`MEDICAL_DIAGNOSIS/TREATMENT/PRESCRIPTION/CURE/GUARANTEE/DISEASE_PREVENTION/MEDICATION_CHANGE`。同子句未发现否定词记为 `UNNEGATED_MEDICAL_CLAIM`；存在否定词但无法证明其覆盖命中项记为 `UNRECOGNIZED_NEGATION_SCOPE`。后者表示保守拒绝，不自动断言误判。
- 新诊断只含固定 ruleId/category 和有界 schema fieldPath，经版本化 codec 保存于 v10 原有 errorCode 列；旧 v1 错误仍可读。UI 仍使用固定安全消息，不显示匹配内容、异常或原始 JSON。
- 确认的独立误判：人工合成的免责声明 “not intended as a substitute for medical diagnosis or treatment” 因否定范围词表缺少 `as` 而被拒绝。先添加用例并观察失败，再仅补 `as`；对应肯定句、另起句停药、连接词后夹带停药仍拒绝。该合成用例不是请求 9 的保留样本。
- 契约核对：必填字段、枚举、请求日期和临时 evidence 别名一致；本地仍拒绝未知字段、越界数值和非本 Run 的证据。responseSchema 的统一数值范围只能覆盖总上限，单位级上限和文本长度由 parser 严格检查；生成指令现明确提供相同限制。对空历史明确禁止推断零摄入/营养缺乏，建议只能依据现有证据，不伪造历史或个体化目标。

### 本轮验证

- 36 项相关 JVM 测试通过（Parser 12、错误 codec 3、ViewModel 14、Firebase 配置 7）；涵盖所有文本字段/规则、安全诊断编解码及恢复，合法否定句和对应违规内容成对验证。
- `assembleDebug`、`assembleDebugAndroidTest`（含 `compileDebugAndroidTestKotlin`）通过；API 35 手动指定 1 项内存 Room/Fake Gateway 回归通过，证明具体诊断持久化且失败保留 trace、DRAFT=0。不运行会卸载安装包的全量 Gradle 仪器任务。验收验证器新增写文件表达式后曾被 JUnit 判为非 void 方法；补显式 Unit 并重新编译，随后正常执行到如实失败的 SUCCEEDED 断言，不影响生产代码/请求次数。
- 只读真实验证器的 `SUCCEEDED` 断言如实失败，不能计作真实成功验收。补充了成功草稿的重新解析、证据关联和重启指纹比较能力，但本轮无草稿，这些成功分支未获真实验证。
- 后续必要步骤：原始失败字段仍未知；现有诊断可在下次合法返回计划时定位字段和规则。当前请求超时没有足够证据支持进一步改安全规则或扩大超时；不得把增加额度、放宽安全检查或改 Agent 架构当作已验证的解决方案。

以下保留前轮 9 次请求记录；其中“本轮”指前轮验收，不是本次新增请求。

## 环境与执行边界

- 沿用 Medium_Phone_API_35、已登记 Debug App Check 的 Debug 安装和合成账户。未卸载、清数据、更换模拟器或操作 stash。
- 生产调用路径：HomeActivity → NutriCoach UI 输入需求并点击 Retry → NutriCoachViewModel → NutritionPlanningAgent → FirebaseAgentModelGateway。没有固定工具选择或伪造计划。
- 模型 `gemini-3.7-flash`，实际 SDK `firebase-ai:17.16.0`，backend `GOOGLE_AI`。保持两阶段、每 Run 最多 2 次请求、只读工具、26 秒请求超时、60 秒总时限和严格计划校验。
- 用户将累计请求上限从 8 提高到 9。本轮仅运行一次 Agent，新增 2 次 SDK 模型调用；累计为 9/9，结束后无额外请求。
- 这里的请求数是 SDK 模型生成调用数。应用无自动重试；所检查 SDK APIController 没有模型重试循环/HttpRequestRetry，使用 OkHttp 引擎。未抓包，不能将该计数声称为已测量的底层 HTTP 传输次数。
- 未读取 token、完整 Logcat、模型正文或原始工具结果。诊断只采集专用标签的阶段、模型/SDK/backend、错误分类和异常类型。

## 9 次请求账本

此前 7 次由前轮执行记录与设备中 5 个 Run 的终态/工具审计交叉核对；本轮为第 6 个 Run。编号是本次验收会话中的顺序，不是数据库 ID。

| 请求 | Run | 阶段 | 结果 |
| --- | --- | --- | --- |
| 1 | 1 | TOOL_SELECTION | MODEL_TIMEOUT；RequestTimeoutException；工具 0 |
| 2 | 2 | TOOL_SELECTION | 成功，画像与摄入历史两个工具均成功 |
| 3 | 2 | PLAN_GENERATION | 返回输出；本地 FINAL_SAFETY_INVALID，DRAFT=0 |
| 4 | 3 | TOOL_SELECTION | MODEL_TIMEOUT；RequestTimeoutException；Run 耗时 25,102 ms |
| 5 | 4 | TOOL_SELECTION | MODEL_SERVER；ServerException；工具 0 |
| 6 | 5 | TOOL_SELECTION | 成功，画像与摄入历史两个工具均成功 |
| 7 | 5 | PLAN_GENERATION | MODEL_SERVER；ServerException；DRAFT=0 |
| 8 | 6 | TOOL_SELECTION | 成功，模型选择 get_nutrition_profile 与 get_intake_history，两者审计均 SUCCEEDED |
| 9 | 6 | PLAN_GENERATION | 输出进入本地 parser；FINAL_SAFETY_VALIDATION / FINAL_SAFETY_INVALID，DRAFT=0 |

## 最后一轮实际结果

- 合成需求要求根据最近摄入与本地营养画像改善下周饮水；由实际 UI 输入并发起，工具选择和本地执行通过生产路径。
- 最新 Run=FAILED；ToolCall=2，全部 SUCCEEDED；DRAFT=0；Run 耗时 19,901 ms。
- UI 从 Loading 进入可恢复 Error，未展示假计划或成功状态。强制停止并重启应用后恢复同一安全错误；再次读取确认 Run 总数仍为 6，没有新建 Run。
- Patient、UserAccount（不计登录时间）、QuestionnaireProfile、FoodIntake 的合成账户指纹与生成前基线一致；未产生业务写入。
- 草稿保存、成功建议/依据展示、成功草稿重启恢复未通过真实验收。Debug Provider 的测试不代表 Release Play Integrity 真机验收。

## 失败证据与未知项

- 最后一轮没有捕获 Firebase 异常；HTTP 状态、SDK 错误码和 SDK cause 类型均为“未提供”。准确阻断点为本地 NutritionPlanOutputParser，非本轮 ServerException。
- 该 parser 把 `containsPii` 与 `containsUnsafeMedicalClaim` 都映射为 UNSAFE_CONTENT，调度器再写入 FINAL_SAFETY_INVALID。现有记录不能区分字段/规则，原始输出未保存；不能断言模型违反规则，也不能断言又一次误判。
- 请求 7 的历史异常类型为 ServerException，cause 类型未提供，HTTP 状态及结构化 SDK 错误码未提供。实际 SDK Exceptions.kt 的 ServerException 只有 message/cause；APIController 可将多种非成功响应与错误体解析问题映射为它，因此不能直接归结为供应商故障或排除请求/权限问题。
- 本轮 `4409202` 从 SDK BuildConfig 读取版本；诊断明确输出缺失字段为 NOT_PROVIDED，不解析异常 message，不记录堆栈/响应体。不改变模型配置或安全规则。

## 最小复现与下一项必要工作

1. 无网络复现当前失败边界：用 Fake Gateway 返回合法工具选择，保留两条成功审计，再令结构化输出中的某个文本字段命中现有 PII/医疗规则；验证 FINAL_SAFETY_INVALID、trace 保留、DRAFT=0。现有 parser 与 Agent 回归测试可作为入口。
2. 这只能复现错误路径，不能复现本轮具体生成内容；本轮未保存原文，禁止凭猜测添加规则豁免。
3. 下一步先为 parser 的拒绝补充固定枚举的字段名/规则类别（例如 SUMMARY、DISCLAIMER、PII、MEDICAL_CLAIM），验证诊断绝不携带匹配片段或原文，保持 v10 与校验行为不变。随后才能在另行授权的真实请求中确定具体分支，或用合成正反例离线复现；本轮未实施该扩展。
4. 若后续再次出现 ServerException，先使用无身份/健康数据的最小 SDK 请求并仅收集传输层数值状态或固定错误分类，以区分请求合同、权限/配额、响应解析和服务异常；不得解析/输出完整异常消息或通过放宽安全约束求通过。本轮没有为此额外发请求。

## 验证与保留状态

- 仅运行 FirebaseAgentModelConfigurationTest：7 项通过；assembleDebug、assembleDebugAndroidTest 及 AndroidTest Kotlin 编译通过。没有重跑已通过的全量测试。
- 显式运行只读验收验证类，以设备 Room 状态核对历史和最新结果。其 SUCCEEDED 断言如实失败，不计为通过的端到端验收。
- Room v10、Schema/Migration、DataStore、App Check 与 AI Monitoring 配置未改；未升级依赖。
- `stash@{0}` OID 仍为 `cbcd4cb6ee7db6bc4fc69cc7934c9094f83abbcd`，未 apply/pop/drop/提交。验收用应用安装与数据保留。
