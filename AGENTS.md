# NutriTrack 项目开发指令

## 接手与证据

- 工作根目录是本仓库；先读 `CURRENT_STATUS.md`，再按任务阅读 `PROJECT_CONTEXT.md` 对应章节。执行前核对当前分支、HEAD、差异和未跟踪文件。
- 当前代码决定实现状态；测试结果须注明日期、范围和是否本次执行。历史验收、静态实现、计划和待验证不能混写。
- `docs/context-handoff/` 原报告仅供本地查阅，保留不改、不提交、不强制加入 Git；通过共享 `.gitignore` 和本地排除规则保护，新检出没有原报告也可从三个根文档恢复上下文。
- 原报告存在时：`legacy-main.md` 是共同历史，两个 `legacy-fork-*.md` 是聊天 Fork 增量，不等于 Git 分支；旧停止条件、旧失败和旧 HEAD 不覆盖后续明确决策。
- Agent 工作还读 `docs/AGENT_UI_ACCEPTANCE.md`；UI 读 `docs/UI_REFRESH.md`；同步读 `docs/SYNC_CONTRACT.md`。日志工作区存在时读 `docs/AGENT_DEBUG_TRACE.md`（尚未提交，不属于本次文档提交）。`docs/REFACTOR_PLAN.md` 开头是旧基线，应结合后续章节及当前代码。

## 产品与架构

- 原生 Android 营养记录与一般营养指导 MVP：Kotlin、Compose/Material3、ViewModel/StateFlow、Coroutines、Room、Preferences DataStore、Retrofit/OkHttp、Firebase AI Logic、WorkManager；单一 `:app` 模块。
- 使用 Application 手工注入和轻量 ViewModel Factory；不无故引入 Hilt、UseCase、重复 Repository/DataSource、空同步层或无任务 Worker。
- 业务页面通过 ViewModel 暴露不可变状态与事件，生命周期感知收集；不在数据型 Composable 直接读 DAO/Repository 或发业务网络请求。Settings 同样通过 ViewModel 暴露仅供展示的账户字段；Clinician 因缺少服务端授权保持关闭。
- Room 是业务事实源；CSV 仅作种子输入；DataStore 只存当前用户和末次页面。问卷完成状态由有效 Room 画像推导，不能另存布尔状态。
- 保留用户隔离、严格输入校验、事务和完整显式迁移；禁止破坏性迁移、用默认 0 掩盖坏数据或随意改历史 Schema。Schema 变更须配迁移和数据保留验证。
- Agent 保持“模型选只读工具 → 本地校验执行/审计 → 独立 JSON 生成 → 本地格式/业务/安全/evidence 校验 → 事务保存 DRAFT 与 SUCCEEDED”。不恢复已放弃的原生 FunctionResponse 回传，不强制每次两个工具。
- userId 来自可信会话；工具白名单、调用预算、取消传播、有限瞬时重试、草稿审计和零请求恢复不可绕过。普通 AI 与 Agent 的 Gateway/模型配置保持独立。
- DRAFT 不自动采用；确认仅通过幂等 ActionDispatcher 更新计划。到期 Worker 完全离线，不调用 AI/网络、不执行营养建议。云同步目前仅有契约。

## 数据、环境与修改

- 不输出或写入文档任何密钥、密码、Token、Debug secret、完整用户 prompt/response、原始个人健康数据或未脱敏异常。Debug trace 仅保留白名单结构；Release 使用惰性 No-op。
- 包名使用通用身份；种子仅用人工数据；真实服务配置保持本地忽略。公开源码先运行 `scripts/export_public_source.py`，不携带原仓库 `.git`、缓存、日志或私有交接目录。
- 保持 App Check 的 Debug/Release Provider 隔离、禁止备份和明文流量、账户密码哈希；不擅自更改 Firebase Console、AI Monitoring、模型、SDK 或签名配置。
- 真实 AI 请求需有当前任务的明确授权与调用预算；不沿用旧轮余额，不在普通测试中开启真实探针，不用真实请求证明文档整理完成。
- 保留已有未提交和未跟踪文件。未经任务授权，不切换/重置分支、合并、提交、推送或操作 stash；受保护 stash OID 为 `cbcd4cb6ee7db6bc4fc69cc7934c9094f83abbcd`。
- 受保护验收 AVD 历史为 `Medium_Phone_API_35`；核对身份，不能仅凭端口判断。不卸载、清数据、wipe、重建或读取/重置 Debug token。设备测试优先独立环境；先检查 Runner/安装任务是否破坏已有安装。
- 小范围修改，不全项目格式化、无关升级或改写业务。纯 UI 任务保留数据、导航、Agent 和生命周期语义；字体与授权资源一并保留。

## 验证与文档维护

- 按变更运行有意义的定向 JVM 测试及必要构建/lint；常用 `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`。变体修改检查 Release 编译；仪器源码编译不等于设备测试通过。
- 设备测试使用隔离数据、Fake 外部依赖并显式选择范围；真实探针默认关闭。Schema、取消、幂等、恢复、安全规则变更应验证对应边界；不要删除断言求通过。
- 用户限定只读/仅文档时，不运行会生成文件的构建或设备任务。验证受限就准确记录，不冒称通过。
- 重要任务完成后：更新 `CURRENT_STATUS.md` 的状态/证据/遗留项；长期架构或决策变更更新 `PROJECT_CONTEXT.md` 并保留原因；仅稳定开发规则进入本文件。专项文档按授权范围同步更新。
- 文档记录日期、代码依据、测试范围及未验证事项；不得将一次 Debug 成功扩展为 Release、真机、全工具、长期稳定性或医疗有效性结论。三个根文档保持互相一致，本文件保持简短。
