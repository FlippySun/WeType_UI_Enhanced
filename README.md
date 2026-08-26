<div align="center">
<img src="./assets/icon.png" width="120px"/>

# WeType UI Enhanced

一个以 **微信输入法（WeType）界面美化与个性化** 为主要功能的 Xposed 模块，同时为作用域其它输入法解锁 MIUI 全面屏优化限制。


<p align="center">

![Android 12 or later](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&amp;logoColor=white)
![LSPosed 102](https://img.shields.io/badge/LSPosed-Modern_API_102-5C6BC0)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)
![License](https://img.shields.io/badge/License-AGPLv3-orange)

</p>
</div>

---

## 功能

### 微信输入法美化

- 自定义浅色 / 深色模式下的窗口背景颜色与透明度
- 选择一张本地图片作为浅色 / 深色模式共用的输入法背景
- 自定义浅色 / 深色按键颜色、透明度与圆角
- 自定义背景模糊强度、平滑圆角及边缘高光效果
- 自定义输入法全局品牌强调色
- 调节工具栏图标背景透明度
- 调节候选词背景透明度与圆角
- 调节首个候选词及候选栏拼音边距
- 支持阻止微信输入法热更新

### MIUI / HyperOS 附加功能

在支持小米全面屏键盘优化的 MIUI / HyperOS 系统上，提供三方输入法解锁全面屏键盘优化限制，解锁小米短语的包名校验，修复三方输入法无法获取系统剪贴板列表的问题。

该部分并非模块主要功能，在非小米系统上不会启用，也不影响 WeType 美化功能。

## 效果预览

默认效果为 iOS 27 Apple 官方设计稿内的配色、圆角等数值
<details open>
<summary>#FB7299 主题色截图</summary>
<table>
  <tr>
    <td><img src="./assets/prew/dark_1.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/dark_2.jpg" width="200" alt="深色模式"></td>
    <td><img src="./assets/prew/light_1.jpg" width="200" alt="浅色模式"></td>
    <td><img src="./assets/prew/light_2.jpg" width="200" alt="浅色模式"></td>
  </tr>
</table>
</details>

## 使用要求

- Android 12+
- LSPosed / 兼容的 Xposed 框架
- Xposed API Version ≥ 102
- 微信输入法

安装模块后，在 LSPosed 中启用模块并勾选 **微信输入法** 作用域，然后重启微信输入法。

模块生效后，可通过桌面入口进入设置；也可以点击微信输入法「关于」页面中的 Logo 打开寄生设置页。

在「颜色」中选择背景图片并保存；图片会压缩后存入微信输入法私有目录，不需要相册或外部存储权限。

部分设置修改后需要重启微信输入法进程才能完全生效。

### 微信输入法 3.5.2 内嵌版

本分支兼容通过 NPatch 1.0.7 Canary 762 将模块嵌入微信输入法 3.5.2。打包时必须同时启用 `--injectdex`，确保模块进入微信输入法的 `:hld` 子进程；内嵌后可在微信输入法「关于」页点击 **插件与自定义背景** 进入寄生设置，无需另装模块或启用 LSPosed 作用域。稳定版 build 741 尚未向内嵌模块投递可写 XposedService，不能用于生成本修改版。

```shell
printf 'security.provider.13=org.bouncycastle.jce.provider.BouncyCastleProvider\n' > /tmp/npatch-bc.security
java -Djava.security.properties=/tmp/npatch-bc.security \
  -jar jar-v1.0.7-762-release.jar \
  -m WeType_UI_Enhanced-1.29.0_release.apk \
  --injectdex -npa -o output \
  WeType-3.5.2-source.apk
```

本次本地产物的 SHA-256：微信输入法 3.5.2 输入包 `42366ddb0dc82cc46dc99567359048597be186810d45dbef7d22bd364a6805f1`，1.29.0 模块 `c6034cd162a32dd2f07467f3a24cddae2932213b5366af12a8aba2e9898fbf1a`，内嵌输出 `37e54c9c42e90032544698a2d022ca378b4b526fa2864add9302249552f6336c`。

仓库仅提供 AGPL-3.0 模块源码，不提交或分发腾讯原始 APK 与合并后的安装包。修改版 APK 的签名必然不同于腾讯官方签名，普通设备无法直接覆盖官方版本；操作前应备份输入法数据，并根据设备的签名校验能力选择安装方式。

本次内嵌包仅完成构建与静态校验，未在设备上安装或启动，也未验证 `:hld` 运行日志、入口点击、选图保存和输入法进程重启。NPatch 写包结果可通过 `unzip` 与 APK v2 签名校验，但 Android 构建工具仍会报告 ZIP header/GPB flag warning，严格安装器的兼容性需在目标设备确认。下方测试环境仅对应独立模块，不覆盖微信输入法 3.5.2 内嵌包。

## 测试环境

设备：Xiaomi 17 Pro

HyperOS 4.0.0.27 Beta

Android 17

LSPosed v2.1.1-it (7846)

微信输入法：3.5.3.56201

## 兼容性

模块主要针对微信输入法进行适配。微信输入法内部实现、资源名称或云端热修复发生变化时，部分功能可能暂时失效。

MIUI / HyperOS 相关附加功能仅针对小米系统，不适用于其他厂商的系统级输入法优化实现。

## 下载

请前往本仓库或下方模块仓库的 Releases 下载最新版本。

Xposed 模块仓库：https://github.com/Xposed-Modules-Repo/com.xposed.wetypehook

## 开源致谢

感谢项目 [MIUI_IME_Unlock(MIT)](https://github.com/RC1844/MIUI_IME_Unlock) 提供的解锁 MIUI 全面屏优化限制功能

感谢 [miuix](https://github.com/compose-miuix-ui/miuix) 提供的 Compose UI 库

## 开源许可

本项目自 2026.8.16 起换用 AGPL-3.0 许可协议，要求修改和分发的同时也公开源码，且使用相同的许可协议。
