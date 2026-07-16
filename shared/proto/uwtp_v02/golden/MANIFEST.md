# DRAFT Golden 清单 (2026-07-15 P1 首版, 字段=Registered-Draft)

| 样本 | 字节数 | wire hex | 说明 |
|---|---:|---|---|
| ctrl_state_query_req | 2 | `08 01` | STATE_QUERY 请求 query_flags=bit0 |
| ctrl_state_query_rsp_idle | 2 | `12 00` | 全 IDLE 成功响应(runtime 空子消息必须在场) |
| ctrl_state_query_rsp_busy | 6 | `12 04 08 01 18 01` | offline=CAPTURING + transfer=ACTIVE |
| ctrl_info_req | 0 | `(空)` | INFO 空请求(0 字节) |
| ctrl_info_rsp | 17 | `10 01 18 01 22 0b 08 81 20 10 01 18 81 02 20 80 02` | INFO 响应: fw=1/registry_rev=1/1 个 GOODIX_HR_RAW schema |
| ctrl_info_rsp_verstr | 16 | `10 01 18 01 2a 0a 56 31 2e 31 2e 39 39 2e 31 30` | INFO 响应带真实版本串(2026-07-16 P0: 现场验版本不依赖 J-Link) |
| minimal_error_not_supported | 2 | `08 02` | MinimalErrorRsp NOT_SUPPORTED(0x0002) |
| live_start_req | 3 | `08 80 02` | START 请求: PPG_G 裸流, 无结果 |
| live_start_rsp | 8 | `10 80 02 20 81 20 28 01` | START 成功: effective=PPG_G, schema 0x1001 rev1 |
| live_stop_req | 0 | `(空)` | STOP 空请求 |
| live_stop_rsp_ok | 0 | `(空)` | STOP 成功(0 字节=OK) |
| transfer_begin_req_first | 4 | `08 07 20 10` | 首次 BEGIN(token=0 不校验; ack_every_n=16 仅示例值) |
| transfer_begin_req_resume | 22 | `08 07 10 80 80 04 20 10 29 88 77 66 55 44 33 22 11 30 80 80 80 32` | 续传 BEGIN(带 token+size 校验) |
| transfer_begin_rsp | 21 | `10 01 18 80 80 80 32 21 88 77 66 55 44 33 22 11 38 20 40 e7 01` | BEGIN 成功: id=1/100MB/token/accepted_*(n=32,data=231) |
| transfer_ack | 6 | `08 01 10 80 80 04` | 累计 ACK: id=1, next=65536 |
| transfer_ack_win_nack | 7 | `08 03 10 e0 39 18 09` | 窗口 NACK: id=3, next=7392(窗口起点), error_code=INTEGRITY(0x0009) |
| transfer_window_end | 16 | `03 00 00 00 e0 1c 00 00 c0 39 00 00 44 33 22 11` | WINDOW_END(非 PB, 固定 16B): id=3 + start=7392 + end=14784 + crc32=0x11223344 (LE) |
| transfer_begin_req_down_fw | 11 | `20 20 30 80 80 84 01 38 01 40 01` | 下行 BEGIN: FIRMWARE, size=2162688, n=32 (file_id/token 省) |
| transfer_begin_req_down_res | 24 | `20 20 30 80 80 80 32 38 01 40 02 4a 0b 52 65 73 44 61 74 61 2e 64 61 74` | 下行 BEGIN: RESOURCE ResData.dat 100MB |
| transfer_finish_req_commit | 10 | `08 01 10 ef fd b6 f5 0d 18 01` | 下行 FINISH: id=1 + 整体 CRC32 + commit=1 (收尾激活) |
| transfer_finish_req_down_nc | 8 | `08 02 10 f8 ac d1 91 01` | 下行 FINISH: id=2 + CRC32, commit 省 (staging 不激活) |
| transfer_finish_req_crc0 | 6 | `08 03 10 00 18 01` | 下行 FINISH: 显式 CRC=0 (字段在场即校验, 非跳过) |
| transfer_error_event | 7 | `08 0a 10 01 18 b9 60` | IO_ERROR(0x000A) 事件: id=1, last_valid=12345 |
| object_list_rsp | 26 | `12 18 08 07 10 80 80 80 32 18 80 dc d6 d2 06 20 01 29 88 77 66 55 44 33 22 11` | 对象列表: 1 条 100MB 文件(含 token) |
| data_prefix_nocrc | 8 | `01 00 00 00 00 00 01 00` | DATA 前缀 crc_mode=0: transfer_id=1 + offset=65536 (LE) |
| data_prefix_crc | 12 | `01 00 00 00 00 00 01 00 ef be ad de` | DATA 前缀 crc_mode=1: id=1 + offset=65536 + frame_crc32=0xDEADBEEF (LE; 覆盖式样待专项⑤) |
