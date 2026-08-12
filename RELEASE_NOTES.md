# ShizuLog v1.5.2

## 固定发布签名

v1.5.2 是 ShizuLog 的签名迁移版本。

从此版本开始，GitHub Actions 不再依赖每台临时 Runner 自动生成的 Debug 签名，而是：

1. 构建 Android `release` APK。
2. 从 GitHub Actions Secrets 恢复固定的 ShizuLog 发布密钥。
3. 使用 Android SDK `zipalign` 对齐 APK。
4. 使用 `apksigner` 以同一把长期密钥签名。
5. 构建过程中执行 `apksigner verify --print-certs` 验证签名。

### 重要

如果设备当前安装的是 v1.5.1 或更早的随机 Debug 签名版本：

- 安装 v1.5.2 时需要最后卸载一次旧版。
- 从 v1.5.2 开始，只要以后保留同一把签名密钥且 versionCode 持续增加，就可以直接覆盖更新。

### 密钥安全

私钥不会提交到 Git 仓库。

正式密钥只存在于：

- 用户自己保存的本地签名备份。
- GitHub Actions Secrets。

丢失这把密钥后，将无法继续为已经安装固定签名版本的用户提供同包名覆盖升级。
