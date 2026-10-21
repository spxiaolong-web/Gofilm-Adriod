# 小龙影视 · Android 学习版

这是一个用 Java 和 Android WebView 编写的手机应用，加载自己的 HTTPS 网站：
`https://film.spxiaolong.com`。

## 在手机 / iPad 上生成 APK

1. 打开本仓库的 **Actions**。
2. 进入 **Build Android APK**，选择最近一次绿色成功的运行记录。
3. 在页面底部 **Artifacts** 下载 **GoFilm-Android-release**。
4. 解压 ZIP，把 `app-release.apk` 下载到安卓手机，点击安装。
5. 如系统询问，临时允许本次下载使用的浏览器或文件管理器安装应用，完成后可关闭该权限。

也可以在 Actions 的 **Build Android APK → Run workflow** 手动编译。
第一次编译会下载 SDK 和构建工具，耗时取决于 GitHub 的执行情况。

## 这一版学什么

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/spxiaolong/gofilm/MainActivity.java` | App 的界面、网站加载、网页返回、视频全屏 |
| `app/src/main/AndroidManifest.xml` | 应用入口、名称、联网权限 |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应桌面图标 |
| `app/build.gradle` | 包名、版本、支持的 Android 版本 |
| `.github/workflows/build-apk.yml` | GitHub 云端检查、编译和保存 APK |

原理：APK 安装的是一个 Android 程序，程序内的 WebView 加载网站。
网站、数据库仍在原服务器运行，网页内容变化通常无需重装 APK；原生代码变化则需要重新编译。

## 已包含

- 首页、返回、刷新按钮及加载进度。
- 同域 HTTPS 页面留在应用中，外部 HTTP(S) 链接转交浏览器。
- 网页视频全屏容器及退出全屏按钮。
- 主页面加载失败的提示和重试按钮。
- 屏幕旋转时保留当前网页；离开应用时暂停 WebView。
- 仅申请联网权限，无广告 SDK、无文件读写权限、无 JavaScript 原生桥接。
- 保持 TLS 证书校验，禁用明文 HTTP 和混合内容。

## 限制

- 这是 Android 8.0 及以上手机的学习版，尚未适配电视遥控器。
- WebView 播放能力受系统 WebView、编码、片源网络和跨域策略影响，需真机测试。
- 不绕过 DRM、证书校验、访问控制；不能保证原网站的每条线路都兼容。
- 使用内容须有相应授权；开源程序不附带影视内容授权。
- 当前为固定私有密钥签名的 **release 包**，尚未上架应用商店。首次从旧 debug 包切换时，需要卸载旧包（会清除本地登录状态）。此后保持相同包名、签名密钥，并提高 versionCode，即可覆盖更新。
- 本仓库未包含 Gradle Wrapper；工作流固定安装 Gradle 8.11.1。电脑上可用相同版本运行 `gradle lintDebug assembleDebug`。

## 真机检查清单

安装 → 打开首页 → 搜索 → 进入详情 → 用自己有权播放的测试视频检查播放 / 全屏 / 返回 → 断网后刷新检查错误提示 → 恢复联网重试。

Actions 的 lint 和构建成功只代表静态检查、编译通过，不等于真机播放已经验证。

## 固定签名与版本更新

在仓库 Settings → Secrets and variables → Actions 添加以下 Repository secrets：

| 名称 | 内容 |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | 私有 PKCS12 密钥文件的完整 Base64 编码 |
| ANDROID_KEYSTORE_PASSWORD | 密钥库密码 |
| ANDROID_KEY_ALIAS | 密钥别名（本项目生成时为 gofilm） |
| ANDROID_KEY_PASSWORD | 密钥密码（本项目 PKCS12 生成时与密钥库密码相同） |

Base64 不是加密。密钥及其编码文件、密码必须私下保存，不能提交到仓库。
工作流在临时目录恢复密钥，检查别名、编译并验证 APK 签名，随后删除临时密钥。
缺少 Secrets 或签名错误会导致构建失败，不会退回调试签名。

每次发布新版本，在 app/build.gradle 中提高 versionCode，并修改 versionName。
当前为 versionCode 3 / versionName 0.3.0。下一次可使用 4 / 0.4.0。
APK 作为 Actions Artifact 保留 14 天；此工作流不会自动创建 GitHub Release。

## v0.3.0 图标更新

- 使用原创深蓝底金龙图案，资源在 drawable-nodpi/dragon_artwork.png。
- 自适应图标分为背景与前景；前景保留边距，适配圆形、圆角方形等桌面遮罩。
- 本次仅更新图标和版本号，继续使用原有包名与固定签名。
- 安装时直接覆盖 v0.2.0，不需卸载；安装后检查桌面图标、登录状态和播放。
