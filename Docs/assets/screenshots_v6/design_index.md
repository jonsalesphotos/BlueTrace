# V4 设计稿截图索引（design_NN.png）

由 `Docs/prototypes/v4_android.html` 按 prototype-shot skill 的定尺手机框方法批量渲染。
- 尺寸：990×2148（DPR3，宽高比 0.461 ≈ 真机 0.45）。
- 范围：实现中的 46 屏（已排除「不实现」5 屏：GNSS A/B/C、配置A 传感器总控、配置B 设备端算法）。
- ★ 标记的 3 屏内容超出固定框，已**向下加长**保留完整内容（其余严格定尺）。

| 文件 | 屏 | 备注 |
|---|---|---|
| design_01.png | 采集 · 采集 Tab 主界面 | |
| design_02.png | 场景 · 采集场景选择（主·子场景） | |
| design_03.png | 数据 · 数据 Tab 采集会话 | |
| design_04.png | 设置 · 设置 Tab | ★加长 990×2550 |
| design_05.png | 离线A · 读取 DUT 存储·已存会话 | |
| design_06.png | 离线B · 导入中（DUT→本机） | |
| design_07.png | 离线C · 分配场景/用户 | |
| design_08.png | 启动 · 启动屏 Splash | |
| design_09.png | 启动B · 权限门控·首启请求 | |
| design_10.png | 启动C · 后续启动·缺权限弹出 | |
| design_11.png | 启动D · 按需请求/权限不足处理 | |
| design_12.png | 启动E · 蓝牙已关闭（≠权限） | |
| design_13.png | 启动F · 后台省电设置指南 | |
| design_14.png | 设备A · 设备列表·单击连接/断开 | |
| design_15.png | 设备B · 连接上限（最多 3 台） | |
| design_16.png | 设备C · 扫描·无结果空态 | |
| design_17.png | 用户A · 用户选择·有列表 | |
| design_18.png | 用户B · 用户·空态 | |
| design_19.png | 用户C · 用户编辑（表单） | |
| design_20.png | 运行A · 数据采集·就绪 | |
| design_21.png | 运行B · 数据采集·采集中 | |
| design_22.png | 运行C · 采集类型选择 | |
| design_23.png | 运行D · 长按 2 秒结束（遮罩） | |
| design_24.png | 结束A · 采集结束摘要 | |
| design_25.png | 结束B · 导出完成 Toast | |
| design_26.png | 数据A · 数据空态 | |
| design_27.png | 数据B · 多选/选择态 | |
| design_28.png | 数据C · 会话详情 | ★加长 990×2805 |
| design_29.png | 数据D · 搜索无结果 | |
| design_30.png | 导出A · 导出中（ProgressDialog） | |
| design_31.png | 导出B · 导出完成 Toast | ★加长 990×2208 |
| design_32.png | 导出C · 导出失败·写入中断 | |
| design_33.png | 导出D · 存储不足（导出前预检） | |
| design_34.png | 设置A · 环境与权限检查详情 | |
| design_35.png | 设置B · 导出位置详情 | |
| design_36.png | 设置C · 存储占用详情 | |
| design_37.png | 设置D · 关于 BlueTrace | |
| design_38.png | 设置E · 应用日志（导出） | |
| design_39.png | 设置F · 设备维护（DUT） | |
| design_40.png | 设置G · 外观主题 | |
| design_41.png | 设置H · 语言 | |
| design_42.png | 横切A · 暂停·重连中 | |
| design_43.png | 横切B · 已停止（存储满） | |
| design_44.png | 横切C · 运行日志 | |
| design_45.png | 横切D · 进程恢复弹层 | |
| design_46.png | 横切E · 正在采集全局提示条 | |
