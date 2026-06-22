# BlueTrace GNSS 线收尾 · Agent 构建 Prompt（v4 · GNSS WIP 收尾 + Q1 入口迁移）

> 接 [v3](agent_build_prompt_v3.md)：v3 把 Q1（GNSS 入口迁移）记为"待并入 GNSS 线"。本轮把 **GNSS 线一次性收尾并提交**：
> ① 收尾在途 **GNSS WIP**（数据源那层已基本完成，见下）；② 落地 **Q1 入口迁移**（设置去掉 GNSS 开关 → **采集类型勾选**）；③ 真机核验 `gps.csv`；④ SPEC/原型口径同步。
>
> **权威顺序**：`SPEC.md` ＞ `v4_android.html` 原型 ＞ 截图。**Q1 是用户已确认的决策**（GNSS 从设置移到采集类型勾选，见 `memory/gnss-relocation-decision`）——本轮**直接落地，不再问"要不要迁"**；仅当迁移中的**子决策**不明（如设置侧 GNSS 屏去留、原型 HTML 是否同步改）才用 `AskUserQuestion` 确认。BLE/DUT 解码仍 **Mock**；验收**真机优先**。

---

## 一、怎么用

```
/goal 依据 Docs/agent_build_prompt_v4.md 收尾 BlueTrace GNSS 线：把在途 GNSS WIP 一起完成并提交，落地 Q1 入口迁移（设置去掉 GNSS 开关 → 采集类型选择 sheet 加 GNSS 勾选，按需定位授权），真机核验 gps.csv 真实写入，并同步 SPEC/原型口径。直到「验收清单」全部满足；真机自测、按仓库习惯提交推 main；迁移子决策不明先 AskUserQuestion。
```

---

## 二、本轮范围（Brief）

### A. GNSS WIP 现状（已完成，**别重做**，本轮一并提交）
当前工作区已有这 5 处未提交改动，**数据源那层已通**：
- `shared/.../data/GnssSource.kt`（新）：`GpsSample(lat/lon/altM/speedMps/accuracyM)` + `fun interface GnssSource { samples(): Flow<GpsSample>; None }`。
- `app/.../data/android/AndroidGnssSource.kt`（新）：真实 `LocationManager`（GPS+NETWORK，1s，**while-in-use**，缺 `ACCESS_FINE_LOCATION`/定位关 → 空流；`awaitClose` 移除监听）。
- `app/.../di/AppModule.kt`：`single<GnssSource> { AndroidGnssSource(androidContext()) }` + 注入 `DefaultSessionController(gnssSource = get())`。
- `shared/.../session/DefaultSessionController.kt`：删掉假漂移 `gpsLoop`（上海漂移），改为 `if (config.gnssEnabled) gnssSource.samples().collect { → RunEvent.Gps(...) }`。
- `DefaultSessionControllerTest.kt`：相应改动。
> 即：**真实 GPS 源已接上**，仍由 `config.gnssEnabled` 门控；`RunEvent.Gps → gps.csv` 的落盘路径在 P3 已有。**本轮要做的是"开关从哪来"——Q1。**

### B. Q1 入口迁移（核心，已确认决策）
**目标**：GNSS 不再是"设置里的全局开关"，而是**每次采集在「采集类型选择」里勾选的一路**（D-V4-6：要 GNSS 就开始前授权，否则本次会话不含 GPS）。
1. **采集类型 sheet 加 GNSS 勾选**（`ui/screen/run/CollectTypeSheet.kt`）：在传感器列表外，增加一行 **GNSS（本机定位 · gps.csv）** 勾选项（用 `tertiary` 紫，与存储占用 GNSS 配色一致）。勾选状态驱动**本次会话** `config.gnssEnabled`（不是持久化全局开关）。
   - 数据建模二选一（你判断更顺哪种，**保持 SessionController 接口不变**）：① 在 `CollectConfig` 里把 GNSS 作为独立布尔，sheet 用 `Set<CollectType> + gnss:Boolean`；② 或给采集类型模型加一个 GNSS 伪类型再在 ViewModel 映射成 `gnssEnabled`。
