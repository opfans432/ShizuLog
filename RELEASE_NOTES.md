# ShizuLog v1.6.1

## 诊断包隐私增强

诊断包默认改为“脱敏版”。

脱敏器会尝试遮盖常见格式：

- `Authorization`
- `Bearer ...`
- `Cookie / Set-Cookie`
- `access_token`
- `refresh_token`
- `id_token`
- `api_key / api-key`
- `client_secret`
- `session / sessionid`
- `auth_token`
- `password / passwd`
- 常见 JWT

命中的内容会替换为：

```text
<REDACTED>
```

仍然保留“原始版”选项，原始版不会主动修改日志。

> 自动脱敏不可能识别所有敏感信息，因此分享前仍应确认接收方可信。

## 自定义诊断包内容

日志文件始终包含，以下附加信息可以单独关闭：

- 崩溃分析摘要
- 设备 / Android 信息
- 目标 App、版本、UID/PID

## 历史日志支持

历史日志卡片新增“诊断包”。

现在不只是当前日志，任何历史 `.log` 都可以直接生成诊断包。

对于历史日志，ShizuLog 会优先读取日志文件头中的：

- `mode`
- `packages`
- `uids`

避免误用当前首页选择的 App 信息。

## 诊断包操作

生成后支持：

- 分享
- 另存为任意 Android 文档位置
- 删除应用内生成的诊断包

## 完整性清单

每个诊断 ZIP 新增：

```text
manifest-sha256.txt
```

其中记录 ZIP 内主要文件的 SHA-256，便于检查文件是否被改变。

## 固定签名

v1.6.1 继续沿用 v1.5.2 建立的固定发布签名。

升级脚本会先检查 GitHub Actions 中是否仍存在：

- `SHIZULOG_KEYSTORE_B64`
- `assembleRelease`
- `apksigner`

如果固定签名工作流不存在，脚本会停止，避免意外重新发布随机签名 APK。
