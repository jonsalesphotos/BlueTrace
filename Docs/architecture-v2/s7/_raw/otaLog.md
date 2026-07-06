# OTA 升级与日志系统协议事实提取

> 来源：`E:\1\apollo4_watch_s7\Docs\03_BLE与协议\protocol-analysis\03-ota.md`（下称 03）、`04-logging.md`（下称 04）。行号为该两份文档内行号；括注的 `文件:行` 为文档转引的源码位置。

---

## 一、OTA 升级

### 1.0 前提：三套相互独立的 OTA 路径（03:11-19）

| 路径 | 对象 | 通道 | 是否编译进当前固件 |
|---|---|---|---|
| A. AMOTA（Ambiq BLE OTA 服务） | 主机 MCU 固件/SBL | BLE GATT 独立服务 | **否**（参考实现，amotas_main.c 等在 compile_commands.json 中 0 命中，03:19、03:231） |
| B. 项目自有 OTA（资源 + SecBL） | LVGL 资源 A/B 分区 + SecBL 二级引导 | App/MMI 下发 + 上位机推数据，落 eMMC/MRAM | **是** |
| C. AP_OTA SPI | 从机（ASR Modem/CP）固件 | 主机经 AMUX-SPI 推给从机 | **是** |

**实际生效的是 B 和 C；A 仅作协议参考**（03:19）。

### 1.1 路径 A：AMOTA BLE（参考实现，未启用）

**通道**（03:54-57，svc_amotas.h:63-77）
- 服务 UUID `00002760-08C2-11E1-9073-0E8AC72E1001`；RX 特征（写命令）`...0E8AC72E0001`；TX 特征（notify）`...0E8AC72E0002`。SERVICE_PART=0x1001 / RX_PART=0x0001 / TX_PART=0x0002（03:179）。

**帧格式**（03:58-61，amotas_api.h:65-71）
- 单包最大 AMOTA_PACKET_SIZE = 512+16 = **528B**
- 包头：len(2B) + cmd(1B)；包尾：CRC32(4B)
- FW 头 44B（AMOTA_FW_HEADER_SIZE）

**命令枚举**（03:63，amotas_main.c:151-159）：UNKNOWN / **FW_HEADER / FW_DATA / FW_VERIFY / FW_RESET**（文档未给出数值）。
**状态枚举**（03:64）：SUCCESS / CRC_ERROR / INVALID_HEADER / ... / FLASH_WRITE_ERROR。

**FW 头字段**（03:67-68，amotas_main.c:181-200、977-986，偏移）：encrypted(0)、fwStartAddr(4)、fwLength(8)、fwCrc(12)、secInfoLen(16)、version(32)、fwDataType(36)、storageType(40)、imageId(44，仅 Apollo4；SBL=1/APPLICATION=2，03:65)。存储类型 0=内部 flash / 1=外部 flash（03:62）。

**流程时序**（03:109-125）：
1. App 写 RX：`len + cmd + payload + crc32`；`amotas_write_cback` 分片重组 + 包级 CRC32 校验（03:116-118，amotas_main.c:1181-1266）
2. **FW_HEADER**：解析 44B 头 → `amotas_set_fw_addr` 选内/外 flash 并擦除目标区（03:119，amotas_main.c:481-582）
3. **FW_DATA**：按页写 flash，累加 `ui32ImageCalCRC`（03:120，amotas_main.c:618-697）
4. **FW_VERIFY**：比对 `ui32ImageCalCRC == fwHeader.fwCrc` → `amotas_update_ota`：`am_hal_ota_add(MRAM_KEY, magic, addr)` 写 OTAPOINTER（03:121-122，amotas_main.c:1097-1106、849）
5. **FW_RESET**：回 ACK → resetTimer 200ms → `am_hal_reset_control(SWPOI)` 复位（03:123-124，amotas_main.c:417/426）

**校验**：包级 CRC32（每包尾 4B）+ 镜像级 CRC（FW_VERIFY 比对 fwCrc）。
**断点续传**：文档未提及支持（无相关机制描述）。
**状态机**：eAmotaState 存在（amotas_main.c:142-175，03:180），文档未展开各状态名。