2. **构建 CollectConfig 的地方**（`viewmodel/CollectViewModels.kt`）：`gnssEnabled` 改为**取自 sheet 选择**，不再取自 `AppPreferences.gnssEnabled`。
3. **按需定位授权**：勾了 GNSS 且点开始 → 若 `ACCESS_FINE_LOCATION` 未授予，**就地弹系统请求**；拒绝则本次会话不含 GPS 一路（toast 提示，不阻断采集，§5.2 建议权限）。
4. **设置侧去开关**：移除 `AppPreferences.gnssEnabled` 作为"是否采集 GNSS"的来源 + 设置里的 GNSS 开关行。
   - ⚠️ **子决策（不明则 `AskUserQuestion`）**：设置里的 GNSS 屏（`ui/screen/permission/GnssScreen` + `settings_gnss` 入口）如何处置——
     a) 整屏移除；或 b) 保留为**只读状态/权限说明**（"定位权限/系统定位开关"状态 + 去系统设置，对应原型 GNSS B/C 的缺权限/定位关），仅去掉"启用开关"。**推荐 b**（状态仍有价值，只搬走开关），但请向用户确认 a/b。

### C. 数据路径核验（真机，真实定位）
- 勾 GNSS → 授权 → 跑一次采集 → 结束 → 打开会话文件夹，确认 **`gps.csv` 有真实经纬度行**（不是上海假漂移；室内可能只有 NETWORK provider 的粗定位，也算通）。
- **前台服务 `location` 类型**（§6.5）：GNSS 期间后台续采时定位不断（`foregroundServiceType` 含 `location`，已声明）；不申请后台定位（**D-3**）。
- manifest 的 **GNSS 快照**（§6.2）记录本次是否启用 + 权限态。

### D. SPEC / 原型口径同步
- **SPEC**：把"GNSS = 设置 toggle"的旧口径改成"**采集类型勾选（每会话）**"：至少改 **§8 组件清单的「GNSS 设置行 toggle 变体」**（删/改）、**§6.5**、**§5.2 建议权限**、**§5.1 屏级细节**对 GNSS 的指向；§6.4/§6.5 的 gps.csv 落盘口径不变。
- **原型** `v4_android.html`：原 **GNSS A/B/C** 在设置下——A（已启用 toggle）随 Q1 失效；**B（缺定位权限）/C（系统定位关）** 作为"GNSS 选中后采集侧的状态"仍有用。是否改原型（搬到采集类型 sheet 上下文）属**子决策**，不确定就 `AskUserQuestion`（也可只在 SPEC 记口径、原型留待 v3 类对照轮再收）。

### E. 测试
- `DefaultSessionControllerTest`：补/核 —— 用 **fake `GnssSource`**（发若干 `GpsSample`）+ `config.gnssEnabled=true` → 断言产出 `RunEvent.Gps` / `gps.csv` 有行；`gnssEnabled=false` 或 `GnssSource.None` → 无 GPS 行。commonMain JVM 单测，不依赖 Android。

---

## 三、不做
- 不改 BLE/DUT 协议（Mock）；不申请 `ACCESS_BACKGROUND_LOCATION`（**D-3**，while-in-use + 前台服务 `location` 足够）。
- 不把 GNSS 塞进 BLE 角色体系（它是独立一路、独立 `gps.csv`，§1/§6.5）。
- 不顺手改 V4 已定 IA/交互（扁平设备+限额、硬锁定、三 Tab 等）；要动先问。
- 不引入二期项（服务器/上传/`dataSync` 收敛）。

---

## 四、验收清单（Definition of Done · `/goal` 达成条件）
1. **入口迁移**：采集类型 sheet 有 GNSS 勾选并驱动本次 `config.gnssEnabled`；设置里**无 GNSS 启用开关**；按需定位授权（拒绝则本次无 GPS、不阻断）。
2. **数据真**：真机勾 GNSS + 授权跑一轮 → 会话 `gps.csv` 有**真实定位**行；manifest GNSS 快照正确；不勾则无 `gps.csv`/无 GPS 行。
3. **WIP 收尾**：5 处 GNSS WIP + 本轮改动**一并提交**；构建 `:app:assembleDebug` 成功，`:shared` JVM 单测全绿（含 GNSS 订阅→落盘新测）。
4. **口径同步**：SPEC GNSS 相关段落改为"采集类型勾选"；与"设置 toggle"冲突处清掉；偏离/子决策在提交说明 + `AskUserQuestion` 记录裁决。
5. **不回归**：扫描→连接→采集→结束→数据→导出闭环正常；深色/外观、启动屏不回归。
6. **真机自测**：关键路径在真机验证（截图配方见 v3 §E：adb `-s 5d510bb4`、冷启动、`screencap -p`+`pull`、`input tap/swipe`、`cmd uimode night`）。

> 偏离 SPEC/原型处在提交说明列明。GNSS 线收尾后：协议冻结 → 换真实 DUT 解码（接口不变），再做 iOS。
