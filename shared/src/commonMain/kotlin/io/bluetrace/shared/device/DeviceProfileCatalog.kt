package io.bluetrace.shared.device

import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.protocol.registry.ProtocolRegistry

/**
 * 设备档案目录(设计 V2 §3.1): **识别真源唯一化**——全 App 只此一处执 [DeviceProfile.matches].
 * 列表顺序即识别优先级(首个 matches 命中). 解码侧 [ProtocolRegistry] 由本目录 [toProtocolRegistry] 派生.
 */
class DeviceProfileCatalog(private val profiles: List<DeviceProfile>) {
    /** 广播识别: 首个 matches 命中的档案(顺序即优先级); 无命中返回 null. */
    fun identify(device: ScannedDevice): DeviceProfile? = profiles.firstOrNull { it.matches(device) }

    /** 按稳定标识查档. */
    fun byId(profileId: String): DeviceProfile? = profiles.firstOrNull { it.profileId == profileId }

    /**
     * 解码侧 [ProtocolRegistry] 由此派生(识别真源唯一, 解码侧消费接口零改动):
     * 收集各档案的非空 [DeviceProfile.dataPlane] 组装(顺序即优先级).
     */
    fun toProtocolRegistry(): ProtocolRegistry =
        ProtocolRegistry(profiles.mapNotNull { it.dataPlane })

    /**
     * 扫描去识别化后的统一打标(扫描流投影层调用): 原始上报(真实客户端 profileId=null)按 [identify]
     * 结果 copy 出 profileId/kind. **已带身份的设备原样保留**——MockBleClient roster 是预置设备
     * (无真实广播, 自带 kind/profileId), 不被 Mock 兜底档案(catch-all)压平(Polar H10=REFERENCE,
     * S7 Mock=DUT 得以保住).
     */
    fun annotate(device: ScannedDevice): ScannedDevice {
        if (device.profileId != null) return device
        val profile = identify(device) ?: return device
        return device.copy(profileId = profile.profileId, kind = profile.kind)
    }
}
