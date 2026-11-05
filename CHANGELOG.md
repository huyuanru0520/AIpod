# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

<!-- 推到自己的仓库后，把文末 compare 链接里的 your-name 替换成实际 GitHub 用户名；或直接删掉链接定义，改为纯文本标题。 -->

## [1.1.1] - 2025-11-05

### Changed
- 全局动效重做：页面切换横推 220ms、播放页底部升起 260ms、封面淡入、迷你播放器滑入。
- 播放进度订阅下沉到 SeekBar 与歌词组件，避免每秒重组整页。
- 封面缓存改为按 key 订阅的 `SnapshotStateMap`，滚动列表不再被单张封面牵连。
- 背景封面用低分辨率拉伸替代全屏 `blur()`，中低端机掉帧明显减少。
- 播放状态轮询降频（播放 500ms / 暂停 1500ms）。

## [1.1.0] - 2025-11-04

### Changed
- 播放页重构：封面与歌词整合为一屏，滚动歌词时当前句恒定居中。
- 单曲循环 / 随机播放 / 列表循环移入曲库页，播放页底部只保留上一首、播放、下一首。

## [1.0.44] - 2025-11-04

### Fixed
- 文件夹层级支持手势返回，不再直接退回桌面。

## [1.0.43] - 2025-11-03

### Fixed
- 顶部黑色区域：窗口属性重排会把 `hide()` 顶回，改为属性前置、`hide()` 最后执行，并补 insets 自愈与延迟补 hide。

## [1.0.42] - 2025-11-03

### Changed
- edge-to-edge 常驻，系统栏强制透明，作为顶部黑区的兜底方案。

## [1.0.41] - 2025-11-03

### Fixed
- 从歌曲列表进入播放页顶部残留黑区。
- 所有列表从详情返回后保留原滚动位置。

## [1.0.40] - 2025-11-02

### Fixed
- 部分歌曲封面读取不到：补齐 VorbisComment 中 base64 编码的 `METADATA_BLOCK_PICTURE`，并支持头部续读。

## [1.0.39] - 2025-11-02

### Changed
- 播放页封面去掉边框，按原始比例展示，不再出现黑边。
- 歌词优先读文件内嵌 metadata，没有才回退同目录同名 `.lrc`。

### Fixed
- FLAC / ID3 / MP4 内嵌封面解析失败导致封面缺失。

## [1.0.38] - 2025-11-01

### Fixed
- 播放时当前歌词句精确居中。
- 浅色模式下歌词对比度过低。
- 飞牛音乐状态栏不显示专辑封面，只显示应用图标。

## [1.0.37] - 2025-11-01

### Fixed
- CoverFlow 左滑退出后顶部出现白色色块；横屏扫描时同样出现。

### Added
- 新增「极速」扫描档位（0 间隔 / 32 路并发），面向 STRM 与自建 OpenList。

## [1.0.36] - 2025-10-31

### Changed
- 横屏 CoverFlow 左右滑动改为退出 CoverFlow，而非退出应用。
- 点击「当前播放」改为卡片式从底部升起，返回手势缩回原位。

## [1.0.35] - 2025-10-31

### Fixed
- 深色模式顶部状态栏出现白条。

### Changed
- 应用名改为 **Matrix Music**。

## [1.0.x] - 2025-10

初始版本：WebDAV / 飞牛 FnOS 音源接入、全库扫描与缓存、Media3 播放与通知栏控制、歌词与封面解析、CoverFlow 浏览。

[1.1.1]: https://github.com/your-name/MatrixMusic/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/your-name/MatrixMusic/compare/v1.0.44...v1.1.0
[1.0.44]: https://github.com/your-name/MatrixMusic/compare/v1.0.43...v1.0.44
[1.0.43]: https://github.com/your-name/MatrixMusic/compare/v1.0.42...v1.0.43
[1.0.42]: https://github.com/your-name/MatrixMusic/compare/v1.0.41...v1.0.42
[1.0.41]: https://github.com/your-name/MatrixMusic/compare/v1.0.40...v1.0.41
[1.0.40]: https://github.com/your-name/MatrixMusic/compare/v1.0.39...v1.0.40
[1.0.39]: https://github.com/your-name/MatrixMusic/compare/v1.0.38...v1.0.39
[1.0.38]: https://github.com/your-name/MatrixMusic/compare/v1.0.37...v1.0.38
[1.0.37]: https://github.com/your-name/MatrixMusic/compare/v1.0.36...v1.0.37
[1.0.36]: https://github.com/your-name/MatrixMusic/compare/v1.0.35...v1.0.36
[1.0.35]: https://github.com/your-name/MatrixMusic/compare/v1.0.0...v1.0.35
