# 02 · 可注册通道解析器架构（ProtocolProfile / ChannelParser / Registry）

> 状态：设计稿 v1（2026-07-02）。评审通过后按 §8 迁移路线落码。
> 前置阅读：[00_prior_discussions.md](00_prior_discussions.md) §4（现状与空白）。

## 1. 问题

现状的协议解析是**单一全局解码器**：

```kotlin
// shared/src/commonMain/kotlin/io/bluetrace/shared/protocol/SampleDecoder.kt（现行）
interface SampleDecoder {
    fun decode(kind: DeviceKind, notification: BleNotification): List<DecodedSample>
}
// di/AppModule.kt: single<SampleDecoder> { MockSampleDecoder() }   ← 全局唯一
```

这满足不了三个已到眼前的需求：

1. **S7 项目的常规质量通信协议**要接入 —— 帧格式与 BlueTrace v0 信封完全不同；
2. **不同项目不同通道**：各家设备的 GATT Service/Characteristic UUID、订阅集合、写通道都不一样，「通道」这个维度目前没有抽象（`BleNotification` 只有 deviceId + bytes）；
3. **同一会话内多协议并存**：实机已验证的场景——DUT（自研协议）+ Polar H10 参考（标准 HRS 0x180D）同时连接，今天靠 `DeviceKind` 二分，加第三种协议就塌了。

## 2. 设计目标与非目标

**目标**

- G1 新协议接入 = **新增一个 Profile 类 + 注册一行**，零改动既有解析器与上层（开闭原则）；
- G2 通道（Service/Characteristic）成为一等公民，Profile 声明自己要订阅/写入哪些通道；
- G3 解析产物统一为**事件模型**（不只样本，还有 Ack/能力/状态/文件块），为 03 号协议与 04 号手表控制铺路；
- G4 全部落 `commonMain`，iOS 直接复用（KMP 就绪）；
- G5 兼容迁移：不推翻 `SampleDecoder`，第一步用适配器包住，上层（`DefaultSessionController`）零改动。

**非目标**

- 不做运行时动态加载（插件 jar/dex）——「可注册」指编译期组装、DI 期注册；
- 不重设计 BLE 信封（12B 头 + 分片规则不动，见 00 §硬约束）。

## 3. 核心抽象（commonMain，包名 `io.bluetrace.shared.protocol.registry`）

