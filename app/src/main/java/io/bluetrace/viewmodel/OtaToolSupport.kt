package io.bluetrace.viewmodel

import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.domain.ScannedDevice

/**
 * OTA 工具链(单/多设备屏)当前绑定的协议 = B2A 指令集, 以 **GATT 主服务 UUID** 判定设备可刷.
 *
 * 判定**不用设备名/档案名**(profileId 等): 名称是会变的——"S7" 只是当前型号名, 未来跑同一
 * B2A 协议的新设备(不同名字/独立档案)应照常放行, 异构协议设备无论叫什么都应拒绝.
 * GATT 服务 UUID 是协议的稳定标识, 与产品命名解耦.
 *
 * 执行链(zip 包 loader/FILE_TRANS 指令集/升级策略)当前同样绑定 B2A;
 * 通用 OTA 按 profile 分派(loader/策略/读数一体化)属后续任务, 见任务清单.
 */
internal const val OTA_TOOL_SERVICE_16 = "FFE0"

/** 设备是否可进 OTA 工具链: 识别到的档案声明的 GATT 主服务 == B2A 服务(忽略大小写). */
internal fun DeviceProfileCatalog.supportsOtaTool(device: ScannedDevice): Boolean =
    identify(device)?.gattSpec?.serviceUuid16.equals(OTA_TOOL_SERVICE_16, ignoreCase = true)
