# S7 固件端 OTA · 完整流程与「为什么不能只刷 fw.dat」（代码级证据）

> **目的**：给出 S7 手表固件侧 OTA（B2A 资源 OTA）的完整流程、错误处理、以及「只推 fw.dat 为什么必然失败」的**逐行代码证据**。
> **证据口径**：每条结论带 `file:line`（固件仓 `E:\1\apollo4_watch_s7`）+ 真机日志（`Docs/log/log5.txt`，2026-07-08 O-1 实验）。**仅 boot/SecBL 二级引导的镜像写入是闭源预编译产物（`SecBL.dat`），无源码——该处标 【推测】**，其余全部 【实证】。
> **一句话结论**：**只刷 fw.dat 必失败**——固件在 OTA 开始就 `SYS_fremove_all` **擦空目标资源区**（`System.c:3763`），随后只写 App 推送的文件，`OTA_DoRecvBefore` 又是空壳不补拷；末尾 `OTA_DoRecvAfter` **硬校验目标区必须存在 Res 三件套**（`OTA_Main.c:643-676`），缺则 `EECT_OTA_LOAD_RES(1101)` 中止、不置生效标志。字库缺可从旧区补拷（例外），Res 不补拷。

---

## 0. 三套 OTA 路径定位（只讲被编译进当前手表固件的 B）

固件有三套独立升级路径（`Docs/03_BLE与协议/protocol-analysis/03-ota.md`）：A=AMOTA BLE（**未编译**）、**B=项目自有资源 OTA（本文，经 B2A/AMDTP）**、C=AP_OTA SPI（对 Modem）。App 驱动的是 **B**。B 只刷 **应用/资源区（eMMC A/B 双区 + 主 MCU 镜像 fw.dat）**，**永不经 BLE 碰 SecBL/boot**——砖必落可 J-Link 恢复区（`Docs/OTA/S7采集OTA_设计.md` §1.3）。

---

## 1. 完整流程（一图）

