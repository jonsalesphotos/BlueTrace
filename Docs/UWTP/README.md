# UWTP · 统一可穿戴传输协议（Unified Wearable Transport Protocol）

> 协议家族独立目录（2026-07-06 建）。UWTP 是跨项目的 BLE 设备传输协议标准；S7/BlueTrace 只是其第一个落地产品线。
> **当前基线 = V0.99（冻结候选）**，双端按文档静态注册表共识开发，联调通过后冻结 V1.0（线上 `protocol_version = 1`）。

## 文件索引

| 文件 | 角色 | 状态 |
| --- | --- | --- |
| [`UWTP_BLE_Protocol_Design_V0.99.md`](UWTP_BLE_Protocol_Design_V0.99.md) / [`.html`](UWTP_BLE_Protocol_Design_V0.99.html) | **协议正文**：Core（5B 头/GATT 绑定/时间模型/断连语义/并发矩阵/安全姿态）+ 六域（CTRL/ALGO/LOG/OTA/FILE/TUNNEL）+ 示例帧/注册表；D-1~D-14 审议决策表在册 | ✅ 冻结候选 |
| [`uwtp_v0.99_draft.proto`](uwtp_v0.99_draft.proto) | 机器可读契约（全部 Protobuf 消息） | ✅ 随正文 |
| [`UHTP_BLE_Protocol_Design_V4.md`](UHTP_BLE_Protocol_Design_V4.md) | **前身**（UHTP V4）：5B 头 + 事务域骨架的原始收敛稿，V0.99 的继承来源 | 📦 历史，勿再引用为基线 |
| [`assets/gen_uwtp_examples.py`](assets/gen_uwtp_examples.py) | 示例帧生成脚本（5B 头 + protobuf wire + CRC32 实算，len 自洽断言） | ✅ 可复跑 |

## 关联（目录外）

- **S7 采集 Profile**（待改写）：[`../architecture/s7/protocol-zqdata-uhtp-v1.md`](../architecture/s7/protocol-zqdata-uhtp-v1.md)——基于前身 UHTP V4 的旧稿，将按 V0.99 §22 待办 1 收敛为一页 Profile（选域 + 填注册表 + S7 GATT 绑定 + legacy 双栈迁移）。
- **现网协议（迁移对象）**：[`../architecture/s7/S7协议共识规格.md`](../architecture/s7/S7协议共识规格.md)（B2A + 采集固件 DC/ZQDATA，全字段溯源固件代码）。
- 参照系：Zephyr MCUmgr/SMP img_mgmt（OTA 语义对齐，见正文 §14.6 映射表）。

## 演进路线

```
UHTP V4（骨架收敛） → UWTP V0.99（补全审议定稿, 当前） → 固件评审 → 双端金帧联调 → V1.0 冻结
                                                        └→ S7 采集 Profile（第一个产品剖面）
```
