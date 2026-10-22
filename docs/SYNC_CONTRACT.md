# NutriTrack 同步契约（未来后端边界）

> 契约版本：`nutrition-sync/v1`。本文只定义未来接入服务端时的稳定边界；当前 App 没有后端、没有同步表，也没有任何后台网络调用。

## 资源与标识

| 本地实体 | 服务端资源 | 稳定标识 | 说明 |
| --- | --- | --- | --- |
| `NutritionPlan` | `nutritionPlan` | `planId` | 客户端生成后不改写；`runId` 仅作可审计关联。 |
| `AgentRun` | `agentRun` | `runId` | 仅在需要审计同步时最小化上传。 |
| `AgentToolCall` | `agentToolCall` | `toolCallId` | 仅同步脱敏的审计摘要，不同步原始健康数据。 |
| 确认/放弃动作 | `planAction` | `actionId` | 当前确认沿用稳定值 `confirm:<planId>`；未来同步放弃时必须使用独立且不可复用的 `discard:<planId>`。 |

所有资源均以认证主体隔离。服务端从登录凭据确定主体，不能信任客户端请求中的 `userId`；客户端读取、写入、重试和冲突查询也必须始终限定本地 `userId`。跨用户的 `planId`、`runId`、`toolCallId` 或 `actionId` 一律拒绝，不返回资源是否存在的细节。

## 时间与数据格式

- API 时间戳使用 UTC RFC 3339/ISO-8601（例如 `2026-09-29T08:30:00Z`）；本地 `createdAtEpochMillis`、`updatedAtEpochMillis`、`confirmedAtEpochMillis` 和 `completedAtEpochMillis` 映射为该格式。
- 计划有效期使用 ISO 本地日期 `YYYY-MM-DD`；本地 `validFromEpochDay`、`validUntilEpochDay` 映射为该格式，不传递界面展示文本。
- 本地 `updatedAtEpochMillis` 记录确认、放弃或完成时的本地变更时间；服务端 `updatedAt` 是服务端资源版本时间，二者在未来同步时都必须保留，不能互相伪造或覆盖。
- 所有请求和响应携带 `contractVersion: "nutrition-sync/v1"`；未知主版本、未知状态或未知必填字段必须明确拒绝。

## 计划状态与不可逆规则

未来服务端状态机为：

```text
DRAFT ──确认──> CONFIRMED / ACTIVE ──完成──> COMPLETED
  │                    │
  └──放弃──> DISCARDED └──完成前放弃──> DISCARDED
```

当前本地状态为 `DRAFT`、`CONFIRMED`、`ACTIVE`、`COMPLETED`、`ABANDONED`：本地 `ABANDONED` 映射为服务端 `DISCARDED`。本地离线 WorkManager 仅在已确认/激活计划到达既有结束日期后幂等写入 `COMPLETED`；客户端同步不得自行臆造 `ACTIVE` 或 `COMPLETED`。

- 不允许 `COMPLETED`、`DISCARDED` 回退到任何非终态；不允许 `CONFIRMED/ACTIVE` 回退为 `DRAFT`。
- 确认与放弃竞争时，服务端按资源版本串行化；首个合法终态或确认结果生效，后续冲突返回稳定冲突结果及当前脱敏状态，不采用“最后写入覆盖”。
- 计划内容、证据、免责声明和有效期在确认后不可由同步重写；修订应创建新 `planId`，保留旧审计关联。

## 幂等、重试与冲突恢复

- `POST /plan-actions` 以 `(authenticatedUser, actionId)` 去重；同一 `actionId` 必须使用完全相同的动作类型、`planId` 和契约版本。
- 重复提交、客户端超时或进程重启后，客户端用原 `actionId` 重试；服务端返回首次已落库结果，不重复产生副作用。
- 网络超时不代表失败：客户端先保留本地待确认事实，后续查询动作结果或以同一 `actionId` 重试，不生成新的 actionId。
- 版本冲突、身份变化、删除或无效状态均为可恢复的显式结果；UI 重新读取本地/服务端权威状态，不把冲突显示为成功。

## 离线优先与失败恢复

Room 继续是本地业务状态来源。未来同步必须在本地确认完成后、通过显式持久的同步工作项上传；该工作项只在真实后端需求出现时设计和迁移，当前不存在占位 outbox 或同步表。

上传成功后才记录服务端确认版本；上传失败保留本地状态和 `actionId`，允许受控重试。后台网络任务不得调用 Agent、不得生成计划、不得绕过用户确认，也不得把本地 DRAFT 自动变为已生效计划。

## 健康数据、删除与审计

- 最小化上传：仅上传执行同步所需的计划、状态、时间、版本和脱敏审计摘要；不上传密码、手机号、邮箱、原始问卷、完整摄入明细、模型提示/响应或调试凭据。
- 服务端与客户端审计均按用户隔离，保留 `actionId`、资源 ID、状态、时间和最小错误码；不得记录原始敏感载荷。
- 删除请求需先撤销未完成同步，再按用户范围删除服务端资源及未来同步工作项；受法规/安全保留要求的最小审计记录需明确说明保留期限与不可见性。
- 日志、错误报告和重放工具均不得包含可识别健康数据或凭据。

## 当前实现边界

当前版本仅有离线 Room 草稿、确认/放弃、到期完成和本地审计；没有服务端 API、认证 token 交换、同步表、冲突合并、上传队列或后台网络调用。已确认计划会注册一个完全离线的 WorkManager 到期检查，它不访问 Agent、Firebase 或任何网络。本契约不表示服务端同步能力已实现；未来接入后端时应在 Repository/同步边界实现本契约，使 UI 与 ViewModel 无需了解网络协议细节。
