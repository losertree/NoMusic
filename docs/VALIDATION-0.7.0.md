# NoMusic 0.7.0 验证记录

验证日期：2026-11-24 至 2026-11-25。运行环境：现有 Android 4.4 / API 19 x86 模拟器，1280 × 720 px / mdpi。测试用本地 Emby fixture，保留并恢复设备原账号、队列与设置。正式包为 versionCode 15、versionName 0.7.0（CS11 带 `-cs11` 后缀）。

## 构建

- CS11 debug / AndroidTest、CS11 release、standard release 构建通过。
- CS11 debug、CS11 release、standard release Lint 均通过，无错误；保留旧系统目标、Java 8 和既有项目的非阻断警告。
- 两种正式包的 APK 签名验证通过，包含 Android 4.x 所需的 v1 签名，沿用已有安装签名。
- 已在模拟器覆盖安装 CS11 正式包；安装后确认不可调试、版本正确、原账号 / 队列 / 进度保留、恢复时保持暂停。

## 自动化测试

| 套件 | 结果 | 记录 |
| --- | --- | --- |
| 组件与发音 | 9 / 9 | [原始输出](test-results/0.7.0-api19-widgets-karaoke.txt) |
| 既有集成 | 13 / 13 | [原始输出](test-results/0.7.0-api19-base.txt) |
| 功能集成 | 15 / 15 | [原始输出](test-results/0.7.0-api19-features.txt) |
| 界面场景 | 19 / 19 | [原始输出](test-results/0.7.0-api19-ui.txt) |
| 已安装正式包 | 1 / 1 | [原始输出](test-results/0.7.0-api19-installed-release.txt) |

共 57 项通过。最终调试包的四个套件运行时，原生 Launcher2 中已实际添加竖版 NoMusic 组件；组件随真实播放服务获取 fixture 歌曲和歌词。

新增用例验证了粤语简繁体 / 词组读音、日语汉字 / 助词 / 拗音 / 促音 / 长音、韩语常见连音与音变、自动识别不会把汉字猜成粤语、缓存落盘及重新读取、取消旧回调、账号与内容隔离、间奏空白时间点、偏移与单句范围、翻译和发音独立显隐。组件的三种布局在六种尺寸实际执行 RemoteViews 动作并测量，检查触控区及可见文字的边界。

真实点击测试验证了组件播放 / 暂停 / 下一首和 K 歌 PendingIntent；界面测试验证了整宽跟唱、单句循环、不改变列表循环设置、退出后取消练习，以及返回桌面继续播放。常规套件覆盖旧歌词、队列、分页、缓存、网络恢复、登录失效和原有 UI。

## 界面检查

- [原生桌面实际组件](screenshots/widget-desktop-0.7-fixture.png)：fixture 正在后台播放，当前歌词和暂停按钮已更新。
- [竖版](screenshots/widget-tall-0.7-fixture.png)、[卡片](screenshots/widget-card-0.7-fixture.png)、[控制条](screenshots/widget-compact-0.7-fixture.png)：在 Android RemoteViews 中真实渲染，使用测试歌词和测试歌手。
- [日语 K 歌](screenshots/karaoke-japanese-0.7-fixture.png)：汉字歌词与本地生成的罗马音同屏显示。
- 另检查了 280 × 320 dp 竖版、280 × 240 dp 卡片、280 × 64 dp 控制条：次要信息按高度收起，控制按键保留至少 48 dp 触控区。

## 范围与限制

本轮运行验证为 API 19；API 16 保持最低 SDK 与兼容检查，本轮没有可运行的 API 16 模拟器，不能视为完成 API 16 运行回归。CS11 原厂桌面是否提供标准 AppWidget 入口、实际网格及可用高度需实车确认；标准组件不能代替原厂私有卡片协议。

发音为本地自动转写，特殊人名、多音字和演唱读音可能不同于词典。逐词高亮依赖原歌词逐词时间，纯文本没有同步或单句循环。此次跟唱范围未包含录音或人声分离。0.7.0 已生成本地正式 APK，更新站的公开发布版本仍为 0.6.2。
