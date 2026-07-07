# -*- coding: utf-8 -*-
"""ZQDATA/UHTP V1 设计稿示例包生成器 (注: 已被 UWTP V0.99-r2 取代, 布局以 UWTP 为准).
5B 头 + protobuf wire 编码 + CRC32(IEEE) 全部实算; 自带解码断言.
"""
import struct, zlib, datetime

# ---- 5B header ----
def hdr(ver_flags, main_t, sub_t, seq, payload: bytes) -> bytes:
    assert len(payload) <= 238
    return bytes([ver_flags, main_t, sub_t, seq, len(payload)]) + payload

VF = 0x10          # header_ver=1, SINGLE
VF_RSP = 0x14      # + NEED_RSP

# ---- protobuf wire ----
def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F; v >>= 7
        out.append(b | 0x80 if v else b)
        if not v: return bytes(out)

def fv(f, v): return varint((f << 3) | 0) + varint(v)
def fb(f, b): return varint((f << 3) | 2) + varint(len(b)) + b

def hexs(b): return ' '.join(f'{x:02X}' for x in b)
W = 78
UTC = int(datetime.datetime(2026, 7, 6, 8, 0, 0, tzinfo=datetime.timezone.utc).timestamp())

# ============ E1: CTRL/TIME_SYNC (固定二进制) ============
t0 = 1_750_000_000_000_000   # app 发送时刻 us (示例)
t1 = t0 + 12_000             # 设备收
t2 = t1 + 800                # 设备回
ping = hdr(VF_RSP, 0x01, 0x05, 0x21, bytes([1]) + struct.pack('<Q', t0))
pong = hdr(VF,     0x01, 0x05, 0x21, bytes([1]) + struct.pack('<QQQ', t0, t1, t2))
print('=' * W); print('E1  CTRL/TIME_SYNC ping/pong'); print('=' * W)
print(f'ping ({len(ping)}B): {hexs(ping)}')
print(f'pong ({len(pong)}B): {hexs(pong[:24])} ...')
print(f'     t0={t0} t1={t1} t2={t2}')

# ============ E2: CTRL/USER_PROFILE 写入 ============
up = (fv(1, 175)      # height_cm
    + fv(2, 705)      # weight_kg_x10
    + fv(3, 1)        # sex = 1 male
    + fv(4, 1993) + fv(5, 6) + fv(6, 15))   # birth
req = hdr(VF_RSP, 0x01, 0x09, 0x22, up)
rsp = hdr(VF, 0x01, 0x09, 0x22, b'')        # CommonRsp{status=0} -> proto3 空
print('=' * W); print('E2  CTRL/USER_PROFILE'); print('=' * W)
print(f'req ({len(req)}B): {hexs(req)}')
print(f'rsp ({len(rsp)}B): {hexs(rsp)}   <- status=0 缺省不编码')

# ============ E3: CTRL/ALGO_CTRL 开结果上传 + ALGO/REPORT_TLV ============
ac = fb(1, varint(1) + varint(2)) + fv(2, 1000)   # enable_ids=[1,2] packed, min_interval_ms=1000
req3 = hdr(VF_RSP, 0x01, 0x04, 0x23, ac)
hr_val   = fv(1, 72) + fv(2, 90) + fv(3, 80)                       # HrResultV1
spo2_val = fv(1, 98) + fv(2, 72) + fv(3, 85) + fv(5, 1500)         # Spo2ResultV1 (pi_x1000)
def record(algo_id, delta, flags, val):
    return bytes([algo_id, delta, flags, len(val)]) + val
bundle = (bytes([5]) + struct.pack('<I', 1234567) + bytes([2])
        + record(1, 0, 0x03, hr_val)
        + record(2, 10, 0x03, spo2_val))
rpt = hdr(VF, 0x02, 0x02, 0x36, bundle)
print('=' * W); print('E3  ALGO_CTRL enable + REPORT_TLV bundle'); print('=' * W)
print(f'algo_ctrl req ({len(req3)}B): {hexs(req3)}')
print(f'report ({len(rpt)}B): {hexs(rpt)}')
print(f'  bundle hdr: seq=5 base_time_ms=1234567 count=2')
print(f'  rec1: algo=1 dt=0 flags=03 len={len(hr_val)} val={hexs(hr_val)}')
print(f'  rec2: algo=2 dt=10 flags=03 len={len(spo2_val)} val={hexs(spo2_val)}')