```kotlin
/** GATT 通道标识。Mock 阶段可用约定字符串（如 "mock"），真实端为 128-bit UUID 字符串。 */
data class ChannelId(val serviceUuid: String, val characteristicUuid: String)

/** 解析产物统一事件模型 —— DecodedSample 的超集，控制面/数据面共用。 */
sealed interface ProtocolEvent {
    /** 数据面：已解码样本（沿用现有 DecodedSample，落 CSV 的路径不变） */
    data class Samples(val samples: List<DecodedSample>) : ProtocolEvent
    /** 控制面：命令回执（04 手表控制的地基） */
    data class CommandAck(val cmdId: Int, val status: Int, val detail: String) : ProtocolEvent
    /** 控制面：设备事件 / 能力声明 / 当前状态（msgType 0x10/0x11/0x12） */
    data class DeviceEvent(val code: Int, val detail: String, val deviceTsUs: Long) : ProtocolEvent
    data class Capability(val payload: DeviceCapabilitySnapshot) : ProtocolEvent
    data class State(val payload: DeviceStateSnapshot) : ProtocolEvent
    /** 数据面：设备端算法结果（msgType 0x20） */
    data class AlgoResult(val algorithmId: Int, val deviceTsUs: Long, val metrics: Map<Int, Float>) : ProtocolEvent
    /** 离线回传：文件/会话块（03 号协议 BTCP/1 使用） */
    data class FileChunk(val transferId: Int, val offset: Long, val bytes: ByteArray, val last: Boolean) : ProtocolEvent
    /** 诊断：解析失败帧（计数进质量小结 droppedPackets，原始字节仍在 hexlog） */
    data class Malformed(val reason: String, val skippedBytes: Int) : ProtocolEvent
}

/**
 * 有状态·逐连接·逐通道解析器。
 * 生命周期 = 一次连接；重连必须 reset()（清分片缓冲/序号基线，对应 SPEC §4.9 resync）。
 * 线程模型：由 DefaultSessionController 单 Channel 串行消费保证单线程调用，实现内无需加锁。
 */
interface ChannelParser {
    fun parse(notification: BleNotification): List<ProtocolEvent>
    fun reset()
}

/** app → 设备 命令编码。约束（SPEC §4.2）：编码结果必须装进单次 ATT write，写方向不分片。 */
interface CommandEncoder {
    /** @return null 表示该 Profile 不支持此命令（能力探测用） */
    fun encode(command: DeviceCommand): ByteArray?
}

/**
 * 一个「项目/设备族」的协议接入包 —— 注册的最小单元。
 * 例：BlueTraceV0Profile / S7QualityProfile / HrsProfile。
 */
interface ProtocolProfile {
    /** 稳定 id，进 session_manifest.json（会话回放时选对解析器） */
    val id: String
    /**
     * 设备归属判定（nRF Connect 式分层，2026-07-02 定）：
     * ① **广播特征匹配（首选）**：`ScannedDevice.advertisedServices` 含本协议服务 UUID / 厂商数据特征；
     * ② **连接后确认（兜底）**：service discovery 后调 [confirmByServices] 查关键特征（如 RX/TX）存在；
     * ③ 广播名/MAC **不作协议判据**，仅作扫描页用户过滤辅助。按注册顺序首个命中生效。
     */
    fun matches(device: ScannedDevice): Boolean
    /** 连接后确认（可选二次判定）：传入已发现的全部特征 UUID；默认信任广播匹配。 */
    fun confirmByServices(characteristicUuids: Collection<String>): Boolean = true
    /** 要订阅的 Notify 通道（真实 BleClient 按此订阅；Mock 忽略） */
    val notifyChannels: List<ChannelId>
    /** 命令下行通道；纯观测协议（如标准 HRS）为 null */
    val writeChannel: ChannelId?
    /** 每（设备,通道）一个解析器实例（有状态，勿共享） */
    fun createParser(channel: ChannelId): ChannelParser
    fun commandEncoder(): CommandEncoder? = null
}

/** 注册表：DI 期组装，运行期只读。 */
class ProtocolRegistry(private val profiles: List<ProtocolProfile>) {
    fun resolve(device: ScannedDevice): ProtocolProfile? = profiles.firstOrNull { it.matches(device) }
    fun byId(id: String): ProtocolProfile? = profiles.find { it.id == id }
    val all: List<ProtocolProfile> get() = profiles
}
```

### 3.1 每设备装配：DeviceParserHost

```kotlin
/**
 * 连接期宿主：为一台已连接设备持有 profile + 各通道 parser，路由 Notify → 事件。
 * 由 SessionController（或 ConnectionRegistry）在 connect 成功后创建、断开销毁、重连 reset。
 */
class DeviceParserHost(
    val deviceId: String,
    val profile: ProtocolProfile,
) {
    private val parsers = mutableMapOf<ChannelId, ChannelParser>()
    fun parse(n: BleNotification): List<ProtocolEvent> {
        val ch = n.channel ?: profile.notifyChannels.first()   // Mock 无通道信息时取默认
        return parsers.getOrPut(ch) { profile.createParser(ch) }.parse(n)
    }
    fun onReconnected() = parsers.values.forEach { it.reset() }
}
```

**配套小改动**：`BleNotification` 增加可空字段 `val channel: ChannelId? = null`（Mock 传 null，真实端填实际特征）。这是对现有类型的唯一改动，二进制兼容。

## 4. 数据流（对齐 legacy Architecture §14 四层流水线）