### 1.2 路径 B：项目自有 OTA（资源 A/B + SecBL，已启用）

**无 BLE 命令 id 层描述**：文档只给到 `OTA_Start(lData)` / `OTA_Stop` 由 lv_watch/UI 触发、上位机推数据（03:135-141），未列出 B2A 命令编号。

**关键事实**（03:72-86）：
- 资源分区：eMMC `0:/A` 与 `0:/B` 双目录，写入"另一区"后切换，`ucResAreaNew = ('A'==ucResArea)?'B':'A'`（OTA_Main.c:618-624）
- 校验文件：ResCheck.dat / ResFat.dat / ResData.dat / fCheck.dat（字库）（03:77，OTA_Main.c:638-668,683）
- SecBL：100KB，基址 = 2MB−20KB−100KB = **0x001E2000**（OTA_Main.c:77-79）；用 `am_hal_mram_main_words_program(KEY=0x12344321)` 分块写 MRAM（03:79,86）
- SecBL 校验：写前累加 uiChkSum、写后读回累加 uiChkSum2 比对（**累加和，非 CRC**，03:80，OTA_Main.c:1407-1482）
- OTA 标志：MRAM 私有区 `OTA_SEC_BL_DATA.ucOtaFlag`，**MAC_OTA_FLAG=0x01**（定义在 OTA_Main.c:37，03:81）；私有区地址 `MAC_NVM_PRIV_ADDR2`（nvm_Data.h:81，03:82）

**流程时序**（03:129-146）：
1. `OTA_Start(lData)` 建 OtaStartTask → `OTA_DoRecvBefore` 准备"另一区"目录、copy 字库/资源
2. 上位机推数据（写入新区）
3. `OTA_Stop` → `OTA_DoRecvAfter`：校验 ResCheck/ResFat/ResData 存在，缺字库则从旧区 copy（OTA_Main.c:595-776）
4. `OTA_SetFlag(MAC_OTA_FLAG)` 写 MRAM 私有区 → `OTA_Done()` → 延时 1s → `SYS_Reset()`（03:85，OTA_Main.c:1878-1883、1272-1274）
5. 重启后 `ble_freertos_watch.c:1161` 调 `OTA_Check()`：标志==MAC_OTA_FLAG → `OTA_GotoSecBL()`（裸函数改 VTOR+SP 跳 reset，OTA_Main.c:82-95）由 SecBL 执行刷写

**失败回滚 / 状态机**（03:84,144，OTA_Main.c:1618-1653）：
- 开机 `ucStrtupErrCnt` 每次 +1，达 **MAX_STARTUP_ERR_CNT=30**（OTA_Main.c:38）→ 跳 SecBL 回退
- `OTA_CheckSecBL`（ui.c:1122）：比对 SecBL 校验和，不一致则 `OTA_UpdateSecBL` 重写 MRAM 并重启（03:145，OTA_Main.c:1508-1582）

**断点续传**：文档未提及。**签名/加密**：B/C 路径未见非对称签名，仅累加和/CRC16（03:255，需确认）。

### 1.3 路径 C：AP_OTA SPI（对从机 Modem，已启用）

**包结构**（03:92，master_ota_spi.c:284-292）：
`type(4B) + len(4B) + idx(4B) + buf(1536B) + reserved(448B) + fcs(4B)` —— **分块大小 1536B（1.5K，MAX_BUFFER_LEN）**（03:93）。

**包类型（命令 id）**（03:95，master_ota_spi.c:268-282）：
- 与 CP 固件通信：握手=**1**、版本=**3**、设 CP 标志=**5**
- 与 updater 固件通信：握手=**10**、发升级包头=**11**、发数据包=**12**、CRC 校验=**13**、复位标志=**14**

**握手魔数**：主→从 `0x12345678`，从应答 `0x87654321`（03:96,162）。

