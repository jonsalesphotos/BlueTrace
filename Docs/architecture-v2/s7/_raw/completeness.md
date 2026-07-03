{
  "items": [
    {
      "aspect": "1. GATT UUID（service/notify/write 全套）",
      "status": "complete",
      "evidence": "「BLE传输层」§1.1：AMDTP Service `0000FFE0-3C17-D293-8E48-14FE2E4DA212`（句柄0x0800）、RX `...FFE1` Write Without Response（值句柄0x0802，下行写）、TX `...FFE2` Notify（0x0804，上行）、TX CCC `0x2902`（0x0805，安全级 DM_SEC_LEVEL_NONE）；128-bit base 与构造宏（svc_amdtp.h:64-71）齐备；ACK 特征 FFEB 明确未启用。矛盾#5（服务集合）已按实际编译开关收敛为仅 AMDTP+GH3X2X，GH3X2X 工厂服务（含其 UUID 类型/属性两处矛盾）与维护控制台数据面无关。",
      "impact": "连接、发现服务、订阅 FFE2 Notify、向 FFE1 写帧的代码可直接落地，无阻塞缺口。"
    },
    {
      "aspect": "2. 帧信封（同步字/长度/校验/字节序）",
      "status": "complete",
      "evidence": "三份来源一致：外层 B2A_HEAD 8B = SOF 0xBB + status 位域 + uiLen(LE16，帧头后字节数) + uiCRC(LE16) + uiIndex(LE16)；CRC16-CCITT-FALSE（Poly=0x1021, Init=0xFFFF, 无反转, Xorout=0），覆盖范围=偏移8起 uiLen 字节（不含帧头）；内层 B2A_DATA_CMD 4B（Cmd/Key/paramLen LE16）仅首包携带；全字段小端、packed 无填充；明确无转义/字节填充机制（靠 SOF+长度域+CRC 定界）。CRC8(0x07/0)仅用于广播不参与帧。",
      "impact": "帧编解码器（含 CRC 单测）可直接按表实现，是全套材料中最扎实的部分。"
    },
    {
      "aspect": "3. 请求-应答配对机制与超时重传约定",
      "status": "partial",
      "evidence": "配对规则明确：应答回显同 Cmd/Key；是否回 Ack 由请求帧头 EHST_ACK 位决定；CRC 失败设备回 EHST_FAIL 作被动 NAK。缺口：B2A手册·上明确「超时/主动重传机制：未出现任何定义」；无事务 ID/seq（uiIndex 仅分片用，心跳 seq 自用）；设备主动 IND（心跳/告警）与应答共用 Notify 通道，同 Cmd/Key 并发请求无法区分；DEV_CTRL 关机/重启/恢复出厂 handler 强制 ucIsOk=0 不回任何包。",
      "impact": "App 必须自行实现单飞（one-outstanding）串行请求队列、自定超时与重试次数（无协议依据，需实测标定）；重启/恢复出厂只能以 BLE 断链事件作为完成判据，控制台 UI 需按此设计。"
    },
    {
      "aspect": "4. 分包/重组（大 payload 过 MTU）",
      "status": "partial",
      "evidence": "接收/重组机制完整：EHST_IS_MULTI_PKT/EHST_MULTI_PKT_END、多包ID=(ucStatus>>4)&0x03（不符即整片丢弃）、uiIndex 递增、续包裸 payload、命令字由 ucLastCmd/ucLastKey 延续。缺口：单包上限 iMaxParamPktLen 从未给出数值（仅可由日志路径 pszParam=244-15+8=237B 反推）；App→设备方向的分片尺寸上限无定义（仅 PUSH_MSG 特例只校 SOF）；MTU 协商机制为文档矛盾#4（AttcMtuReq(247) vs SERVC_MSG_REQ_MTU，watch 路径标注「未决」），仅确认目标 MTU=247、DLE 251。",
      "impact": "上行重组可直接实现；下行（OTA 数据块、大 SET）需 App 侧保守自定分片大小（如 ≤ 协商MTU-3-8，或 ≤225）并实机验证设备缓冲承受度，属可控但必须实测的缺口。"
    },
    {
      "aspect": "5. 命令表完备性（维护类命令逐字段定义）",
      "status": "partial",
      "evidence": "覆盖良好：时间同步 GET/SET 0x02/0x03+0x01（9B 逐字段，含 timezone 语义）、GET_DEV_INFO 0x21（版本 TLV）、GET_SN_INFO 0x26（59B 定长）、GET_DEV_VOL 0x24（10B）、GET_DEV_BLE_MAC/GET_DEV_FUNC/GET_BOND、PERSON_DATA GET 7B/SET 8B、DEV_CTRL 0x01-0x0B、FILE_TRANS 全五子命令、心跳 0x05/0x0C（8B 双向）均有逐字段偏移表。文档自标缺口：MAC/IMEI/ICCID 传输字节序「需与 App 核对」；GET_DEV_INFO 中间固定字节1含义不明；GET_DEV_FUNC 32 位功能掩码逐位含义完全缺失；FILE_LOG 请求 szPassthru 语义未定；GET_DEV_VOL.ulLastTime 恒 0。",
      "impact": "约九成维护命令可直接编码；MAC/SN/IMEI 显示需实机核对正反序（否则界面显示反序 MAC）；功能位掩码无位定义导致无法做能力探测 UI，需固件组补表或抓包比对。"
    },
    {
      "aspect": "6. 错误码表",
      "status": "complete",
      "evidence": "ENUM_B2A_ERROR_CODE_TYPE 0x00–0x0D 全量列出（SUCC/FAIL/TIMEOUT/FORMAT/MEMORY/NOT_SUPPORT/PARAM/BUSY/LOW_BAT/NO_DATA/MD5/CRC/FATAL/FSUM_FAIL，ble2appEx.h:47-71）；另有帧头 EHST 状态位、绑定结果码 EBRT_*(0/1/2)、文件传输 BFRS_*(0-3)、CommAck 1B 语义（0=成功/1=失败/5=不支持）。",
      "impact": "错误码→用户提示的映射表可直接建；唯一小缺口是「每条命令可能返回哪些码」未逐命令穷举，实现时按全集处理即可，不阻塞。"
    },
    {
      "aspect": "7. 绑定/认证/加密前置要求",
      "status": "contradictory",
      "evidence": "存在文档矛盾#1：配对等级 [文件2/3]=DM_AUTH_BOND_FLAG 仅绑定无 SC 位（逐字节确认）vs [文件4]=Bonding+LESC；已知一致部分：Just Works（NoInput/NoOutput）、TX CCC 安全级 NONE（开 notify 不要求链路加密）。业务层 BOND 命令族字段齐全，但 KEYCODE 的 16B Code「占位未读内容」=固件根本不校验，BOND_REQ 应答中 AuthCode(16B) 的生成算法/用途未给。关键缺口：**维护类命令（GET/SET/DEV_CTRL/FILE_TRANS/日志）是否要求先完成 B2A 绑定/鉴权，全套材料零说明**（仅 BOND_REQ 自身有已绑定分支）。",
      "impact": "推测无强制门控、连上即可直发命令，但 DUT 控制台必须首先实测「未绑定直发 GET_DEV_INFO/DEV_CTRL」是否被拒；若被拒则按 BOND_REQ→KEYCODE 流程握手（字段定义已够用）。配对等级矛盾对 App 影响小（OS 层处理），按 Just Works+Bond 实现即可。"
    },
    {
      "aspect": "8. OTA 完整状态机",
      "status": "partial",
      "evidence": "传输层充分：状态机 EOTA_IDLE→REQ→READY→START→TRANS_DATA→END（App层文档§1.3）；FILE_TRANS 五子命令请求/应答逐字段完整（REQ 8B/RESP 12B 含 ulSliceMaxSize+续传 ulOffset、START 16+N、DATA 块+9B 应答含累加和、STOP 1B、OFFSET 4B）；EHST_OTA_PART=0x80、BMID_*/BFTT_* 枚举齐全。缺口：03-ota.md 明确「OTA-B 的 B2A 命令 id/BLE 下行信封未展开」——端到端编排缺失：一次升级要传哪些文件（ResCheck.dat/ResFat.dat/ResData.dat/fCheck.dat?）及顺序、ucModuleId×ucType 组合约定、STOP 后生效触发者（文档显示 OTA_SetFlag/重启走 UI 的 OTA_Start/Stop 路径，与 FILE_TRANS 的衔接未说明）、断点续传仅有 OFFSET 查询无失败重入规范、无签名仅字节累加和。",
      "impact": "可先实现并单测「通用文件传输」层；但完整固件/资源 OTA 会话（文件清单、类型选择、传完如何生效重启）信息不足以直接写代码，需固件组提供升级包规范或抓取正式 App 的 OTA 时序补齐。"
    },
    {
      "aspect": "9. 日志拉取完整流程",
      "status": "partial",
      "evidence": "已明确：触发 Cmd/Key=0x07/0x07，payload=ucModel(1B，==1 拉 now+bak 两文件)+szPassthru（透传原样回显）；回传 0x07/0x09，块=请求前缀回显+裸日志片（≈200+B/块，5ms 节流，无窗口/ACK 流控）；互斥标志 filelog_is_sending；源文件 eiotlog.log+eiotlog2.log（300KB 轮替）；块无 offset/seq，按到达顺序拼接。缺口：**传输完成/EOF 标志零描述**；两个日志文件之间无边界分隔说明；块无序号导致丢包即静默损坏且不可检测；szPassthru 含义「需确认」；FILE_LOG 键值 04 文档称枚举顺序推断，b2a_protocol.h 给显式 0x07（两源微冲突，以 0x07 为准）。",
      "impact": "可发起拉取并接收块，但「何时结束」只能实现为空闲超时启发式（如静默 3-5 秒判完成），文件边界需拿真机样本人工确认；完整性无法校验，控制台需提示用户日志可能不完整。"
    },
    {
      "aspect": "10. 示例报文（golden bytes）",
      "status": "partial",
      "evidence": "全部材料仅 3 处字节级样例：完整帧仅 1 条——产测握手 `BB 02 03 00 2D 46 00 00 08 01 01`（含 CRC，可作信封层 golden）；RPT_DATA 请求 2 条为省略帧头前缀的残帧（`...86 03 04 00 00 00 00 00 00` 等）；日志请求仅 payload `01 00 00 00 00`。时间同步、设备信息、电量、SN、OTA、DEV_CTRL 等维护命令的完整请求帧为 0 条，设备应答方向完整帧为 0 条。",
      "impact": "信封编解码可用握手帧做唯一一条现成 golden 单测（顺带验证 CRC 实现）；所有命令级 golden bytes 需按字段表自行构造+自算 CRC（自证循环，无法交叉验证），或实机抓包补齐后方可建立可信测试基线。"
    }
  ],
  "overall": "总体判定：材料对「设备维护控制台」约七成信息可直接写代码——传输层（GATT UUID、8+4 字节帧信封、CRC16-CCITT-FALSE 参数与覆盖范围、分包重组位定义、错误码全表）扎实且多源一致，时间同步/设备信息/SN/电量/用户信息/重启恢复出厂等命令有逐字段偏移表，可先落地「信封编解码 + GET/SET/DEV_CTRL 最小闭环」。硬缺口集中在五处：(1) 无任何超时/重传/并发约定，且重启类命令不回包，可靠性层需 App 自定并实测；(2) 日志拉取无 EOF 标志、块无序号，结束判定只能靠空闲超时启发式；(3) OTA 传输命令齐全但端到端编排（文件清单、类型组合、传完生效触发）未展开，不足以直接实现完整升级；(4) 鉴权门控（维护命令是否要求先 B2A 绑定）零说明，KEYCODE 实为不校验，需首轮实机验证；(5) golden 示例报文近乎空白（仅 1 条完整帧），单测基线需实机抓包补齐。另有 5 处文档矛盾（配对 LESC 与否、MTU 协商机制、GH3X2X UUID/属性、服务集合），多数已按实际编译开关收敛且对 App 侧实现影响有限。建议实施顺序：信封+基础 GET/SET → 实测鉴权门控与分片上限 → 日志（带超时启发式）→ OTA（待固件组补升级包规范）。"
}