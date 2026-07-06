# -*- coding: utf-8 -*-
"""
gen_zqdata_wire_examples.py -- 生成 protocol-zqdata.md "实例包 decode" 章节的线格式示例帧。

字段布局全部来自代码实证(见 protocol-zqdata.md §3 各表的 file:line);
数值为构造演示值(zqdata 通道无真机抓包, 需采集固件手表; 布局与 CRC 为真)。
自校验: 用 B2A 真机产测金帧 CRC16("08 01 01")==0x462D 校验 CRC 实现与固件一致。
"""
import struct


def crc16_ccitt_false(data: bytes) -> int:
    # Poly 0x1021 / Init 0xFFFF / 不反转 / Xorout 0 (crc16_ccitt_false.c:24-42)
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if (crc & 0x8000) else (crc << 1)
            crc &= 0xFFFF
    return crc


assert crc16_ccitt_false(bytes([0x08, 0x01, 0x01])) == 0x462D, "金帧自校验失败"


def b2a_wrap(cmd: int, key: int, payload: bytes, status: int = 0x00) -> bytes:
    # B2A_MakePkt2Send 同构 (ble2appEx.c:339-425): 8B 帧头 + 4B 命令头 + payload
    inner = bytes([cmd, key]) + struct.pack("<H", len(payload)) + payload
    crc = crc16_ccitt_false(inner)  # CRC over 头后全部 (ble2appEx.c:64)
    head = bytes([0xBB, status]) + struct.pack("<HHH", len(inner), crc, 0)
    return head + inner


def hr_frame(ticks: int, ppg4: list, acc: tuple, cnt: int) -> bytes:
    # 28B 数据主体, 全大端 (hr.c:778-803)
    return (struct.pack(">I", ticks)
            + b"".join(struct.pack(">i", v) for v in ppg4)
            + struct.pack(">hhh", *acc)
            + struct.pack(">H", cnt))


def hexdump(b: bytes, width: int = 16) -> str:
    return "\n".join(
        " ".join(f"{x:02X}" for x in b[i:i + width]) for i in range(0, len(b), width)
    )


# --- 例① HR 起帧包 (Key=0x05, 应用头 {01 03 02 00}, hr.c:998-1002,1179) ---
start_pkt = b2a_wrap(0x08, 0x05, bytes([0x01, 0x03, 0x02, 0x00]))
print("=== 例① HR 起帧包 (%dB) ===" % len(start_pkt))
print(hexdump(start_pkt))

# --- 例② HR 数据包 212B (Key=0x06, 应用头 {01 03 00 04} + 7x28B, hr.c:1042-1122) ---
UTC = 0x686A9C80          # 演示 UTC 秒锚点(数值无语义, 只为展示帧0前4B=真实时间戳)
frames = []
# 帧0(全局帧序号0): 重打包保留原始前 4B = 真实 UTC (hr.c:1078-1082)
frames.append(hr_frame(UTC, [120000, 121500, 119800, 122300], (12, -8, 1024), 0))
# 帧>=1: ticks 槽已改装为 AGC 4B (hr.c:1085-1089): drv0绿1=0x2A gain绿1=0x05 drv1绿1=0x33 gain绿2=0x06
agc4 = bytes([0x2A, 0x05, 0x33, 0x06])
for i in range(1, 7):
    f = hr_frame(0, [120000 + i, 121500 + i, 119800 + i, 122300 + i], (12, -8, 1024), i)
    frames.append(agc4 + f[4:])
payload = bytes([0x01, 0x03, 0x00, 0x04]) + b"".join(frames)
assert len(payload) == 200, len(payload)
data_pkt = b2a_wrap(0x08, 0x06, payload)
assert len(data_pkt) == 212, len(data_pkt)
print("\n=== 例② HR 数据包 (%dB) ===" % len(data_pkt))
print(hexdump(data_pkt))
print("\nB2A Len16=%d CRC16=0x%04X" % (len(payload) + 4, crc16_ccitt_false(data_pkt[8:])))
