<div align="center">

<img src="docs/icon.png" width="120" alt="Matrix Music" />

# Matrix Music

**面向 NAS 的 Android 音乐播放器** — 直连 WebDAV / 飞牛 FnOS，把私有曲库装进手机。

[![Platform](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](#环境要求)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Media3-1.5.1-FF6F00)](https://developer.android.com/media/media3)
[![Version](https://img.shields.io/badge/version-2.2.0-blue)](CHANGELOG.md)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

</div>

---

## 这是什么

Matrix Music 不建索引库、不依赖服务端插件，直接用 WebDAV 协议扫你的 NAS 目录，把音频文件当成流来播。适合群晖、威联通、飞牛、Alist / OpenList 挂载的网盘，以及用 `.strm` 占位文件组织的媒体库。

所有数据都在本机：连接信息存 DataStore，曲库缓存落盘，不上传任何服务器。

## 功能

| 模块 | 说明 |
| --- | --- |
| **音源** | WebDAV（Basic / 匿名）、飞牛 FnOS 音乐（账号登录、多线路回退）；同一时刻启用一种，设置里切换 |
| **媒体库** | **纯 iPod 模拟器首页**：滚轮上方一整块「屏幕」，列表（含设置 / 歌单）全部在里面；没有底部 Dock、没有正在播放横条、没有任何点击手势，唯一交互是转轮——转动选择、中心键确认、MENU 返回。搜索用滚轮字母盘输入，不用软键盘 |
| **飞牛分类** | 风格直接读服务端 `/genre/list`；文件夹用 `audioSpec.path` 本地建树；漫游为全库随机流 |
| **扫描** | 四档节奏（温和 250ms·3 路 → 极速 0ms·32 路），STRM 场景可全速；结果落盘缓存，冷启动秒开 |
| **线路** | 主备地址自动切换；切换时直接改写索引里的地址，不重新扫描，正在播的歌也会自动换线重试 |
| **播放** | Media3 ExoPlayer，MediaSession 接入系统媒体控制与通知栏；支持 FLAC / ALAC / WAV / AAC / MP3 |
| **软解兜底** | 内置 FFmpeg 扩展（4 ABI），MediaCodec 不支持的无损格式自动降级软解 |
| **封面** | 优先用同目录的 cover 图（`cover.jpg` / `folder.png` / `front.webp` 等，省流量），目录没有才从音频文件内嵌标签提取（FLAC PICTURE / VorbisComment base64 / ID3 APIC / MP4 `covr`）；通知栏同步显示 |
| **歌词** | 优先读 metadata 内嵌（FLAC `LYRICS`、ID3 `USLT`、MP4 `©lyr`），没有才回退同目录同名 `.lrc`；播放时当前句恒定居中 |
| **播放页** | **透明玻璃 iPod 点击轮**：转动调音量（12°/格细震，无刻度线）；MENU 在本页展开队列（带封面）用滚轮选歌、中心确认；中心内凹 Home 键就地浮出进度 HUD，滚轮快进快退、再按退出；短按左右切歌、长按快退快进。背景为整张封面真高斯模糊（Apple Music 风格） |
| **交互** | 横屏直接以 CoverFlow 唱片架展示列表、滚轮切换专辑；当前播放卡片从底部升起、全量手势返回 |
| **音质** | 原采样率直通，或本地转 256kbps AAC 缓存（省流量、兼容老设备） |
| **外观** | iOS 26 风格液态玻璃外壳（悬浮 Dock + 玻璃迷你播放器），内部仍是 Material 3；10 种主题色可选，深浅色分别推导 |

## 截图

> 仓库暂未放置截图，欢迎提 PR 补充。播放页、CoverFlow、曲库列表三张即可。

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9（随 wrapper 提供） |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 21 |

## 构建

```bash
git clone https://github.com/<your-name>/MatrixMusic.git
cd MatrixMusic

# 指向本机 SDK（或直接用 Android Studio 打开，会自动生成）
cp local.properties.example local.properties
# 编辑 sdk.dir=...

./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（未配置签名时自动用 debug 签名）
```

产物：`app/build/outputs/apk/release/app-release.apk`

一键打正式交付包（编译 → 校验版本 → 源码归档 → **自动清掉旧版本，交付目录只保留最新一版**）：

```bash
./scripts/release.sh       # 交付到上级目录；可用 OUT_DIR 指定其它路径
```

### 正式签名

仓库不含任何密钥。要打可上架的包，在项目根目录建 `keystore.properties`（已加入 `.gitignore`）：

```properties
storeFile=keystore/matrix-music.jks
storePassword=******
keyAlias=matrix-music
keyPassword=******
```

`storeFile` 相对项目根目录。没有该文件时 `assembleRelease` 自动回退 debug 签名，方便 fork 直接跑通。

### 国内网络

`gradle-wrapper.properties` 默认官方源，拉不动时可换成镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
```

Maven 仓库如需镜像，改 `settings.gradle.kts` 里的 `repositories` 即可。

## 项目结构

```
app/src/main/java/com/musicbox/nas/
├── MainActivity.kt            入口、导航栈、沉浸式窗口与状态栏处理
├── MusicBoxApp.kt             Application、DataStore 配置、扫描与连接状态
├── data/
│   ├── Auth.kt                OkHttp 客户端、按主机注入认证
│   ├── Models.kt              Track 等领域模型
│   ├── LyricsRepo.kt          歌词加载：内嵌 → 同名 .lrc → 缓存
│   ├── webdav/WebDavClient.kt PROPFIND 扫描、Range 读取、STRM 解析
│   ├── fnos/FnosClient.kt     飞牛音乐接口、登录、风格分类与接口自检
│   ├── fnos/FnosCatalog.kt    飞牛全量曲目本地索引（文件夹树 / 随机漫游）
│   └── m3u/M3uParser.kt       M3U / M3U8 歌单解析
├── player/
│   ├── PlaybackService.kt     MediaSessionService
│   ├── PlayerController.kt    播放控制、队列、封面推送、进度订阅
│   ├── PlayerHolder.kt        Application 级播放器持有者
│   └── AacTranscoder.kt       AAC 转码与缓存
├── scan/ScanService.kt        前台服务承载全库扫描
├── ui/
│   ├── components/            封面缓存（带 LRU 与重试）、迷你播放器
│   ├── screens/               曲库 / 播放页 / 歌单 / 设置
│   └── theme/                 Material3 主题
└── util/
    ├── AudioTags.kt           FLAC / ID3v2 / MP4 标签与封面解析
    ├── MediaHead.kt           分段 Range 预读文件头
    ├── CrashLogger.kt         本地日志与导出
    └── Utils.kt               杂项
```

设计取舍写在 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 已知限制

- **明文 HTTP**：为兼容局域网 NAS，`usesCleartextTraffic=true` 且信任用户证书，公网部署请自行上 TLS。
- **凭证本地保存**：连接配置（含密码）以明文存于应用私有 DataStore，未做加密；共用设备请勿保存密码。
- **STRM 依赖服务端**：`.strm` 文件内容需是可直连的 HTTP 地址，播放时不做二次解析。
- **仅一种音源同时生效**：WebDAV 与飞牛互斥，切换会重建曲库。
- **未开启混淆**：`isMinifyEnabled=false`，便于排查问题；正式发布可自行开启并补 keep 规则。

## 参与贡献

Issue / PR 都欢迎。提交前请确保 `./gradlew assembleDebug` 通过；涉及 UI 的改动附截图更好。

## License

[MIT](LICENSE) © Contributors
