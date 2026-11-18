# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.1] - 2026-11-18

### Added

- **DLNA 投屏**：自动发现局域网 MediaRenderer 电视，与 Google Cast 合并为统一投屏入口，并行搜索设备。
- **投屏兼容阶梯**：按 `direct → remux-ts → H.264 多档转码` 顺序自动降级，每档通过 UPnP（`GetTransportInfo` + `GetPositionInfo`）做真实起播校验；对 MKV、多音轨、HEVC/HDR 等老电视不支持的片源自动跳过直连，优先走画质无损的流拷贝 remux。
- **音轨保真**：投屏 URL 携带 `audioStreamIndex`，保留手机端选中的音轨。
- **投屏控制**：投屏中常驻控制框（暂停/继续/停止）；结束投屏后按电视端进度在本地续播。
- **Finplayer 品牌化**：启动 logo、应用图标（各 dpi）、WebView 内 logo 替换为自有图标。
- **PGS/VobSub 字幕**：WebView 端默认启用实验性图形字幕渲染（用户已手动设置的保持不变）。

### Fixed

- 转码冷启动返回 500 / HTTP 200 空体：新增 warm-up 预热与同档重试，避免误降级到低画质档。
- 老电视卡在"正在获取投屏内容信息"：续播改用服务器端 `startTimeTicks` 而非 SOAP Seek；卡住时快速降级；降档之间不再发送 `Stop`（老电视会把 Stop 当作整个投屏结束）。
- 起播校验误判：连续 `PLAYING` 但位置遥测恒为 0 的电视不再被误判为卡住而反复重播；同时保留"位置冻结在 seek 目标"的假成功识别。
- 结束投屏后跳到片尾：`GetPositionInfo` 改读 `RelTime`（当前进度）而非 `TrackDuration`（总时长），并对续播位置做时长钳制。
- 投屏后音轨变英文：多音轨源不再走 `static=true` 直连（Jellyfin 会忽略 `audioStreamIndex`），改用带音轨参数的 remux。
- UI：投屏相关提示框统一为圆角半透明背景、文字居中；修复 Android 12 启动 logo 两侧被裁切的问题。
- 构建：禁用 Gradle problems HTML 报告，规避外置 APFS 卷上跨路径移动失败导致的构建报错。

### Changed

- 版本号统一由 `gradle.properties` 的 `jellyfin.version` 管理（`1.0.1`，对应 `versionCode 1000199`）。
- Gradle 发行版下载源切换为腾讯云镜像。
- 投屏实现按 `proprietary` / `libre` 风味拆分，公共逻辑通过 `ICastPlayerFactory` 接口隔离，DLNA 在两种风味下均可用且不依赖 GMS。
