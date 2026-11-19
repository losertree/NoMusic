# NoMusic 0.6.2 验证记录

交付版本：**0.6.2 / versionCode 14**。修正了缓存检查和播放入口的音质匹配，使已经完整下载的歌曲能被识别并直接重播。

- 缓存标记按当前音质和实际可播放的副本判断，避免其他音质文件造成错误标记。
- 切换音质后，队列预缓存会重新检查并补齐所选音质缺少的文件。
- 已缓存的降级音频可继续用于重播；原始音质不受支持时，192 kbps 降级副本也可直接使用。
- 两台模拟器的播放、自动预缓存、离线和重播集成测试各 15 / 15 通过；缓存策略测试各 11 / 11 通过。
- 两台模拟器都覆盖安装 0.6.2 release，签名版检查各 1 / 1 通过，确认会话和队列保留且更新检查返回当前已是最新版。
- 两种 release 沿用 0.1.0 签名；发行包不可调试，公网清单与 APK 的包名、版本、大小和 SHA-256 已校验。

| 检查 | Android 4.1 标准版 | Android 4.4 CS11 |
| --- | --- | --- |
| 播放及缓存复用 | [15 / 15](test-results/0.6.2-api16-features.txt) | [15 / 15](test-results/0.6.2-api19-features.txt) |
| 缓存策略 | [11 / 11](test-results/0.6.2-api16-policy.txt) | [11 / 11](test-results/0.6.2-api19-policy.txt) |
| 签名版安装、数据保留和更新检查 | [1 / 1](test-results/0.6.2-api16-installed-release.txt) | [1 / 1](test-results/0.6.2-api19-installed-release.txt) |
