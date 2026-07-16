# UWTP v0.2 协议快照(App 侧唯一真源镜像)

> 同步时间: 2026-07-16 · 目标基线: 固件仓 apollo4_watch_s7 **提交 f480d804**
> (feat(uwtp): P0——下行 FINISH 强制整体 CRC + INFO 返回真实版本串)

## 真源偏差声明(审计必读)

- 固件仓的 `Docs/proto_v02/` **从未被 git 提交**(untracked, 全历史零记录)——"从提交 f480d804
  同步"字面不可行, 本快照取自**固件仓工作区磁盘副本**(2026-07-16), 并以 **f480d804 已提交的
  C 代码**(collect_link_transfer.c 等)为裁判做了排除与核对;
- **排除 2 个 golden**(HEAD dc431e16 的 crc_mode=2/WINDOW_END 未提交特性, f480d804 C 中
  `WINDOW_END|0x0A` 零命中): `transfer_window_end.hex` / `transfer_ack_win_nack.hex`;
- 任务说明书预期 23 个 golden, 实取 **24 个 hex**: 差异为 `data_prefix_crc.hex`(crc_mode=1
  DATA 前缀布局)——它在旧 18 快照中即存在且有测试覆盖, App 请求仍恒用 crc_mode=0,
  保留该样本仅锁布局不代表启用;
- `MANIFEST.md` 按固件侧原样收录(其中含被排除两样本的行, 以本 README 排除声明为准)。

## 文件清单与 SHA256(前 16 位)

| 文件 | sha256/16 |
|---|---|
| `uwtp_common.proto` | `3a83861e09c329a8` |
| `uwtp_ctrl.options` | `353f5d9091d605b1` |
| `uwtp_ctrl.proto` | `bf1a9db616a36ba3` |
| `uwtp_live.options` | `1bd62c80da679ac7` |
| `uwtp_live.proto` | `44e45b1720232b18` |
| `uwtp_transfer.options` | `52af30252ed07c2b` |
| `uwtp_transfer.proto` | `ae48673ec1b51961` |
| `golden/ctrl_info_req.hex` | `85b17bf987e8cebd` |
| `golden/ctrl_info_rsp.hex` | `8fa3b70a6a542205` |
| `golden/ctrl_info_rsp_verstr.hex` | `2387aad253e507e2` |
| `golden/ctrl_state_query_req.hex` | `8d23321a6d9498ee` |
| `golden/ctrl_state_query_rsp_busy.hex` | `2d667d280cceebc6` |
| `golden/ctrl_state_query_rsp_idle.hex` | `f9fba85e2d7237b6` |
| `golden/data_prefix_crc.hex` | `b6da3ed9744f95a4` |
| `golden/data_prefix_nocrc.hex` | `b980b98378453665` |
| `golden/live_start_req.hex` | `b1984f38877cf817` |
| `golden/live_start_rsp.hex` | `acc2de995d2f2b6b` |
| `golden/live_stop_req.hex` | `85b17bf987e8cebd` |
| `golden/live_stop_rsp_ok.hex` | `85b17bf987e8cebd` |
| `golden/minimal_error_not_supported.hex` | `b97112741b7e7149` |
| `golden/object_list_rsp.hex` | `4f34357eaaa03e1a` |
| `golden/transfer_ack.hex` | `33d728aae2f17fb6` |
| `golden/transfer_begin_req_down_fw.hex` | `5d85b7caceb62981` |
| `golden/transfer_begin_req_down_res.hex` | `5c293da630f0618c` |
| `golden/transfer_begin_req_first.hex` | `4ddb854df84d2dba` |
| `golden/transfer_begin_req_resume.hex` | `1ac760966a3ef93f` |
| `golden/transfer_begin_rsp.hex` | `809fe1d2998d2982` |
| `golden/transfer_error_event.hex` | `486b4833ae67a9ca` |
| `golden/transfer_finish_req_commit.hex` | `6af760472d0ffd3a` |
| `golden/transfer_finish_req_crc0.hex` | `5a5f17c58ac31fa8` |
| `golden/transfer_finish_req_down_nc.hex` | `cb97f0c596bdbb6c` |
| `golden/MANIFEST.md` | `dea3c5f419f7f77d` |