# ============ E4: FILE 离线回传全流程 ============
# 合成 46080B 会话文件, CRC32(IEEE)
fdata = bytes((i * 13 + 7) & 0xFF for i in range(46080))
fcrc = zlib.crc32(fdata) & 0xFFFFFFFF
print('=' * W); print(f'E4  FILE 离线回传 (file=46080B CRC32=0x{fcrc:08X})'); print('=' * W)

cat_req = hdr(VF_RSP, 0x05, 0x01, 0x40, fv(2, 8))    # page=0 缺省, page_size=8
meta = (fv(1, 7)            # session_id
      + fv(2, 0x01)         # kinds = HR
      + fv(3, UTC)          # start_utc
      + fv(4, 300)          # duration_s
      + fv(5, 46080)        # size_bytes
      + fv(6, 1152)         # frame_count
      + fv(7, 0x02)         # content_format = PPGHR40
      + fv(8, fcrc))        # crc32
cat_rsp = hdr(VF, 0x05, 0x01, 0x40, fv(2, 1) + fb(4, meta))   # total=1, sessions[]
print(f'CATALOG req ({len(cat_req)}B): {hexs(cat_req)}')
print(f'CATALOG rsp ({len(cat_rsp)}B): {hexs(cat_rsp)}')

rb = fv(1, 7) + fv(3, 230) + fv(4, 16)               # session=7, resume=0缺省, chunk=230, win=16
read_begin = hdr(VF_RSP, 0x05, 0x02, 0x41, rb)
rr = (fv(2, 1)            # transfer_id
    + fv(3, 46080)        # total_bytes
    + fv(4, 230)          # chunk_bytes
    + fv(5, 16)           # ack_window_chunks
    + fv(6, fcrc)         # file_crc32
    )                     # next_offset=0 缺省
read_ready = hdr(VF, 0x05, 0x03, 0x41, rr)
print(f'READ_BEGIN ({len(read_begin)}B): {hexs(read_begin)}')
print(f'READ_READY ({len(read_ready)}B): {hexs(read_ready)}')

d0 = hdr(VF, 0x05, 0x04, 0x00, struct.pack('<II', 1, 0) + fdata[0:230])
d1 = hdr(VF, 0x05, 0x04, 0x01, struct.pack('<II', 1, 230) + fdata[230:460])
ack = hdr(VF, 0x05, 0x05, 0x50, struct.pack('<II', 1, 3680) + bytes([0]))
print(f'READ_DATA#0 ({len(d0)}B): {hexs(d0[:24])} ...   hdr+tid=1,off=0,data230B')
print(f'READ_DATA#1 ({len(d1)}B): {hexs(d1[:24])} ...   off=230, seq=0x01 递增')
print(f'READ_ACK    ({len(ack)}B): {hexs(ack)}   <- App 每 16 片授一次, next_offset=3680')

re_ = fv(1, 1) + fv(3, 46080)                        # transfer_id=1, reason=0缺省, sent=46080
read_end = hdr(VF, 0x05, 0x06, 0x52, re_)
del_req = hdr(VF_RSP, 0x05, 0x08, 0x53, fv(1, 7))
print(f'READ_END   ({len(read_end)}B): {hexs(read_end)}')
print(f'DELETE req ({len(del_req)}B): {hexs(del_req)}')

# ============ E5: TUNNEL 透传 ============
goodix = bytes([0xAA, 0x11, 0x02, 0x00, 0x5A, 0x01])   # 示例: 汇顶协议字节原样
tun = hdr(VF, 0x06, 0x01, 0x60, goodix)
print('=' * W); print('E5  TUNNEL/GH3220_EVK 透传 (payload 不透明)'); print('=' * W)
print(f'frame ({len(tun)}B): {hexs(tun)}')

# ============ E6: 心跳 ============
hb = fv(1, 3600) + fv(2, 3980) + fv(3, 0x01)          # uptime_s, battery_mv, flags(bit0=charging)
hb_pkt = hdr(VF, 0x01, 0x06, 0x70, hb)
print('=' * W); print('E6  CTRL/HEARTBEAT'); print('=' * W)
print(f'frame ({len(hb_pkt)}B): {hexs(hb_pkt)}')

# ---- 尺寸/回验断言 ----
for f in (ping, pong, req, rsp, req3, rpt, cat_req, cat_rsp, read_begin, read_ready, d0, d1, ack, read_end, del_req, tun, hb_pkt):
    assert f[4] == len(f) - 5 and len(f) <= 243
assert d0[5:9] == struct.pack('<I', 1) and d0[9:13] == struct.pack('<I', 0)
print('\nall frames: len 自洽, <=243B OK; UTC =', UTC)
