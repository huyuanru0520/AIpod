# 架构与设计取舍

这份文档记录几个关键模块为什么这样做，方便后来者改代码前先知道坑在哪。

## 1. 全局状态：Application 级 StateFlow，不引 ViewModel 树

`MusicBoxApp` 持有连接、曲库、扫描进度、主题等 `MutableStateFlow`，Compose 直接 `collectAsState()`。
原因：曲库和播放器都是进程级单例，做成 ViewModel 反而要在多个 NavHost 页面间共享，收益为负。

持久化统一走 DataStore Preferences（`musicbox_prefs`），写入口集中在 `MusicBoxApp` 的几个 `save*()` 方法里，避免各处散落 `edit {}`。

## 2. 扫描：并发 + 自适应节流

WebDAV 用 PROPFIND 递归，目录内条目用 `async` 并行建 Track。
四个节奏档位控制两个参数：

| 档位 | 最小请求间隔 | 并发 |
| --- | --- | --- |
| 温和 | 250 ms | 3 |
| 均衡（默认） | 120 ms | 6 |
| 快速 | 50 ms | 12 |
| 极速 | 0 ms（不排队） | 32 |

除极速外，`WebDavClient` 会在连续成功后逐步收窄间隔，遇到 429/503 则退避放大——匀速排队既压不住突发，也太慢。
极速档是为 `.strm` + 自建 OpenList 这类没有网盘风控的场景准备的。

STRM 只解析文件内容里的直链，不做二次跳转；扫描时按目录缓存 cover 图 URL，避免每首歌都去读文件头。

## 3. 封面：两级缓存 + 按 key 订阅

```
CoverStore.request(key)
  ├─ LruCache<String, Bitmap> 64MB      位图本体
  ├─ SnapshotStateMap ready/failed       Compose 订阅点
  └─ 失败分级：确定性失败 10 分钟内不重试；临时失败 8 秒后重试 2 次
```

早期版本用一个全局 `version` 计数器通知刷新，任何一张封面加载完都会让整屏重组。
现在 `readyCovers[key]` 是 `SnapshotStateMap`，Compose 只在读取对应 key 时订阅，列表滚动不互相牵连。
键区分「目录 cover 图」和「文件内嵌封面」两个前缀，同一首歌两种来源各自缓存、互不覆盖。

取图顺序：目录 cover 图 → 文件内嵌标签。前者一次 HTTP GET，后者要 Range 读文件头再解 FLAC/ID3/MP4 结构，能省则省。

## 4. 播放：Media3 + 本地转码

`PlaybackService` 是标准 `MediaSessionService`，`PlayerController` 包一层业务方法（队列、循环模式、AAC 转码）。
`AacTranscoder` 把下一首提前转成 256k AAC 落到缓存目录，播完直接换源，避免切歌时的空窗。

通知栏封面通过 `MediaMetadata.artworkData` 推送。列表侧封面加载成功后会把字节回灌给通知，避免同一次播放重复下载两次。

## 5. 导航与返回

所有返回逻辑集中在一个 `BackHandler`，按优先级判断：

```
播放页浮层 → 详情栈（专辑/歌手/文件夹）→ CoverFlow → 普通返回
```

文件夹用路径栈而不是嵌套 NavHost，`active` 参数控制内层 `BackHandler` 是否生效——否则多个页面同时注册，手势会直接退出 App。

列表滚动位置用 `rememberSaveableStateHolder` 按 `root` / `tab_*` / `d<depth>_<hash>` 分层保存，进详情再返回停在原位。

## 6. 窗口：edge-to-edge 的顺序陷阱

沉浸式的坑集中在 `MainActivity.applyImmersive()`：

- 所有会触发窗口重排的属性（`addFlags`、`layoutInDisplayCutoutMode`、`statusBarColor`、背景色）必须**前置**，
  `WindowInsetsControllerCompat.hide()` 必须**最后**执行。反过来写，系统在应用属性时会重新显示系统栏，`hide()` 的结果被顶回去。
- 转屏后 inset 会短暂归零再恢复，用 `setOnApplyWindowInsetsListener` 做自愈（400 ms 节流）。
- 150 / 500 / 1000 ms 三档延迟补一次 `hide()`，覆盖部分 ROM 的慢速动画。
- 系统栏强制透明，避免 `FLAG_LAYOUT_NO_LIMITS` 下露出的白/黑色块。

普通页面用 `statusBarsPadding()` + `navigationBarsPadding()` 让内容避开，播放页自己铺满。

## 7. 性能

- **播放进度订阅下沉**：`position` 只在 SeekBar 和歌词组件内部 `collect`，不上升到整页，避免每秒重组整棵 UI。
- **减少 `blur()`**：背景用的是 24×24 缩略图拉伸 + 半透明遮罩，全屏模糊在低端机上掉帧明显。
- **`derivedStateOf`**：歌词当前行、列表分组用派生状态，只在结果变化时才触发重组。
- **轮询降频**：播放中 500 ms、暂停 1500 ms，够用且省电。
- **动画克制**：页面切换 220–260 ms，只动 `alpha` / `translation`，不动布局尺寸。

## 8. 日志

`CrashLogger` 分等级写本地文件，关闭时连 Logcat 都不打。排查线上问题靠设置页「导出日志」。
标签约定：`Immersive`（窗口）、`Cover`（封面）、`Artwork`（通知封面）、`Lyrics`（歌词）、`FnOS`（飞牛登录与接口）。
