# NoMusic 更新服务

公网入口为 https://nomusic.losertree.de/ 。现有 OpenResty 代理 HostHatch 的 127.0.0.1:5202，域名与 TLS 配置保持原样。新增 nomusic-updates.service 通过 systemd 启动只读 Python 服务，文件位于 /opt/nomusic-updates/site。服务使用动态用户，仅监听回环地址。

/updates/latest.json 按包名提供版本号、最低 SDK、文件大小、SHA-256 和 APK 地址；安装包位于 /downloads/。客户端只接受本站 HTTPS 下载，校验长度、哈希、包名、更高版本号和相同签名证书，然后交给系统安装器确认。旧安装器只接受文件 URI；无外部存储时仅把待安装的公开 APK 生成为随机文件名、其他应用只读的文件，所有账号与音频下载仍私有。Android 7+ 使用临时授权的 ContentProvider。

发布步骤（项目根目录，先设置 JAVA_HOME）：

```powershell
./gradlew.bat :app:assembleStandardRelease :app:assembleCs11Release :app:lintStandardRelease :app:lintCs11Release
python tools/updates/package.py --apksigner D:/Android/Sdk/build-tools/36.0.0/apksigner.bat
./tools/updates/publish.ps1 -SshHost HostHatch
```

若 SSH 未自动读取用户配置，可额外传入 `-SshConfig C:/Users/<用户名>/.ssh/config`。

发布前修改 app/build.gradle 中的版本号，以及 release-notes.txt。脚本验证暂存文件、上传 APK、确认服务健康，最后原子替换清单。已发布的 APK 不可更换内容；后续修改必须递增版本和文件名，避免代理缓存混用。package.py 检查与旧 APK 签名一致，私钥不会上传。

当前不可调试的 release 包沿用 0.1.0 测试签名，以覆盖现有安装。请保留本机 %USERPROFILE%/.android/debug.keystore；换构建机时安全迁移该签名。更换发行签名需要迁移方案，不能直接用新密钥覆盖安装。

运维：

```sh
systemctl status nomusic-updates
journalctl -u nomusic-updates --no-pager -n 30
curl -fsS http://127.0.0.1:5202/updates/latest.json
```

升级联调可用 -PnomusicVersionCode=1 -PnomusicVersionName=0.1.99 构建低版本 debug 探针，用 adb install -r -d 安装，然后运行 UpdateIntegrationTest。测试使用真实域名下载、验证和准备安装包，不改 Emby 会话与队列；探针仅用于模拟器，不得发布。0.1.0 没有更新入口，首次需手动安装新版本。
