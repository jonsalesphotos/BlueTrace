package io.bluetrace.viewmodel

import io.bluetrace.shared.device.DeviceProfileCatalog
import io.bluetrace.shared.domain.ScannedDevice
import io.bluetrace.shared.s7.S7FirmwareUpdateFactory

/**
 * OTA 工具链(单/多设备屏)的可刷判定: 识别到的档案声明的升级工厂 == 本工具执行链所用的实现
 * ([S7FirmwareUpdateFactory], 编译期类型符号).
 *
 * 判据演进(两次修正, 语义按序收紧):
 * 1. 不锚产品名/档案名(profileId 等字符串): 名称是会变的——"S7" 只是当前型号, 未来跑同一
 *    协议的新设备(不同名字/独立档案)应照常放行; 稳定标识优先.
 * 2. 也不只锚 GATT 服务 UUID: FFE0 只证明 GATT 通道面匹配, 不证明升级编排语义兼容
 *    (zip 包格式/FILE_TRANS 指令/中止门控/"同 ID 自复位回连"都是策略级契约)——同服务但
 *    firmwareUpdate=null 或挂异构策略的未来档案必须拒绝.
 * 锚**升级能力工厂类型**两者兼得: 编译期符号不受改名影响, 且精确表达"执行链兼容".
 * 通用 OTA 按 profile.firmwareUpdate 分派(执行链不再硬编码 S7 策略)属后续任务, 见任务清单.
 */
internal fun DeviceProfileCatalog.supportsOtaTool(device: ScannedDevice): Boolean =
    identify(device)?.firmwareUpdate is S7FirmwareUpdateFactory
