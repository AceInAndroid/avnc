# FastVNC 3.3.1

FastVNC 首个独立发布版本，基于 AVNC 开源维护，采用 GPL-3.0-or-later，保留 Gaurav Ujjwal、贡献者及第三方依赖的版权和许可证声明。

## 本次更新

- 全新 FastVNC 名称、电脑与双向连接图标；独立包名 `tech.zhangbing.fvnc` 和发布签名。
- 连接与设置双标签主页、快速添加电脑、设置搜索，以及连接、认证、错误和重试状态。
- 电脑键盘、触控板与快捷键标签；Mac / Windows 输入配置、F1–F12、修饰键和常用操作。
- 双指滚动与捏合、可拖动触控板、大小与透明度设置，滚动灵敏度最高 10 倍。
- Mac 控制快捷操作、显示器选择，以及流畅 / 高清画质模式。
- 安卓文字剪贴板同步与本地历史管理；通过独立 SSH/SFTP 发送文件到远端账户的 Downloads 文件夹。
- 连接取消、超时、重连、后台输入释放、SSH 与设备发现的稳定性修复。

## 安装与使用

- 下载 `FastVNC-3.3.1-arm64-release.apk`；支持 Android 5.0 及以上的 ARM64 设备，本次未提供其他 ABI。
- 可与原 AVNC 共存；旧连接需要在 AVNC 导出 JSON 后到 FastVNC 导入。
- 剪贴板自动同步受安卓前台读取限制，从其他应用复制后需回到 FastVNC，再在远端粘贴。
- 文件发送需要远端开启 SSH/SFTP；Mac 可在系统设置的共享中开启远程登录。
- 横向滚动、中文输入、显示器识别与部分 Mac 快捷操作取决于远端服务器能力和系统配置。

## 开源与校验

- 本发布标签：`fastvnc-v3.3.1`，区别于继承的上游 `v3.3.1` 标签。
- 对应源码附件 `FastVNC-3.3.1-corresponding-source.tar.gz` 包含应用、构建脚本、递归子模块、原生依赖源码和补丁、93 个已解析的运行时依赖源码 JAR，以及版本和文件哈希清单。GitHub 自动生成的 Source code ZIP/TAR 不包含子模块内容，推荐下载上述完整归档。
- `COPYING.txt`：GPL v3；`THIRD_PARTY_NOTICES.txt`：第三方许可证和声明。
- `SHA256SUMS.txt`：附件 SHA-256；`FastVNC-release-certificate.pem`：发布公钥证书。
- 发布证书 SHA-256：`48bdc5fa99c29cab152a2b6952deacba3525afed3d454870d69fec0ab94f1c38`。
- 构建和自行签名说明见仓库 `docs/RELEASING.md`。公开源码不包含私钥、口令或设备数据，可自行构建、修改和签名。

## 验证范围

Debug、Release、测试 APK 编译及 APK 签名校验通过；排除既有 MissingTranslation 后 lint 通过。此前版本已通过 18 项相关真机回归及签名 Release 的实际 VNC framebuffer 检查。本次许可证补充后重新编译；原测试机已断开，未重新执行真机测试。

标准 lint 仍有既有缺失翻译及告警；本轮未重复全平台、真实多显示器、真实 Mac / Windows 长时间会话和弱网验收。
