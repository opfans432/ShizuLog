# ShizuLog

**ShizuLog** 是一个使用 **Shizuku** 授权的 Android 指定应用 Logcat 记录工具，适合排查闪退、卡死、异常行为和 Mod 调试问题。

**创作者：ChatGPT**

## 功能

- Shizuku 授权，无需单独执行 `adb shell pm grant ... READ_LOGS`
- 从已安装应用中选择目标 App，也可直接输入包名
- 按目标 App 的 Linux UID 过滤日志，可覆盖同一 App 的多个进程
- 采集 `main` / `system` / `crash` Logcat 缓冲区
- 实时显示日志并自动保存为 `.log`
- 前台服务持续记录，切换到目标 App 后仍可继续采集
- 使用 Android 系统文件选择器导出日志
- 显示 Shizuku 当前为 ADB Shell 或 Root 后端

## 使用方法

1. 安装并启动 Shizuku。
2. 打开 ShizuLog。
3. 点击“请求 Shizuku 授权”并允许。
4. 选择目标 App，或输入包名。
5. 点击“开始记录”。
6. 打开目标 App 并复现问题。
7. 返回 ShizuLog，点击“停止”。
8. 点击“导出日志”。

## 构建

要求：JDK 17、Android SDK 35、Gradle 8.9。

```bash
gradle --no-daemon clean :app:assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

仓库内的 `.github/workflows/build-release.yml` 会在 `main` 分支 push 后：

1. 配置 Java 17 / Gradle 8.9 / Android SDK 35。
2. 编译可侧载安装的 Debug APK。
3. 生成 SHA-256 校验文件。
4. 上传 Actions Artifact。
5. 首次成功构建时创建 `v1.0.0` Release，并附带 APK。

## Shizuku

当前使用：

```text
dev.rikka.shizuku:api:13.1.5
dev.rikka.shizuku:provider:13.1.5
```

日志命令按目标应用 UID 过滤：

```text
logcat -b main -b system -b crash --uid=<UID> -v threadtime
```

## 注意事项

- Shizuku 非 Root 模式下，设备重启后通常需要重新启动 Shizuku。
- Shizuku 服务停止或重启时，正在运行的日志进程会结束。
- 如果目标应用本身没有输出某条日志，ShizuLog 无法恢复不存在的日志。
- 使用 shared UID 的应用可能混入同 UID 的其他包日志。
- `QUERY_ALL_PACKAGES` 用于侧载诊断场景；如需上架 Google Play，需要重新评估应用可见性方案。

## License

MIT License。详见 [LICENSE](LICENSE)。