**校验（两级，勿混淆，03:98-99,247）**：
- **per-packet fcs**：`ota_spi_calc_fcs()` 对 buf 的 len 字节做**简单字节累加和**（`checksum += buf[i]`）写入 4B fcs 字段——**非 CRC16、不覆盖整包**（master_ota_spi.c:1584-1594；填 :1708 / 收 :1921）
- **整文件 CRC16**：`ota_spi_crc_check_16`（crc16_table，初值 0xFFFF）用于升级文件整体校验（master_ota_spi.c:1987-1993、1300）

**流程时序**（03:150-170）：
1. `AmuxHal.c:223` 调 `master_ota_spi_init()` 建 OTA 任务
2. 判断从机在 cp 还是 updater；若在 cp：握手[1]/对比版本[3]/置 CP 标志[5]
3. 进 updater：握手[10]（0x12345678/0x87654321）
4. 读 eMMC 升级包 `fw_asr.dat` 头（updater_package_header_info_block：版本/工具版本/size/CRC/压缩信息，updater_package.h:161-170）→ `ota_spi_crc_check_update_file` CRC16 校验整文件 → 发包头[11]
5. 循环发 1.5K 数据包[12]（每包 fcs=buf 字节累加和；上报进度）
6. CRC 校验命令[13] → 复位 CP 标志[14]
7. `reboot_slave`：AP_CpPwrOffSigal → 延时 → power_on_cp（master_ota_spi.c:1280-1298）→ 读回版本 `ADM_SetModemVer`

**擦写位置**：主机仅 `FAT_fread` 读包 + SPI 下发，擦写在从机侧（推断，需从机固件确认，03:101,249）。**断点续传**：文档未提及。**状态机**：`master_ota_spi_update` 主状态机（master_ota_spi.c:476-640，03:216），文档未展开状态列表。

---

## 二、日志系统

### 2.1 存储（04:10-14, 42-101）

**两套独立管道**（04:101, 255）：
- **UART 环形缓冲管道**：`log_write`/LOG_xxx → 4KB 字节成帧环形缓冲（`.bss`，帧头 u16 长度 2B）→ FreeRTOS 任务 `"log"`（优先级 1，20ms 兜底 + 通知唤醒）批量合并到 384B → **仅 UART0 后端**，不落 eMMC（log_cfg.h:24/39/47/50，log_ringbuf.c:13/16）。满则 drop-newest（丢新不丢老），drain 打印 `[log] N dropped`（04:81-82）
- **eMMC 文件管道**：`SYS_Log2File`（"ab+" 追加）显式写 `0:/sdm/eiotlog.log`，备份 `eiotlog2.log`，**300KB 轮替**（now→bak 重命名）；攒 4KB 批写，掉电前兜底（04:93-99，System.c:374-413/475-520，app_adapter.h:27-31）

**崩溃 dump**（04:121-136）：crash_capture → `.bss` s_crashbuf[2048+4] → 落 **MRAM 0x1F7000**（=2MB−20KB−16KB，nvm_Data.h:6-9），布局 `[magic 'FLG2'=0x464C4732 @+0][len 4B @+4][dump @+8]`，先写 dump 再写头防半截；开机 `log_fault_persist()` 回灌串口 + 追加 eiotlog.log，`.noinit` s_consumed（'CONS'=0x434F4E53）去重。

### 2.2 拉取（BLE 回传，04:103-119）

