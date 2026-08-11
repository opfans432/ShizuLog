# ShizuLog v1.4.0

## 新增记录范围

ShizuLog 现在支持三种记录模式：

- **单应用**：按一个目标 App 的 UID 过滤 Logcat
- **多应用**：同时选择多个 App，按多个 UID 合并过滤
- **全局**：不使用 UID 过滤，记录 Shizuku 当前权限可以读取的 main / system / crash Logcat

## 多应用记录

- 新增可搜索的多选 App Picker
- 支持按应用名称或包名搜索
- 支持勾选任意数量的应用
- 已选择应用会持久化保存
- 相同 UID 会自动去重
- 多应用模式使用 Android logcat 原生的逗号分隔 `--uid=UID1,UID2,...` 过滤

## 全局 Logcat

- 无需选择目标 App
- 开始记录前显示风险确认
- 文件名使用 `global_时间.log`
- 全局模式仍支持历史日志、完整日志、搜索、WARN+ / ERROR 筛选和崩溃快照
- 全局模式仅代表“不按 App UID 过滤”；实际可读取范围仍受 Android ROM 与 Shizuku Shell / Root 权限控制

## 性能

- LogCaptureService 改为批量向界面发送实时日志，降低全局记录时的 Broadcast / UI 压力
- 日志文件写入改为短周期批量 flush，减少高频全局日志的磁盘 I/O
- 完整原始日志仍持续保存到文件

## 崩溃快照

- 单应用、多应用会优先补抓目标 UID 的近期日志
- 同时尝试补抓 AndroidRuntime / ActivityManager 等系统侧关联信息
- 全局模式会补抓近期全局 crash 与关键错误日志