```
BleClient.notifications(deviceId)                    [commonMain 接口，Nordic/CoreBluetooth 实现]
        │  BleNotification(deviceId, channel, bytes, receivedAtMs)
        ▼
DeviceParserHost(deviceId)                            [按 channel 选 parser]
        │
        ├─ BlueTraceV0Parser：FrameReader(切帧+hdrCrc8) → Reassembler(msgId 分片重组)
        │                     → dispatch(msgType) → Wire protobuf / 定宽字节解析
        ├─ S7QualityParser  ：S7 自有成帧/校验 → S7 消息表 → 事件映射
        └─ HrsParser        ：标准 0x2A37 心率测量解析
        │
        ▼  List<ProtocolEvent>
DefaultSessionController（单 Channel 串行消费，现有机制不变）
        ├─ Samples     → SessionRecorder（raw hexlog + CSV，现路径）
        ├─ CommandAck/Capability/State → ControlPlane（04 手表控制）
        ├─ FileChunk   → OfflineImporter（03 离线回传）
        └─ Malformed   → 质量小结 droppedPackets + DiagnosticsLog
```

要点：

- **hexlog 先于解析**：原始字节先落 `raw/<source>.hexlog` 再进 parser（现状已如此），任何解析器 bug 都可离线重放修复；
- **未知不致命**：BlueTraceV0Parser 对 dispatch 表外 msgType 按 `payloadLen` 跳帧（SPEC §4.6 强制规则），产出 `Malformed` 计数不断流。

## 5. 三个内置 Profile

### 5.1 BlueTraceV0Profile（自研 DUT）

```kotlin
class BlueTraceV0Profile : ProtocolProfile {
    override val id = "bluetrace-v0"
    override fun matches(d: ScannedDevice) = d.name.startsWith("BT-DUT")     // 冻结时改按广播服务 UUID
    override val notifyChannels = listOf(ChannelId(DUT_SERVICE, DUT_NOTIFY)) // UUID 待冻结（SPEC §15）
    override val writeChannel = ChannelId(DUT_SERVICE, DUT_WRITE)
    override fun createParser(ch: ChannelId) = BlueTraceV0Parser()           // = 原规划中的 WireSampleDecoder
    override fun commandEncoder() = BlueTraceV0CommandEncoder()              // Command → 12B头+protobuf
}
```

`BlueTraceV0Parser` 内部即既定四层：`FrameReader`（纯函数，可单测）→ `Reassembler`（msgId 缓冲、2s 超时、乱序容忍）→ `MsgDispatcher`（`MsgType` → Wire 消息/`HighFreqBatch` 定宽手解）。**这个类就是 M7 要交付的真实解码器**，只是挂进 Profile 而非全局单例。

### 5.2 S7QualityProfile（S7 项目 · 常规质量通信协议）

S7 帧格式的权威定义**待 S7 侧提供**，接入骨架先行（这正是本架构的意义——骨架不依赖帧细节）：

```kotlin
class S7QualityProfile : ProtocolProfile {
    override val id = "s7-b2a"
    // ✅ 已按真实协议落地（B2aDetect，shared/.../s7/）：广播 UUID 表含 FFE0 即命中，
    //    连接后确认 RX=FFE1(WriteNoRsp)+TX=FFE2(Notify)；不锁名称/MAC，任何 B2A 设备可接入。
    override fun matches(d: ScannedDevice) = B2aDetect.matchesAdvertisement(d)
    override fun confirmByServices(chars: Collection<String>) = B2aDetect.confirmByCharacteristics(chars)
    override val notifyChannels = listOf(ChannelId(AMDTP_SERVICE, "FFE2"))   // 0000xxxx-3C17-D293-8E48-14FE2E4DA212
    override val writeChannel = ChannelId(AMDTP_SERVICE, "FFE1")
    override fun createParser(ch: ChannelId) = S7B2aParser()                 // 帧解码见 s7/S7Frame.kt（已实现）
}

/** TODO 待填帧表：S7 成帧（假定 opcode+len+payload+crc 类结构）→ ProtocolEvent 映射 */
class S7QualityParser : ChannelParser {
    override fun parse(n: BleNotification): List<ProtocolEvent> { /* S7 帧表 */ }
    override fun reset() { /* 清缓冲 */ }
}
```

