# 用 Termux 部署 ShizuLog

1. 把本压缩包下载到手机并解压，或在 Termux 中执行 `termux-setup-storage` 后使用 `unzip` 解压。
2. 进入 `ShizuLog` 目录。
3. 执行：

```bash
chmod +x termux-deploy.sh
./termux-deploy.sh
```

脚本会安装 `git` 与 `gh`，让你通过 GitHub 网页完成授权，然后把源码推送到 `opfans432/ShizuLog`。随后脚本会等待 GitHub Actions 构建并发布 `v1.0.0`，成功后把 APK 下载到手机 Download/ShizuLog-release 目录（若 Termux 未获得共享存储权限，则保存到 `$HOME/ShizuLog-release`）。