- **触发命令**：App→设备 `BCMD_DEV_CTRL` + `BKEY_DEV_CTRL_FILE_LOG`（**数值未见显式赋值**，按 ble2appEx.h:430-446 枚举顺序推断，04:107,253）
- **回传 key**：`BKEY_DEV_ACK_FILE_LOG`（ble2appWrap.c:11310）
- **处理**：`B2A_FileLogCtrlAck`（ble2appWrap.c:11307-11655）；`pszData[0]==1` 时整文件（fopen "rb"+SYS_fread）传 eiotlog.log + eiotlog2.log（04:110）
- **分块**：包缓冲 pszParam = 244−15+8 = **237B（包缓冲上限，非纯日志块）**；单包日志负载 = sizeof(pszParam)−8−uiInParamLen ≈ **200+B**（ble2appWrap.c:11322/11479，04:111,246）
- **发送**：`B2A_MakePkt(BCMD_DEV_CTRL, BKEY_DEV_ACK_FILE_LOG, chunk...)`，每块 `vTaskDelay(5)` 节流；无窗口/ACK 流控，MakePkt 返回非 EECT_OK 即停（04:112,114）
- **格式**：原始字节 memcpy 直传，**无压缩**（无 zlib/lzo/LZ4，04:113,115）
- **互斥**：全局变量 `filelog_is_sending`（ble2appWrap.c:11237）+ `B2A_filelog_get/set_sending_status` 防重入；上送中暂停运动/采样、关 GH3220/Gsensor；同时 SYS_Log2File 在上送中/OTA 中抑制落盘（04:97,116）
- **进度**：log_update_progress 25%→100% 线性映射（04:117）
- 旧"BLE 直接拉 hardfault dump"出口已 `#if 0` 关闭；另有实时接口 `SYS_EiotDebugPrint2Ble`（factory_amdtpsSendData，单包截断 240B），需显式调用、非默认管道（04:119）

### 2.3 日志等级 / 模块 / 格式（04:55-64）

- **等级**：NONE/ERR/WRN/INF/DBG = **0..4**（数值越小越紧急），默认编译上限 LOG_LEVEL_INF，低于等级编译期 DCE 剥离（log_cfg.h:11-19）
- **模块**：每文件 `#define LOG_MODULE_NAME`，缺省 `"gen"`（log.h:33-35）
- **行前缀**：`[%08lu] %c/%s: `，如 `[00001234] I/ble: `（8 位零填充 tick + E/W/I/D 单字符级别 + 模块名）；时间戳为 FreeRTOS tick **非 RTC**；自动追加 `\r\n`（log.c:59-63/78/161-162）
- 单条记录上限 320B（超长 log_write 按 320B 分片不丢字节）；单条格式化栈缓冲 128B；历史 `am_util_stdio_printf` 单次 312B 截断（AM_PRINTF_BUFSIZE 320−8，04:140）

---

## 三、与 BLE 主协议（B2A）的关系

| 子系统 | 通道关系 | 出处 |
|---|---|---|
| **日志拉取** | **同一 B2A 信封**：走 `B2A_MakePkt(BCMD_DEV_CTRL, BKEY_DEV_ACK_FILE_LOG, ...)`，即复用 B2A 主协议的 CMD/KEY 命令体系与打包函数，非独立 GATT 通道 | 04:107-112, ble2appWrap.c:11514 |
| **OTA-A AMOTA** | **独立通道**：自有 GATT 服务（UUID 00002760-...1001，RX/TX 特征），自有帧格式（len+cmd+CRC32），与 B2A 无关——且**未编译启用** | 03:54-61, 03:19 |
| **OTA-B 项目自有** | 触发来自 App/MMI + 上位机推数据（`OTA_Start`/`OTA_Stop` 由 lv_watch/UI 调用）；文档**未给出其 B2A 命令 id**，BLE 数据下行的具体信封在两份文档中均未展开 | 03:16, 03:135-141, 03:208 |
| **OTA-C AP_OTA** | **完全不走 BLE**：主机 eMMC → AMUX-SPI → 从机，自有 SPI 包格式 | 03:17, 03:92 |

---

## 四、文档明示的缺口 / 不确定项

- AMOTA 各命令的数值编码未在文档给出（仅枚举名）；`amota_profile_config.h` 仓库缺失（03:251）
- `BKEY_DEV_CTRL_FILE_LOG` 数值为枚举顺序推断，未见显式赋值（04:253）
- 两条 OTA 路径（B/C）均未描述断点续传机制
- OTA-B 中主 MCU 应用固件是否也经 SecBL 刷写依赖二进制 `Boot/SecBL.dat`，源码不可见（03:253）
- AP_OTA 从机侧擦写行为需从机（ASR/CP updater）固件确认（03:249）
- 无非对称签名校验证据（B：累加和；C：包级累加和 + 文件级 CRC16）（03:255）