<div class="fig">
<svg viewBox="0 0 860 470" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>S7 资源 OTA 完整时序（App 与固件，含擦空/校验/生效门）</title>
<desc>OTA 启动擦空目标区 → REQ/START/DATA/STOP 逐文件接收 → DoRecvAfter 校验 Res 存在 → 通过才置标志重启刷入。</desc>
<style>
.t{fill:var(--fg);font-size:12px;font-weight:600;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:9.5px;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.m{fill:var(--fg);font-size:10px;font-family:Consolas,monospace;}
.a{fill:var(--accent);font-weight:700;}
.d{fill:var(--danger);font-weight:700;}
.ll{stroke:var(--line);stroke-width:1.5;}
.arw{stroke:var(--fg);stroke-width:1.1;marker-end:url(#ah);}
.arwb{stroke:var(--accent);stroke-width:1.1;marker-end:url(#ahb);}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.dbx{fill:var(--code);stroke:var(--danger);stroke-width:1.3;}
</style>
<defs>
<marker id="ah" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="var(--fg)"/></marker>
<marker id="ahb" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="var(--accent)"/></marker>
</defs>
<text x="150" y="20" text-anchor="middle" class="t">App（手机）</text>
<text x="660" y="20" text-anchor="middle" class="t">S7 固件</text>
<line x1="150" y1="28" x2="150" y2="452" class="ll"/>
<line x1="660" y1="28" x2="660" y2="452" class="ll"/>

<rect x="470" y="36" width="380" height="34" rx="4" class="dbx"/>
<text x="660" y="50" text-anchor="middle" class="m d">① SYS_OtaStart(2)：SYS_fremove_all(目标区)</text>
<text x="660" y="63" text-anchor="middle" class="s">System.c:3763 —— 目标资源区被整个擦空</text>

<line x1="150" y1="90" x2="658" y2="90" class="arw"/><text x="405" y="84" text-anchor="middle" class="m">REQ 0x04 <tspan class="s">会话总量</tspan></text>
<line x1="660" y1="112" x2="152" y2="112" class="arwb"/><text x="405" y="106" text-anchor="middle" class="m a">12B 应答 <tspan class="s" font-weight="400">等 MMI 授权 · sliceMax</tspan></text>

<rect x="60" y="126" width="740" height="150" rx="4" fill="none" stroke="var(--muted)" stroke-width="1" stroke-dasharray="4 3"/>
<text x="70" y="141" class="s">对 App 推送的每个文件（fw-only 时只有 fw.dat 这 1 个）:</text>
<line x1="150" y1="162" x2="658" y2="162" class="arw"/><text x="405" y="156" text-anchor="middle" class="m">START 0x01 <tspan class="s">文件头</tspan></text>
<line x1="660" y1="182" x2="152" y2="182" class="arwb"/><text x="405" y="176" text-anchor="middle" class="m a">1B ack</text>
<line x1="150" y1="206" x2="658" y2="206" class="arw"/><text x="405" y="200" text-anchor="middle" class="m">DATA 0x02 ×N <tspan class="s">切片 → 落盘到目标区（只写这个文件）</tspan></text>
<line x1="660" y1="226" x2="152" y2="226" class="arwb"/><text x="405" y="220" text-anchor="middle" class="m a">9B 累加和 ack</text>
<line x1="150" y1="250" x2="658" y2="250" class="arw"/><text x="405" y="244" text-anchor="middle" class="m">STOP 0x03</text>
<line x1="660" y1="268" x2="152" y2="268" class="arwb"/><text x="405" y="262" text-anchor="middle" class="m a">1B ack <tspan class="s" font-weight="400">尺寸兜底 + 末文件 ResCheck</tspan></text>

<rect x="470" y="288" width="380" height="52" rx="4" class="dbx"/>
<text x="660" y="303" text-anchor="middle" class="m d">② OTA_DoRecvAfter · Step1</text>
<text x="660" y="317" text-anchor="middle" class="s">校验目标区必须存在 ResCheck/ResFat/ResData（OTA_Main.c:643-676）</text>
<text x="660" y="331" text-anchor="middle" class="s">缺任一 → EECT_OTA_LOAD_RES(1101) → 末 STOP 回 code=13</text>

<line x1="660" y1="360" x2="152" y2="360" class="arwb"/><text x="405" y="354" text-anchor="middle" class="d m">✗ fw-only：Res 缺 → STOP FSUM_FAIL(13) → App 显失败</text>

<rect x="470" y="376" width="380" height="46" rx="4" class="bx"/>
<text x="660" y="391" text-anchor="middle" class="m a">③ 仅当 Step1/2 全过（EECT_OK）</text>
<text x="660" y="405" text-anchor="middle" class="s">OTA_SetFlag(标志) + OTA_Done → SYS_Reset（OTA_Main.c:1883-1888）</text>
<text x="660" y="418" text-anchor="middle" class="s">重启 → OTA_Check 见标志 → 跳 SecBL 刷 fw【SecBL 闭源·推测】</text>
<text x="405" y="445" text-anchor="middle" class="s">App 观测边界：只看 B2A ack；成功=末 STOP SUCC→重连读版本（D-4）</text>
</svg>
</div>

---

## 2. 逐阶段 + 代码证据

### 2.1 ① OTA 启动：**擦空目标资源区**（核心！）【实证】

App 发起后，固件走 `SYS_OtaStart(U8 type)`（`System.c:3706`，注释"开始OTA处理, 做一些必要的动作"；`AMX_Stop(0)`@`:3723` 对应 log5 首行）。资源 OTA-by-BLE 的 `type==2`（`OTAR_REQ_UPDATE_RES_BY_BLE`）分支：

```c
// System.c:3746-3767
if (2 == type) {                                  // :3746 资源 OTA by BLE
    U8 ucResArea = 0; U8 szPath[...];
    NVM_ResAreaGet(&ucResArea);                    // :3751 当前运行区
    if (ucResArea == 'A') sprintf(szPath, "0:/B"); // :3753 目标区 = 对面区
    else                  sprintf(szPath, "0:/A");
    SYS_flist_all(szPath);                          // :3762 列目标区（旧内容）
    SYS_fremove_all(szPath);                        // :3763 ★ 擦空目标区 ★
    SYS_f_mkdir(szPath, strlen(szPath));            // :3764 重建空目录
    SYS_flist_all(szPath);                          // :3766 再列（已空）
}
```

- `SYS_flist_all` 是**真目录迭代**（`System.c:1871`，`:1896 f_findfirst`/`f_findnext`，逐个打印实际存在的文件），不是打印预期清单——所以它的输出=**目录真实内容**。
- **log5 逐条对上**：`08:47:08` 的 `SYS_flist_all 0:/A/...`（14 个文件，含 `RESCHECK/RESDATA/RESFAT`）= `:3762` 的擦前列表；紧接 `:3763 SYS_fremove_all(0:/A)` 把它清空；`:3766` 再列（空目录→无输出）。**目标区 A 在 OTA 一开始就被擦光。**

> 这一步是全篇的地基：**目标区不是"保留旧文件供复用"，而是每次 OTA 从零开始。**

### 2.2 REQ 0x04 — 会话请求 + MMI 授权 【实证】

- 分派 `ble2appWrap.c:13349`（`BKEY_FILE_TRANS_REQ`）→ `B2A_FileTransReqAck`（`:3587`）解析 8B 请求（moduleId/isOffset/fileCount/totalSize）。
- 磁盘预检 `(ulTotalSize/1024) > 剩余KB` → `BFRS_DISK_FULL`（`ble2appWrap.c:3718-3720`）。
- OK 路等设备端 MMI 授权，异步回 **12B**（`status/moduleId/fileCount/currFileIdx/sliceMaxSize LE/offset LE`；`B2A_FileTransReqAckFromMMI`）。**ucOtaMode（全量/差分）设备侧无实际作用**（见 §3 附注）。

### 2.3 START 0x01 — 单文件头 + 分配接收缓冲 【实证】

`B2A_FileTransStartAck`（`ble2appWrap.c:4149`）：解析 `ulCurrFileSize/ulOffset/ulSliceSize/ucType/ucZipFlag/文件名`（`:4178-4202`）；按 `ulSliceSize` 分配接收缓冲，`ulSliceSize > ulSliceMaxSize` → `EBEC_MEMORY`，否则 `EBEC_SUCC`（`:4247-4254`）。文件名 ≤12（`szFile[13]`，`:4197-4200` 截断）。

### 2.4 DATA 0x02 — 切片落盘（**只写被推送的文件**）【实证】

切片写盘（`B2A_FileTransDataAck3` 写路径）：路径 `0:/<区>/<文件名>`（`ble2appWrap.c:4342-4349`），**首片 `ulOffset==0` 时 `SYS_fremove(单文件)` 后 `SYS_fopen("wb")`**（`:4850-4863`），续片 `"ab+"`。每切片回 9B `recvLen + U32 累加和 + status`。

> **关键**：写盘只碰"收到的这个文件"。**固件没有任何"把没推送的 Res 从旧区拷进目标区"的动作**（下一节 DoRecvBefore 证实）。所以目标区最终 = 擦空后 App 推了什么就有什么。fw-only ⟹ 目标区 = `{fw.dat}`。

### 2.5 STOP 0x03 — 尺寸兜底 + 末文件 ResCheck 【实证】

`B2A_FileTransStopAck`（`ble2appWrap.c:5400`）：
- **per-file 尺寸兜底**（`:5467`）：刚落盘文件的磁盘尺寸 ≠ START 声明 `ulCurrFileSize` → `EBEC_FSUM_FAIL(13)` + 中止。
- 非末文件 → `idx++`、回 `EBEC_SUCC`。
- **末文件** → 触发 `OTA_Stop → OTA_StopTask → OTA_DoRecvAfter`；据其结果回 `EBEC_SUCC` / `EBEC_FSUM_FAIL(13)`。**payload（App 可能发的 16B MD5）被忽略、不解析。**

### 2.6 OTA_DoRecvBefore — **空壳 no-op**（不补拷任何东西）【实证】

编译版由宏门控：`OTA_Main.c:192 //#define MAC_COPY_FILE_BLOCK`（**注释掉→未定义**）→ `:195 #if !defined(...)` 为真 → 编译 `:197` 版：

```c
S32 OTA_DoRecvBefore(S32 lData) {          // OTA_Main.c:197
    ...
    NVM_ResAreaGet(&ucResArea);            // :217
    LOG_INF("...ucResArea:%c", ucResArea); // :218（= log5 那行 ucResArea:B）
    ucResAreaNew = ('A'==ucResArea)?'B':'A';
    return EECT_OK;                        // :222 ★ 无条件早返 ★
    SYS_f_mkdir(...);                      // :226 起全是死代码
    //SYS_fremove_all(szPathNew);          // :229 连这行都被注释
    ...
}
```

- `:222` 之后（拷字库、拷 Res）**全部不执行**。`:788` 那个"差分从旧区拷 Res 三件套"的版本落在 `#else`（`:784-1144`）里，因 `MAC_COPY_FILE_BLOCK` 未定义而**整段死码不编译**。
- **结论**：真机 `DoRecvBefore` 什么都不干。**没有任何机制把 Res 放进被擦空的目标区**——除非 App 自己推。

### 2.7 OTA_DoRecvAfter — **成败门**：Res 硬校验（不补拷）/ 字库补拷 【实证】

`OTA_Main.c:601`。`:634 SYS_flist_all(szPathNew)` 先列目标区（log5 此处只列出 `0:/A/FW.DAT`——印证目标区只剩 fw）。

**Step1 · Res 三件套存在性校验（只查、不补、不验版本）**：

```c
// OTA_Main.c:643-676
sprintf(szFile, "%s/%s", szPathNew, MAC_RES_CHECK_FILE);   // ResCheck.dat
if(!OTA_FileExists(szFile)) {
    LOG_INF("%s(Error) Missing: %s", __FUNCTION__, szFile); // :647 ← log5: Missing 0:/A/ResCheck.dat
    lRet = EECT_OTA_LOAD_RES;                                // :648 (=1101)
    goto GOTO_END_OTA_DoRecvAfter;                           // :649 直接中止
}
... 同样检查 ResFat.dat (:653-662)、ResData.dat (:664-673) ...
```

**Step2 · 字库（fCheck + 字库文件）缺则从旧区补拷**：

```c
// OTA_Main.c:682-707
if(!OTA_FileExists(szFile)) {                 // fCheck.dat 在新区缺
    sprintf(szFileSrc, "%s/%s", szPath, MAC_FONT_CHECK_FILE);  // 旧区
    lRet = SYS_CopyFile(szFileSrc, szFile);   // :699 ★ 从旧区补拷 ★
    ...
}
```

> **不对称是要害**：**Res 三件套——只查存在、缺即挂（`:648`），绝不补拷**；**字库——缺则从旧区 `SYS_CopyFile` 补拷（`:699`）**。这解释了为什么 **ota_part（fw+Res，去字库）可行**、而 **fw-only（连 Res 也不推）不可行**。

### 2.8 生效门 + 重启刷入 【实证 + SecBL 推测】

```c
// OTA_Main.c:1883-1888
lRet = OTA_DoRecvAfter(lData);
if(EECT_OK == lRet) {                 // ★ 仅当 DoRecvAfter 通过 ★
    OTA_SetFlag(MAC_OTA_FLAG);        // 置 MRAM 私有区 OTA 标志
    OTA_Done();                       // 延时 → SYS_Reset()
}
// 非 OK → 不置标志、不重启刷入 → OTA 不落地（设备继续跑当前固件）
```

- fw-only 时 `DoRecvAfter` 返 `1101≠EECT_OK` → **不置标志**。log5 印证：重启后 `OTA_Check() ucOtaFlag:0`（标志没置）→ 无 OTA，正常复位。
- 【推测】置标志后重启，开机 `OTA_Check`（`OTA_Main.c:1618`）见标志 → `OTA_GotoSecBL`（`:1633`）跳二级引导；**SecBL 读 `fw.dat` 写主 MCU 镜像的逻辑是闭源预编译 `SecBL.dat`，无源码**——合理推断其行为（校验+烧写+跳新固件），本文不对该处硬断言。

---

## 3. 为什么「只刷 fw.dat」必然失败（论证链）

<div class="fig">
<svg viewBox="0 0 860 250" xmlns="http://www.w3.org/2000/svg" role="img" style="max-width:100%;height:auto">
<title>只刷 fw.dat 失败的因果链</title>
<desc>擦空目标区 + 只写推送文件 + DoRecvBefore 空壳不补拷 → 目标区只有 fw → DoRecvAfter Step1 缺 Res → 中止。</desc>
<style>
.t{fill:var(--fg);font-size:11px;font-weight:700;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.s{fill:var(--muted);font-size:9px;font-family:Consolas,monospace;}
.d{fill:var(--danger);font-size:11px;font-weight:700;font-family:-apple-system,"Microsoft YaHei",sans-serif;}
.bx{fill:var(--code);stroke:var(--line);stroke-width:1;}
.dbx{fill:var(--code);stroke:var(--danger);stroke-width:1.4;}
.arw{stroke:var(--fg);stroke-width:1.4;marker-end:url(#ah2);}
</style>
<defs><marker id="ah2" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="var(--fg)"/></marker></defs>
<rect x="20" y="30" width="185" height="52" rx="6" class="bx"/><text x="112" y="52" text-anchor="middle" class="t">① 启动擦空目标区</text><text x="112" y="70" text-anchor="middle" class="s">System.c:3763</text>
<rect x="20" y="100" width="185" height="52" rx="6" class="bx"/><text x="112" y="122" text-anchor="middle" class="t">② 只写推送的文件</text><text x="112" y="140" text-anchor="middle" class="s">ble2appWrap.c:4850</text>
<rect x="20" y="170" width="185" height="52" rx="6" class="bx"/><text x="112" y="192" text-anchor="middle" class="t">③ DoRecvBefore 空壳</text><text x="112" y="210" text-anchor="middle" class="s">OTA_Main.c:222 不补拷</text>
<line x1="205" y1="126" x2="255" y2="126" class="arw"/>
<rect x="258" y="96" width="200" height="60" rx="6" class="dbx"/><text x="358" y="120" text-anchor="middle" class="d">目标区 = {fw.dat}</text><text x="358" y="140" text-anchor="middle" class="s">Res 三件套缺失</text>
<line x1="458" y1="126" x2="508" y2="126" class="arw"/>
<rect x="511" y="90" width="200" height="72" rx="6" class="dbx"/><text x="611" y="114" text-anchor="middle" class="d">④ DoRecvAfter Step1</text><text x="611" y="132" text-anchor="middle" class="s">OTA_Main.c:643-648</text><text x="611" y="149" text-anchor="middle" class="s">Missing ResCheck → 1101</text>
<line x1="711" y1="126" x2="761" y2="126" class="arw"/>
<rect x="764" y="96" width="86" height="60" rx="6" class="dbx"/><text x="807" y="120" text-anchor="middle" class="d">中止</text><text x="807" y="139" text-anchor="middle" class="s">不置标志</text>
</svg>
</div>

**四条铁律（全 file:line 实证）合起来 ⟹ 必失败**：

| # | 铁律 | 证据 |
|---|---|---|
| ① | OTA 一开始就 `SYS_fremove_all` **擦空目标资源区** | `System.c:3763`（+ log5 擦前/擦后 flist） |
| ② | 接收只把**推送的文件**逐个落盘（`fremove` 单文件 + `wb`） | `ble2appWrap.c:4850-4863` |
| ③ | `OTA_DoRecvBefore` 是**空壳**，不补拷 Res（差分拷 Res 版是死码） | `OTA_Main.c:222` / `:192/:195` / `:788`死码 |
| ④ | `OTA_DoRecvAfter` Step1 **硬求目标区存在 Res 三件套**，缺即 `EECT_OTA_LOAD_RES` 中止 | `OTA_Main.c:643-676`、门 `:1883-1888` |

**⟹ fw-only：目标区被擦空 → 只写进 fw.dat → Res 缺 → Step1 挂（1101）→ 末 STOP 回 `code=13(FSUM_FAIL)` → 不置生效标志。**

**字库是唯一例外**：Step2 缺字库会从旧区 `SYS_CopyFile` 补拷（`:699`）——所以**可以省字库**，但**不能省 Res**。

**真机实证（log5.txt，只推 fw.dat 1.67MB）**：
```
08:47:08  SYS_flist_all 0:/A/... (14 文件, 含 RESCHECK/RESDATA/RESFAT)   ← System.c:3762 擦前
          → SYS_fremove_all(0:/A)                                        ← System.c:3763 擦空
08:47:12  START fw.dat 1668744  →  ~30s 传完 (流控修复后单文件很快)
08:47:42  OTA_DoRecvAfter NewPath 0:/A → flist 只剩 0:/A/FW.DAT
          Step1 → "Missing: 0:/A/ResCheck.dat" → lRet:1101(EECT_OTA_LOAD_RES)
          → 末 STOP 回 code=13(FSUM_FAIL) → App 显 "DeviceError STOP:fw.dat code=13"
          → OTA_Check ucOtaFlag:0 (标志没置) → 没升级, 正常复位, 非砖
```

---

## 4. 错误处理代码总表

**EBEC_* 通用应答错误码**（`ble2appEx.h:49-70`，STOP/START/DATA 的 1B 应答用）：

| 值 | 名 | 触发（file:line） |
|---|---|---|
| 0 | `EBEC_SUCC` | 正常 |
| 1 | `EBEC_FAIL` | 多包丢包/短包、写盘失败（`ble2appWrap.c:4371`） |
| 4 | `EBEC_MEMORY` | 缓冲分配失败 / `ulSliceSize>ulSliceMaxSize`（`:4238/4249`） |
| **13** | **`EBEC_FSUM_FAIL`** | **尺寸兜底不符（`:5467`）/ 末文件 DoRecvAfter 失败（Res 缺）** |

**关键失败/兜底点**：

| 失败点 | 代码 | 行为 |
|---|---|---|
| DoRecvAfter Step1 缺 Res | `OTA_Main.c:648` `EECT_OTA_LOAD_RES(1101)` → `:1884` 门 | **不置标志、不刷入**（静默没升上，非砖）；末 STOP 回 code=13 |
| DoRecvAfter Step2 缺字库补拷失败 | `OTA_Main.c:703-704` | 同上中止 |
| STOP 尺寸兜底不符（漏包） | `ble2appWrap.c:5467` | `EBEC_FSUM_FAIL` + 中止，防截断文件被提交 |
| 每切片累加和 | 设备逐字节累加回 9B，App 自核不符则重发该切片 | 传输层完整性 |
| **开机回滚兜底** | `OTA_Main.c:1618-1652`：每次开机 `ucStrtupErrCnt++`，达 `MAX_STARTUP_ERR_CNT` → `OTA_GotoSecBL` 回退 | 坏镜像启动失败可回退 |
| **砖化边界** | BLE OTA 只写应用/资源区，永不碰 SecBL/boot（`ble2appWrap.c` 派发只认 OTA/WATCH_BG/BP_FW） | `flash_fw.ps1`（J-Link SWD）无条件恢复 |

> **App 观测边界（D-4）**：App 只看 B2A ack。本例设备在末 STOP 就返回 `code=13`，**App 正确显失败、无假成功**（真机屏证）。若失败发生在 STOP 之后的纯设备内部（本固件不会），App 才看不到。

---

## 5. 结论与可行方案

| 方案 | 内容 | 目标区结果 | 结果 | 耗时 |
|---|---|---|---|---|
| **ota_all** | 字库 + fw + Res 三件套（14 文件） | 全齐 | ✅ 成 | ~9min |
| **ota_part** | fw + Res 三件套（去字库） | Res 齐、字库靠 Step2 补拷 | ✅ 成 | ~8min |
| **fw-only（O-1）** | 仅 fw.dat | 只有 fw、**Res 缺** | ❌ **必失败**（1101/code=13） | 传输 ~30s 但不生效 |

**要真正拿到 ~1min 的 O-1，唯一正解 = 一处固件小改**（你持 `apollo4_watch_s7` 仓）：
- 在 `OTA_DoRecvAfter` Step1（`OTA_Main.c:643-676`），把 Res 三件套的"缺则中止"改成"**缺则从旧区 `SYS_CopyFile` 补拷**"——**照抄 Step2 字库补拷的写法**（`:691-707`，`SYS_CopyFile(旧区, 新区)`）。
- 运行区（旧区）永远有一份合法 Res（设备正跑它），拷过去即可。改完 App 只推 fw.dat 就能过 Step1，~1min 生效。
- 这本质是把 §2.6 那段死码 `:788`"从旧区拷 Res"的意图，在**活的** DoRecvAfter 里正确复活。
- ⚠️ 注意：`System.c:3763` 会先擦空目标区，所以补拷必须在**擦空之后、DoRecvAfter 校验时**做（Step1 里补拷正好在这个时机）。

---

## 附：证据溯源（file:line 汇总）

| 主题 | file:line |
|---|---|
| **擦空目标区** | `Eiot/Platform/Apollo/src/System.c:3706`(SYS_OtaStart) / **:3763**(fremove_all) / :3746(type==2) / :3762,3766(flist) |
| flist 是真目录迭代 | `System.c:1871` / :1896(f_findfirst) |
| DoRecvBefore 空壳 | `OTA/OTA_Main.c:192`(//#define) / :195(#if) / :197 / **:222**(return) / :229(注释 fremove) / :784-1144(#else 死码, :823 fremove_all) |
| **DoRecvAfter Step1 Res 硬校验** | `OTA_Main.c:634`(flist) / **:643-676**(存在性校验) / :647(Missing 日志) / :648(EECT_OTA_LOAD_RES) |
| DoRecvAfter Step2 字库补拷 | `OTA_Main.c:682-707` / :699(SYS_CopyFile) |
| **生效门** | `OTA_Main.c:1883-1888`(if EECT_OK→SetFlag+Done) |
| 开机回滚 | `OTA_Main.c:1618-1652`(OTA_Check/ucStrtupErrCnt→GotoSecBL) |
| REQ 处理 + 磁盘预检 | `Ble2App/ble2appWrap.c:13349`(派发) / :3587(ReqAck) / :3718-3720(disk full) |
| START | `ble2appWrap.c:4149` / :4178-4202(解析) / :4247-4254(缓冲/EBEC) |
| DATA 写盘（只写推送文件） | `ble2appWrap.c:4342-4349`(路径) / **:4850-4863**(offset0→fremove单文件+wb) |
| STOP 尺寸兜底 | `ble2appWrap.c:5400`(StopAck) / :5467(尺寸兜底→FSUM_FAIL) |
| EBEC 错误码 | `Ble2App/ble2appEx.h:49-70` |
| 真机日志 | `E:\1\apollo4_watch_s7\Docs\log\log5.txt`（2026-07-08 O-1 实验，fw-only 失败全程） |

> **闭源边界声明**：SecBL 二级引导（`SecBL.dat` 预编译）读 `fw.dat` 写主 MCU 镜像的具体逻辑无源码，本文相关处已标【推测】；但**只刷 fw 的失败发生在 SecBL 之前**（DoRecvAfter 就挂、不置标志、SecBL 根本不运行），故本文核心论证**不依赖任何闭源推测**，全部 【实证】。

---

- 配套：[`S7采集OTA_指令交互与流程.md`](S7采集OTA_指令交互与流程.md)（App 侧五子命令逐字节）、[`implementation-notes.md`](implementation-notes.md)（O-1 实验记录）。
