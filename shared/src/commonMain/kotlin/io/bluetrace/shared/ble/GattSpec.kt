package io.bluetrace.shared.ble

/**
 * 协议通道声明: BLE 层照此发现/订阅/写, 协议知识不进 [BleClient] 实现.
 *
 * 由各协议 profile 声明式提供(W2 起接入 DeviceProfile), BLE 客户端只按此表:
 * - 连接后定位 [serviceUuid16] 服务, 逐个订阅 [notifyChar16s] 的 Notify;
 * - 下行写默认落 [writeChar16], 写类型按 [writeWithResponse] 选(见预裁定: W1 全部特征仍走 Write Without Response).
 *
 * UUID 一律用 16-bit 短码大写(口径同通用 [extract16]: "FFE0"/"2A37"), 比对方按 extract16 归一.
 * `spec == null` 语义 = BleClient 实现自行探测(现状 B2A->HRS 分支), 迁移期行为等价.
 */
data class GattSpec(
    /** 主服务(16-bit 短码大写). */
    val serviceUuid16: String,
    /** 需订阅的 Notify 特征(可多个); 多特征协议靠此实现按通道分流. */
    val notifyChar16s: List<String>,
    /** 默认写特征(纯上行/无下行设备为 null). */
    val writeChar16: String?,
    /**
     * 默认写特征是否 Write With Response(预裁定: 仅留字段供 AndroidBleClient 选 writeType,
     * W1 不为其新建流控路径; S7/HRS 走 false = 现有 Write Without Response 流控).
     */
    val writeWithResponse: Boolean = false,
)
