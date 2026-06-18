# BlueTrace 继续开发 · Agent 构建 Prompt（v2 · 收口 + 图标 + 启动页规则）

> 接 [v1](agent_build_prompt_v1.md)：现有 `app/` demo 已到 **P1 末**、与文档**高度吻合**（KMP `:shared`+`:app`、扁平设备、三 Tab、Mock BLE 接口化、i18n 资源化、29 个 commonMain 单测）。本轮**在现有 app 基础上继续开发**：补 app/启动页图标、定下启动页展示规则、修审计出的结构性差距、补缺失屏、清理项，并按 SPEC §10.5 推进 P3/P4。
> 权威依据：[`/SPEC.md`](../SPEC.md) + [`prototypes/v4_android.html`](prototypes/v4_android.html) + 本文档。真实通信协议仍未冻结 → **BLE/解码继续用 Mock**（接口不变，协议冻结后再换）。

---

## 一、怎么用

在实现 agent 的会话里执行：

```
/goal 在现有 app 基础上继续开发 BlueTrace，依据 SPEC.md + Docs/prototypes/v4_android.html + Docs/agent_build_prompt_v2.md 的本轮范围与验收清单：完成 ① app 图标 + 启动页图标 ② 启动页冷启动仅展示一次的规则 ③ 修审计 4 条结构性差距 ④ 补缺失/孤儿屏 ⑤ 清理项，并按 SPEC §10.5 推进 P3/P4（协议解码仍用 Mock，待冻结）。直到「验收清单」全部满足；每完成一块在模拟器自测、按仓库习惯提交推 main。
```

---

## 二、本轮范围（Brief）

### A. App 图标 + 启动页图标（必做，当前还是模板默认）
- 品牌标识 = **脉冲/心跳 logo**（原型 `splash-logo` 同款：白色描边脉冲线、蓝→紫渐变底）。
- **自适应启动器图标**：替换模板 `ic_launcher`/`ic_launcher_round` —— 前景 = 白色脉冲 logo、背景 = 蓝紫渐变或纯品牌色（`mipmap-anydpi-v26` 自适应）；补 **Android 13+ monochrome**（themed icon）。删 `res/values/colors.xml` 残留紫/teal 模板色。
- **Android 12 `SplashScreen` 图标**：`themes.xml` 配 `windowSplashScreenAnimatedIcon`（= 同 logo）+ `windowSplashScreenBackground`（品牌底）+ `postSplashScreenTheme`；与原型启动屏一致。
- 包名从模板 `com.example.bluetrace` 改为正式域名（`namespace` + `applicationId`）。

### B. 启动页展示规则（SPEC §5.1）
- **只在冷启动展示一次**（进程级 flag）。**app 已在后台存活**（暖/热启动，例如采集中切走再回来、从最近任务回到 app）→ **不再展示启动屏，直接回当前界面**：采集中 → 运行页；否则 → 采集 Tab。
- 用 **Android 12 `SplashScreen` API** 承载冷启动画（系统画 app 图标，一闪而过），不要长停留 / 不要假 loading。

### C. 修审计 4 条结构性差距
1. **导航改「每 Tab 独立嵌套 NavGraph + 独立返回栈」**（SPEC §7.2①②）：把单一扁平 `NavHost` 重构成三个 `navigation<CollectGraph/DataGraph/SettingsGraph>` 嵌套子图，各 Tab 独立返回栈 + `saveState/restoreState` 隔离滚动位与子页栈；采集子链 Run→Summary→Export 嵌在 CollectGraph 内。
2. **采集运行返回拦截改 `PredictiveBackHandler`**（替换 `BackHandler`，对齐预测返回；硬锁定语义不变）。
3. **存储预检全链补齐**（SPEC §5.2 / §5.8）：开始采集前 + 导出前用 `StatFs`/`getUsableSpace` 真实检测；不足 → 红色阻断 + toast/「去清理」；可用 < 1GB → 启动提示。
4. **环境层补两个真数据源**：① 权限永久拒绝 → 产出 `BLOCKED` 态并引导去应用设置页；② 注册蓝牙 `ACTION_STATE_CHANGED` 广播监听，使采集中关蓝牙能实时触发「暂停 · 自动重连」。

