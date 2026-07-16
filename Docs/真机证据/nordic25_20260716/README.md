# nordic25_20260716 — #25 A/B 与三层观测原始日志(审计说明)

> 2026-07-16 · Xiaomi M2101K9C / Android 13 · 构建 = task/25 分支观测版(三层 fork 日志 + 自写侧同格式日志)
> 结论消费处: `Docs/设计/Nordic重连挂死_根因分析.md` 终局节、CHANGELOG 2026-07-16 条、上游 issue #337。

## 文件与筛选规则(复算口径)

**审计缺口先声明**: `ab_selfwritten.log` **混入了两段实验**(cancel 压测段 + hold 段)且当时未按 run 分文件——
"自写 8 次 0 挂死"不能从该文件整体直接数出, 必须按下述规则切段后复算; `hold_nordic.log` 则是单段, Nordic 7/3 可直接复算。

### `hold_nordic.log` — Nordic 后端 · hold 模式(连接后 12s 不取消)
- 复算: `DISC_REQUEST` 共 **7** 行; 有对应 `DISC_CALLBACK`(按 gattId 配对)的 **4** 行 => **3 挂死**。
- 有效性: 全文件唯一一次 `connect cancelled`(16:55:41)晚于全部三次挂死(16:54:38 / 16:55:05 / 16:55:32),
  排除"取消致回调不来"。

### `ab_selfwritten.log` — 自写后端(含两段, 按行内标记切分)
- **归属标记**: 自写侧 `DISC_REQUEST` 行带 `backend=selfwritten` 尾标; 无该尾标的 DISC_* 行是**混入的
  Nordic 段残留**(当时后台 logcat 未及时更换), 复算自写指标时**必须按此标记过滤**。
- **段 1(cancel 压测, ~16:45:04–16:48:2x)**: 取消路径的无回调是**预期**(取消即拆 GATT), 不计入挂死口径。
- **段 2(hold, 16:49:40–16:52:11)**: `backend=selfwritten` 的 `DISC_REQUEST` 共 **8** 行, 但只覆盖
  **4 个 gattId**(每连接因两次 onMtuChanged 发两次请求), `DISC_CALLBACK` 共 **4** 行(每 gattId 一次)。
  **正确口径**: **4 个连接/GATT 实例 4/4 均收到回调, 0 挂死**——不得表述为"8 个独立成功样本"
  (逐请求配对有 4 行请求无自己的回调, 那是同 gattId 双请求共享一次回调的实现噪声, 非挂死)。
- 一键复算(任意 shell):
  `grep "backend=selfwritten" ab_selfwritten.log | grep -c DISC_REQUEST` 与逐 gattId 配对 CALLBACK。

## 已知局限
- 两段混一文件、Nordic 行混入自写文件 —— 属实验现场纪律问题, 结论经 backend 标记过滤后成立,
  但**审计链不如分文件干净**; 后续实验按"每 run 独立文件 + runId 头行"执行(UWTP 真机验收门已含此要求)。
- 样本量小(7/8 次), 是**定位性**实验而非统计验收; 50 次量级验收留给将来若重启 #24B 时执行。
