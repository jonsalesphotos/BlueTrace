package io.bluetrace.shared.b2a

import io.bluetrace.shared.ble.extract16
import io.bluetrace.shared.domain.PROFILE_B2A
import io.bluetrace.shared.domain.ScannedDevice

/**
 * B2A 协议设备识别(不限 S7 一款; nRF Connect 式分层策略, spec §1):
 *
 * 1. **广播特征匹配(首选)**: 广播 Service UUID 表含 AMDTP 服务 `FFE0`
 *    (S7 实测广播 16-bit 表 = 180A + FFE0/FFE1/FFE2/FFEB, 其它 B2A 设备同服务即同命中);
 * 2. **连接后确认(兜底)**: GATT service discovery 后查 RX=`FFE1`(WriteNoRsp) + TX=`FFE2`(Notify)
 *    两特征同时存在 → 认定支持 B2A([confirmByCharacteristics]);
 * 3. 名称前缀(`SKG WATCH S7-`)仅作**展示/过滤辅助**, 不再是协议判据;
 *    名称/MAC 过滤交给扫描页用户输入(已有过滤框), 不写死.
 */
object B2aDetect {
    /** AMDTP 主数据服务(16-bit 段; 完整 128-bit base = 0000xxxx-3C17-D293-8E48-14FE2E4DA212).  */
    const val SERVICE_16 = "FFE0"
    const val RX_16 = "FFE1" // App→表 Write Without Response
    const val TX_16 = "FFE2" // 表→App Notify

    /** 128-bit 私有 base(真实 GATT 发现回来的是全串, 按 16-bit 段抽取匹配).  */
    const val UUID_BASE_SUFFIX = "-3C17-D293-8E48-14FE2E4DA212"

    /** 广播级匹配: Service UUID 表含 FFE0(大小写/128-bit 全串均容忍).  */
    fun matchesAdvertisement(device: ScannedDevice): Boolean =
        device.profileId == PROFILE_B2A || // Mock/已确认设备直接放行
            device.advertisedServices.any { extract16(it) == SERVICE_16 }

    /** 连接后确认: 已发现的特征 UUID 集合须同时含 RX(FFE1) 与 TX(FFE2).  */
    fun confirmByCharacteristics(characteristicUuids: Collection<String>): Boolean {
        val set = characteristicUuids.mapNotNull { extract16(it) }.toSet()
        return RX_16 in set && TX_16 in set
    }
}
