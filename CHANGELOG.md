# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.5] - 2026-11-20

> 包含自 `1.0.1` 发布以来的累积改动。

### Added

- **水中倒影品牌视觉**：Mint `#00E0A4` + Ink `#0C1417` 配色、鳍形播放符号；应用内 logo、桌面 launcher（含各 dpi PNG）、Splash、WebView 图标与 banner 全部统一为「上半实体 + 下半渐隐倒影 + 水面高光」。VectorDrawable 用递减 alpha 切片模拟渐变，WebView SVG 用真 `linearGradient`。
- **Splash 多语言 App 名称**：在系统 SplashScreen 图标内绘制白色字标（`Finplayer` / `飞影视界` / `飛影視界`），通过资源限定符切换语言；logo 与文字比例经方案对比后定稿。
- **用户菜单「关于」项**：WebView 内 MUI 用户菜单的「退出」上方新增「关于」，点击弹出深色 dialog，显示倒影 logo、本地化 App 名称与版本号；简 / 繁 / 英文案自适应，React 重渲染后自动补回。
- **侧边抽屉标题品牌化**：抽屉顶部标题始终显示 App 名称 + App 版本号，不再显示为 `Jellyfin` 或服务器名 / 服务器版本；采用显示层隔离（隐藏原 `ListItemText` + 插入固定品牌块），避免登录成功后 `useSystemInfo()` 异步更新导致跳变闪动。
- **默认服务器地址构建期注入**：地址不再硬编码在源码，改为构建期由 `local.properties` / 环境变量 / Gradle 属性注入 `BuildConfig`；新增 `local.properties.example` 模板，未配置时回退原生连接页。
- **全局 `/Items` 排序统一**：原生 OkHttp 拦截器 `ItemsSortInterceptor` 与 WebView 注入脚本 `itemsSortOverride.js` 双端改写 `sortBy`（DateCreated / ProductionYear / PremiereDate / SortName）与 `sortOrder`（Descending），并尊重用户在界面手动选择的排序（按媒体库隔离，不跨库串味）。
- **本地化 App 名桥接**：`NativeInterface.getDeviceInformation()` 输出 `localizedAppName`，WebView 注入脚本据此全局替换品牌文案，不再硬编码英文。

### Fixed

- debug 构建品牌资源未同步：`app/src/debug` 下 4 个旧覆盖文件（launcher 前景、app_logo、splash 填充色、colors）同步为 main 的新品牌资源。
- 桌面图标鱼鳍过大被 adaptive-icon 安全区裁切：逐级缩小前景缩放并居中，四周留出余量。
- 登录页标题 / 菜单 header 残留 `Jellyfin` 文案与旧「三个 F」logo：改为读取原生桥的本地化 App 名 + 新 Mint 图标。
- DLNA 进度条拖动改为「重新起播」式 seek（服务端 `startTimeTicks`），并修复：拖动后跳回原位置（`playSessionId` 复用）、`static=true` 直连档忽略起始位置、连续拖动并发会话互相覆盖、direct 档假成功降级、转码档遥测误判。
- remux-ts 档 AAC 音轨在老电视无声：按源 codec 决定是否强制转 AC3，视频仍流拷贝保画质、音轨仍保留用户选择。
- 鸿蒙设备安装报「解析包时出现问题」：排查确认 APK 签名 / SDK / manifest / dex / ZIP 结构均健康，定位为分发下载环节与设备侧限制（非代码缺陷）。

### Changed

- 版本号 `1.0.1` → `1.0.5`；Android Gradle Plugin `9.4.1` → `9.5.1`。
- 各语言 `strings.xml` / `strings_donottranslate.xml` 批量替换品牌文案；`app_name` 去掉 `translatable=false` 以支持多语言。
- `.gitignore` 增加本地工作数据与构建产物规则，避免将含私有信息的文件提交到公开仓库。

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