**需要 S7 侧提供的清单**（放这里当 checklist）：
- [ ] Service/Notify/Write UUID；广播识别特征（名前缀 or 厂商数据）
- [ ] 帧格式：同步字/头部字段/长度域/校验算法（CRC8/16? 多项式?）/字节序
- [ ] 消息（opcode）表：质量数据消息的字段定义、单位、缩放
- [ ] 是否有分包机制；命令-回执模型（有无 cmd_id 等价物）
- [ ] 一段真实抓包样本（hexlog 形式，直接进 commonTest 回放）

### 5.3 HrsProfile（标准心率服务 0x180D · Polar H10 参考设备）

```kotlin
class HrsProfile : ProtocolProfile {
    override val id = "hrs"
    override fun matches(d: ScannedDevice) = d.advertisedServices.contains("180D")
    override val notifyChannels = listOf(ChannelId("180D", "2A37"))  // Heart Rate Measurement
    override val writeChannel = null                                  // 纯观测
    override fun createParser(ch: ChannelId) = HrsParser()            // flags+u8/u16 bpm，几十行
}
```

> MILESTONES 里「标准心率带可先上真实」——HrsProfile 不等自研协议冻结，可作为**第一个真实链路 Profile** 先行打通 Nordic BleClient + 注册表全链路（风险前置）。

## 6. 注册点（DI）

```kotlin
// di/AppModule.kt（Android）；iOS 侧 Koin/手工装配同一列表
single {
    ProtocolRegistry(listOf(
        BlueTraceV0Profile(),   // 顺序即匹配优先级
        S7QualityProfile(),
        HrsProfile(),
    ))
}
```

- **新项目接入 = 写 Profile + 在此加一行**（G1 达成）；
- 按产品 flavor/构建变体裁剪注册列表（S7 版 App 只注册 S7Profile 亦可）；
- `session_manifest.json` 记录每设备命中的 `profile.id`，离线重放 hexlog 时按 id 取解析器。

## 7. KMP 边界

| 组件 | 位置 | 平台依赖 |
|------|------|----------|
| ProtocolEvent / ChannelParser / ProtocolProfile / Registry / DeviceParserHost | `shared` commonMain | 无 |
| BlueTraceV0Parser（含 Wire 生成消息）/ S7QualityParser / HrsParser | `shared` commonMain | 无（纯字节处理） |
| BleClient 实现 | androidMain: Nordic Kotlin-BLE / iosMain: CoreBluetooth | 平台 BLE 栈 |
| 注册表装配 | 各端 DI（内容同一份 commonMain 列表函数 `defaultProfiles()`） | 无 |

解析层 100% commonTest 可测：**golden hexlog 回放**——把真实抓包 hexlog 放进 `commonTest/resources`，逐行喂 parser 断言事件序列（现有 `MockPacketCodecTest` 的升级版）。

## 8. 迁移路线（不打断现有 Mock 流程）

| 步骤 | 改动 | 上层影响 |
|------|------|----------|
| R1 | 落 `registry` 包（本文 §3 全部接口）+ `RegistrySampleDecoder implements SampleDecoder`（内部 resolve→host→parse，只透传 `Samples` 事件） | **零**：Koin 里 `single<SampleDecoder>` 换实现一行 |
| R2 | `MockBleProfile`（包住 MockPacketCodec）注册进 Registry，删 `MockSampleDecoder` 直连 | 零 |
| R3 | `DefaultSessionController` 事件入口从 `List<DecodedSample>` 扩为 `List<ProtocolEvent>`（Samples 之外先只记日志） | shared 内部小改，22 个单测护航 |
| R4 | HrsProfile + 真实 BleClient（Nordic），Polar H10 真机首连 | 新增，不动 Mock |
| R5 | BlueTraceV0Parser（= M7 交付物，等协议冻结）；S7QualityParser（等 S7 帧表） | 新增 Profile 各自独立 |

R1–R3 本周可做（纯 shared 重构 + 测试）；R4 只依赖硬件到位；R5 依赖外部输入（协议冻结/S7 资料），但**骨架已就位，不再阻塞架构**。
