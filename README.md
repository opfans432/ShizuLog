<p align="center">
  <img src=".github/assets/shizulog-icon.png" width="96" height="96" alt="ShizuLog">
</p>

<h1 align="center">ShizuLog</h1>

<p align="center">
  基于 Shizuku 的 Android Logcat 记录、崩溃排查与历史日志管理工具
</p>

<p align="center">
  <strong>当前版本：v1.5.1</strong>
</p>

---

## 简介

ShizuLog 用于在 Android 设备上通过 Shizuku 采集和整理 Logcat，适合排查闪退、卡死、异常行为、后台进程问题，以及需要同时观察多个应用与系统上下文的场景。

当前已经支持 **单应用、多应用、全局 Logcat** 三种记录范围，并提供动态 PID / 多进程跟踪、崩溃快照、完整日志查看、历史日志管理和大日志自动分卷。

> ShizuLog 只能记录应用或 Android 系统实际写入 Logcat 的内容。普通点击、游戏操作或业务动作如果没有产生 Logcat，本身不会被自动记录。

## 记录模式

### 单应用

适合排查一个目标 App。

- 从已安装应用中选择目标
- 也可以手动输入包名
- 按目标 UID 及动态 PID 跟踪相关日志
- 支持目标 App 多进程，如 `:service`、`:remote`
- 记录过程中仍可直接打开目标 App 复现问题

### 多应用

适合同时排查主程序、插件、悬浮窗、辅助工具等相互关联的应用。

- 支持搜索并多选已安装应用
- 同时跟踪多个目标 UID
- 动态跟踪所有目标进程 PID
- 相同 UID 自动去重
- 保留包含目标包名、PID 或相关系统上下文的日志
- “打开所选应用”可从已选应用中选择一个直接启动

### 全局 Logcat

不按目标 App 过滤，记录 Shizuku 当前权限能够读取的系统日志。

- 采集 `main / system / crash / events`
- 适合目标未知、系统级故障或跨应用问题
- 默认约 **50 MB 自动分卷**
- 分卷文件使用 `part01 / part02 / ...`
- 开始记录前会提示日志量与隐私风险

> “全局”表示 ShizuLog 不主动按 App UID 过滤；实际能够读取哪些日志，仍取决于 Android 版本、ROM 和 Shizuku 当前 Shell / Root 权限。

## 实时日志

首页提供实时预览和记录状态。

- 实时显示日志
- `全部 / WARN+ / ERROR` 级别筛选
- 关键词搜索
- 保持筛选前滚动位置
- 用户查看旧日志时不会被强制拉回底部
- 实时显示：
  - 总日志行数
  - WARN 数
  - ERROR 数
  - 当前行速率
  - 已写入大小
  - 当前 PID 数量
  - 全局日志当前卷号

首页预览为了流畅性只保留有限长度，**完整原始日志始终写入文件**。

## 崩溃记录

ShizuLog 会针对常见崩溃场景保留额外上下文。

- `FATAL EXCEPTION`
- `AndroidRuntime`
- `ANR`
- `SIGABRT`
- `SIGSEGV`
- `signal 6 / signal 11`
- Native crash
- `ActivityManager / ActivityTaskManager` 相关上下文
- 手动“崩溃快照”
- 从目标 App 返回 ShizuLog 后自动尝试补抓崩溃快照

多应用模式会结合目标 UID、动态 PID、进程名和包名保留相关上下文，避免单纯 `logcat --uid` 过早丢失系统进程产生的关联日志。

## 历史日志

v1.5.0 对历史日志页面进行了完整升级。

支持：

- 后台扫描历史日志，不阻塞 UI
- 搜索应用名称、包名和文件名
- 按模式筛选：
  - 全部
  - 单应用
  - 多应用
  - 全局
  - 有崩溃
- 按以下方式排序：
  - 最新
  - 最大
  - 最旧
- 显示日志总数量与总占用空间
- 单份日志导出
- 单份日志删除
- 当前正在记录的文件禁止删除
- 批量清理：
  - 7 天前
  - 30 天前
  - 全部历史日志
- 自动识别全局分卷日志

## 完整日志查看

首页和历史日志页都可以进入完整日志查看器。

- 不受首页实时预览长度限制
- 大文件按约 **256 KB 分页读取**
- 第一页 / 上一页 / 下一页 / 最后一页
- 文本可长按选择复制
- 支持正在增长的日志文件
- 当前文件仍在写入时显示 `● 正在写入`
- 点击“刷新”重新计算最新页数
- 原本位于最后一页时，刷新后继续跟随最新页

## 使用方法

1. 安装并启动 **Shizuku**。
2. 打开 **ShizuLog**，授予 Shizuku 权限。
3. 选择记录范围：`单应用 / 多应用 / 全局`。
4. 单应用或多应用模式下选择目标 App。
5. 点击 **开始记录**。
6. 打开目标 App 并复现问题；多应用模式可通过“打开所选应用”选择要启动的 App。
7. 问题复现后返回 ShizuLog。
8. 停止记录，并查看实时日志、完整日志或历史日志。
9. 需要交给其他人分析时，可使用导出功能保存 `.log`。

## 权限与后端

ShizuLog 通过 Shizuku 执行日志采集，不要求用户另外使用电脑执行 ADB 授权流程。

应用会显示当前 Shizuku 后端，例如：

- ADB Shell
- Root

可读取的 Logcat 范围由 Android 系统和当前 Shizuku 后端权限决定。

## 构建

构建环境：

- JDK 17
- Android SDK 35
- Gradle 8.9
- Android Gradle Plugin 8.7.3

构建命令：

```bash
gradle --no-daemon clean :app:assembleDebug
```

APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 会自动构建 APK，并在对应版本 Release 中发布。

## 主要依赖

```text
dev.rikka.shizuku:api:13.1.0
dev.rikka.shizuku:provider:13.1.0
androidx.appcompat:appcompat:1.7.1
com.google.android.material:material:1.13.0
```

## 注意事项

- Logcat 不等于完整的用户操作记录。
- 某些正式版 App 本身输出日志较少，这是正常现象。
- 全局模式可能产生大量日志，并可能包含其他应用或系统产生的敏感信息。
- 长时间全局记录会自动分卷，但仍应关注设备剩余存储空间。
- Shizuku 被停止、重启或权限失效后，需要重新连接或授权。
- 不同 Android ROM 对 Shell 可见日志的限制可能不同。

## 版本

当前版本：**v1.5.1**

详细更新内容见 [`RELEASE_NOTES.md`](RELEASE_NOTES.md)。

## 许可证

本项目使用 [MIT License](LICENSE)。

## 崩溃分析

v1.5.1 新增本地崩溃分析器。

可从当前日志或历史日志进入，自动识别 Java / Kotlin `FATAL EXCEPTION`、ANR、SIGSEGV、SIGABRT 等常见崩溃，并尝试提取：

- 崩溃类型
- 进程 / PID
- 线程
- 异常类或 Native signal
- `Caused by` 根因
- 关键调用位置
- 崩溃堆栈片段

分析过程在设备本地完成，不会自动上传日志。
