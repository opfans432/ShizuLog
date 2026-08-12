# ShizuLog v1.6.0

## 一键诊断包

新增“诊断包”入口，可把一次排错所需的信息整理为 ZIP：

- 当前原始 Logcat
- 崩溃分析摘要
- Android / 设备信息
- 目标 App 名称、包名、版本、UID
- 从最近日志识别出的目标 UID 对应 PID
- Shizuku UID、ADB Shell / Root 后端、Binder 和权限状态
- ShizuLog 自身版本与记录模式

生成完成后可以通过 Android 系统分享面板直接发送 ZIP。

## 隐私

诊断包不会主动包含 ShizuLog 发布签名 `.jks`、`SIGNING-RECOVERY.env`、GitHub Actions Secrets 或其他应用私有文件。原始 Logcat 本身仍可能含敏感信息，分享前请确认接收方可信。

## 固定签名

v1.6.0 保留 v1.5.2 的固定签名体系。升级脚本会先确认现有 GitHub Actions 工作流仍包含 `SHIZULOG_KEYSTORE_B64`，否则拒绝修改，避免误回退到随机 Debug 签名。