### D. 补缺失 / 孤儿屏（对照原型）
- 缺失：**启动C**（后续启动缺权限 ModalSheet）、**导出D**（存储不足导出前预检阻断）、**GNSS C**（系统定位总开关关闭态）。
- 孤儿（已建屏但无导航入口）：接通 **启动E**（蓝牙已关闭）、**启动F**（后台省电指南）的 `navigate` 入口（蓝牙关检测处 / 权限门控建议组「后台不省电」处）。
- 永久拒绝引导系统设置（启动D 态）落地。

### E. 清理项
- `SessionController.simulateStorageFull()` / `injectDisconnect()`、采集屏 `DemoChip` 等演示钩子 → 加 `BuildConfig.DEBUG` 门控或移除。
- 修 commonMain i18n 泄漏：`shared/.../domain/Subject.kt` 的 `Sex` 枚举写死中文（`MALE("男")`），`bioLine()` 直接拼 `sex.label` → 改为调用方用 `sexLabel()`/收已本地化串（避免采集主界面磁贴绕过资源）。
- 删模板残留 `colors.xml`/占位 `themes.xml`；补 `app/src/test` 与 `app/src/androidTest`（依赖已声明无源）。
- 长屏（`ExportLocationScreen`/`GnssScreen` 等）包 `verticalScroll` 防小屏截断。

### F. 推进阶段（SPEC §10.5，协议仍 Mock）
- **P3**：D-6 落盘真实化（okio 写 raw HEX + 每模块解码 CSV + `session_manifest.json` 含 unix 起点/时区/质量小结）、MediaStore 导出全链、**GNSS 实体 `GpsSample` + gps.csv**（while-in-use）。
- **P4**：前台服务托管采集（`connectedDevice|location` + 常驻通知作在场感）、进程恢复（服务重绑续采 / 开口会话自动收尾 + toast）。
- 前台服务类型补 `dataSync`（与 §6.5 字面对齐，二期上传用）；按需补 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 引导。

---

## 三、不做（同 v1）
真实协议解码（Mock 替代，留接口）、传感器总控/设备端算法（配置A/B）、设备维护具体功能（占位）、iOS、服务器/二期。

---

## 四、验收清单（Definition of Done · `/goal` 达成条件）

1. **图标**：启动器图标 = 品牌脉冲 logo（自适应 + monochrome，无模板残留）；Android 12 SplashScreen 显同款 logo；包名为正式域名。
2. **启动页规则**：冷启动展示一次后进主流程；**app 在后台存活时再进入不再展示启动屏**，直接落当前界面（采集中→运行页，否则采集 Tab）—— 可在模拟器复现验证。
3. **导航**：三 Tab 各自嵌套 NavGraph + 独立返回栈（切 Tab 保状态、重选回根、子页栈隔离）；采集运行用 `PredictiveBackHandler` 硬锁定。
4. **存储/环境**：开始前 + 导出前真实存储预检（不足阻断 + 提示）；权限永久拒绝→`BLOCKED`→引导设置；采集中关蓝牙（广播监听）→ 暂停·自动重连。
5. **屏覆盖**：启动C/导出D/GNSS C 补齐；启动E/F 有可达入口；无孤儿屏。
6. **清理**：演示钩子加 DEBUG 门控/移除；commonMain 无写死中文（i18n 泄漏修复）；模板资源清理；app 侧测试补上。
7. **构建/测试**：`./gradlew :app:assembleDebug` 成功；`:shared` JVM 单测全绿（含新增 P3 落盘/存储预检/启动规则相关测试）。
8. **闭环**：扫描(Mock)→连接→采集→实时流→长按结束→D-6 落盘(真实文件)→数据 Tab→导出 Download/BlueTrace/ 全程跑通。

> 偏离 SPEC/原型处在提交说明里列出并说明原因。协议冻结后另起 v3：换真实 BLE(Nordic) + Wire 解码（接口不变），再做 iOS。
