# 公开源码隐私审查

> 2026-10-22 公开副本后续更新：新截图全部来自人工 Compose 夹具；公开导出现在也包含指定 GitHub workflow 与 CONTRIBUTING.md。新改动和验证见 [PUBLIC_RELEASE.md](PUBLIC_RELEASE.md)。下文旧文件数量只描述此前清理批次，不代表本次新增文件后的数量。

核对日期：2026-10-22（Asia/Kuala_Lumpur）。范围：本次工作区的源码、测试、资源、配置与文档，以及所有 Git refs 可达的历史对象。报告不记录任何原始身份值、电话、健康数值、项目配置或凭据。未访问 Firebase Console、设备数据、远端仓库或真实 AI 服务。

## 已完成清理

| 位置/风险 | 本次处理 |
| --- | --- |
| 应用 ID、Kotlin 包、import、测试路径、Room Schema 目录含个人/课程身份 | 统一改为 `com.example.nutritrack`；包括原有未跟踪日志源码。八份 Schema 内容逐一哈希比对不变，不改 Room v12、表结构、迁移或设备数据库 |
| 欢迎页包含作者全名和院校推广 | 改为项目名称及通用健康建议；移除院校链接 |
| `app/google-services.json` 跟踪真实项目配置与客户端 key | 删除当前文件，加入共享忽略；Gradle 仅在开发者本地提供配置时应用 Google Services 插件；不提供伪造 key |
| 被忽略的 `local.properties` 留有旧 AI key | 删除该属性，保留本机 SDK 定位；此文件仍不纳入公开源码 |
| 种子 CSV 含电话与营养记录，来源/同意证据不足 | 保留 63 列接口，九行所有身份及数值重新人工生成，非原记录去标识化或数值扰动；README 标注人工数据及演示注册方式 |
| Clinician 用固定客户端口令读取全部患者统计 | 删除口令、统计加载和 AI 分析界面；保留导航目的地，显示访问不可用。未伪装成真实临床认证 |
| 历史 Markdown 含本机路径、旧包名与真实 Firebase 项目标识 | 替换为通用包名、环境变量和脱敏占位；旧记录不作为可重放脚本 |
| 本地忽略不能随克隆传播 | `.gitignore` 增加服务配置、私有交接、密钥、数据库、日志及产物规则；导出器使用额外源码白名单 |

静态复核：当前可导出文件未发现原身份、原种子电话或已识别配置/凭据值的匹配。二进制文件也参与已知值扫描。检查了 drawable 中 11 个图像：未见个人照片、署名或个人界面截图，未发现 EXIF 字段；其他 launcher 资源也参与字节扫描。Geist 字体与授权文件保持原样。测试中的人工邮箱使用示例域名，隐私拒绝测试保留。

## 必须区分的剩余边界

1. **原仓库不是可直接推送的匿名仓库。** 可达历史共 60 个提交、1,577 个 Git 对象；包含旧身份路径、作者姓名/邮箱、旧种子和真实客户端配置。历史 key 模式命中在 Firebase 配置中。扫描不覆盖不可达对象/全部 reflog；没有重写历史、操作 stash、提交或推送。当前未配置 remote，不能据此推断从未分享过。
2. `.git`、IDE/Gradle/Kotlin 缓存、旧 build 产物和 `docs/context-handoff/` 私有报告仍在原开发目录本地，可能含身份或运行记录；按既有保护规则保留。共享忽略不使旧历史消失，也不保护直接压缩整个原目录。使用导出的干净目录或经过校验的 ZIP。
3. 已删除的旧 AI key 是否有效、是否曾对外分发未验证。若曾使用或分享，应在其供应商控制台撤销/轮换；本次未修改控制台。Firebase 客户端配置不是服务端私钥，但真实项目身份仍不在公开副本中分发。[Firebase key 说明](https://firebase.google.com/docs/projects/api-keys)、[GitHub 敏感数据清理](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)。
4. 应用启用 AI 后会把 prompt 和最小化营养 evidence 发往 Firebase/Google；自由输入仍可能包含用户自行输入的个人信息。本地规则与日志脱敏不能保证任意文本永远没有隐私。公开版默认无服务配置，不等于配置后的应用完全离线。
5. 本地密码有盐哈希，其他 Room 业务/审计数据未做应用级加密。没有云端账户认证、临床角色授权、全局账户删除或生产隐私合规验收。Settings 旧数据访问边界及到期任务风险见 CURRENT_STATUS；本次未扩展成整体架构改造。
6. 没有项目级开源 LICENSE，课程衍生素材和插图的再发布权未验证；未擅自为第三方素材授予许可。现有字体许可保留。

## 公开流程

运行 `python3 scripts/export_public_source.py --check` 检查当前选入文件，再使用 `--output` 生成全新的目录和 ZIP。导出包含当前源码而非仅 HEAD，因此不会漏掉现有 Agent 日志工作。脚本拒绝覆盖已有输出、排除本地配置和历史，复制后重新扫描，并逐文件读回 ZIP 校验名称、内容与 CRC。

在导出目录新建公开仓库，首次提交前自行选择公开作者名和 GitHub no-reply 邮箱；不要把旧 `.git` 复制过去。若必须保留旧提交，需要另行在备份副本上处理历史、提交元数据和所有相关引用后复查。仅新增删除提交不能清除旧内容，见上方 GitHub 官方说明。

扫描器是有界启发式检查，不是所有个人信息或秘密的形式化证明。其自动规则覆盖常见 key/token、私钥、个人主目录路径、课程式包名和种子身份；本次另用仓库原值做专门比对，原值未写入公开扫描器。当前无在线发布或新许可证声明。

## 本次验证

本次实际执行，日期均为 2026-10-22：

- Gradle 离线验证：158 项 JVM 测试通过（28 suites，0 failures/errors/skipped）；Debug APK、Release Kotlin 与仪器源码编译通过；lint 0 errors/fatal、34 warnings。
- 全新导出目录独立 `assembleDebug` 通过，36 项任务全部实际执行；未携带本机配置、真实 Firebase 配置或旧构建产物。
- 184 个可公开文件的通用模式与已知原值扫描均无匹配；八份 Schema、图像、字体及其许可内容校验不变；原 Agent 日志逻辑保留。已检查 Debug APK 条目无已识别原身份/配置值。
- 初次测试发现旧断言不适配既有日志增量，改为同时校验 prompt 绑定和恰好一次发送后通过；未删断言或改变 Gateway 行为。Clinician 数据访问边界加入回归检查。
- 导出目录与最终 ZIP 重新扫描；ZIP 逐条内容比对及 CRC 校验通过。原始 Git refs/HEAD/stash 保留，暂存区为空。没有设备任务、Release APK 构建、真实 AI 请求、控制台变更或上传。

测试明细和旧证据边界见 [CURRENT_STATUS.md](../CURRENT_STATUS.md) 第 10 节。
