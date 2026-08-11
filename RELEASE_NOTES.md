# ShizuLog v1.4.2

这是 v1.4.x 的抓取质量与稳定性更新。

## 修复 WARN / ERROR 筛选

v1.4.1 开始使用 `threadtime,uid` 输出格式，日志行从：

`时间 PID TID E ...`

变成：

`时间 UID PID TID E ...`

旧的首页级别识别仍按旧格式解析，因此 WARN+ / ERROR 可能漏判。v1.4.2 的解析同时兼容旧日志和带 UID 的新日志。

## 动态 PID 与多进程追踪

单应用和多应用记录现在同时使用：

- 目标 UID
- 当前目标 PID
- `包名:remote` / `包名:service` 等多进程名称
- 系统日志中的目标包名
- 系统日志消息中出现的目标 PID

ShizuLog 每约 1.5 秒刷新一次目标进程列表。App 被杀死后重新启动、PID 改变或启动额外远程进程时，不需要重新开始记录。

为了保留进程刚退出后的崩溃上下文，最近 PID 会保留约 30 秒后再淘汰。

## 全局日志自动分卷

全局记录单卷达到约 50 MB 时自动创建下一卷：

- `global_YYYYMMDD_HHMMSS_part01.log`
- `global_YYYYMMDD_HHMMSS_part02.log`
- `global_YYYYMMDD_HHMMSS_part03.log`

当前日志路径会自动切换到最新卷，旧卷继续保留在历史日志中。

## 实时统计

“当前状态”新增实时统计：

- 已记录行数
- WARN 数量
- ERROR / FATAL 数量
- 当前行速率（行/s）
- 累计写入大小
- 单/多应用模式下的动态 PID 数量
- 全局模式分卷编号

## 其他修复

- 修复批量实时日志块末尾已经有换行时，首页仍额外添加空行的问题。
- 日志文件头新增 `dynamic_pid` / `multi-process` 跟踪说明。
