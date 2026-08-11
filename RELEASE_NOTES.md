# ShizuLog v1.4.1

## 修复：多应用“打开目标应用”
- 多应用模式重新启用“打开所选应用”
- 点击后弹出已选择 App 列表，选择其中一个即可启动
- 记录进行中仍可打开目标 App，恢复“先开始记录 → 再打开 App 复现”的正常流程

## 修复：多应用日志几乎为空
- 不再使用 `logcat --uid=...` 作为唯一过滤
- 改为读取 Shizuku Shell 当前可见 Logcat，再由 ShizuLog 软件过滤
- 保留目标 UID 自己写出的日志
- 同时保留消息中提到目标包名的系统日志
- 新增 `events` buffer，提高进程、Activity、任务等系统事件的可见性
- Logcat 输出加入 UID 字段

## 诊断
新日志文件头会显示 `buffers=main,system,crash,events` 与 `filter_strategy=software_uid+package_context`。

注意：Logcat 不会自动记录每一次点击、游戏操作或业务动作；只有 App 或 Android 系统实际写入日志缓冲区的内容才能被记录。
