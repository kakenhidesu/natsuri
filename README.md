# natsuri

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![JDK](https://img.shields.io/badge/JDK-21-orange)](#构建)

natsuri 是与 [natsu](https://github.com/kakenhidesu/natsu) 配套的启动器，主要是为了更方便的同时提供 MCL 式插件管理

名字由 natsu 和「祭り（matsuri）」组成

使用时需要 Java 21 或更高版本

## 🔴 重要

本项目仍处于早期开发阶段，默认下载源为 natsu 的 [natsu-prev](https://github.com/kakenhidesu/natsu/releases/tag/natsu-prev) 预览版，功能与兼容性可能随版本变化。

## 快速使用

安装 Java 21 或更高版本，下载并解压 natsuri 发行 zip，在解压目录打开终端

Windows：

```powershell
.\natsuri.cmd
```

Linux / macOS：

```bash
./natsuri
```

首次运行会从默认源安装 natsu。Console 随后生成 `natsu.yml` 模板并退出，填写配置后再次运行即可

原生后端需要填写 `sign.url` 和 `accounts`

使用 OneBot 11 时，将 `backend.type` 改为 `onebot11`，并配置连接地址。详细配置见 [natsu](https://github.com/kakenhidesu/natsu#快速使用)

插件 jar 放入 `plugins/`，下次启动时加载


### 常用命令

| 命令 | 用途 |
|---|---|
| `install` | 从配置的下载源安装 |
| `install --manifest <地址>` | 指定本地或远程发行清单 |
| `versions` | 查看已安装版本 |
| `use <版本或 installId>` | 切换已安装版本 |
| `pin <版本>` / `unpin` | 锁定 / 解除版本锁定 |
| `doctor --deep` | 检查运行环境和安装文件 |
| `plugins list` | 查看插件 |
| `config show` | 查看启动器配置 |
| `run -- --no-console` | 向 natsu 传入无终端运行参数 |

## 构建

需要 JDK 21，项目已包含 Gradle Wrapper

```bash
./gradlew natsuriDistZip
```

## 鸣谢

- [iTXTech/mirai-console-loader](https://github.com/iTXTech/mirai-console-loader)  包管理与版本锁定的设计参考

## 许可证

natsuri 使用 [AGPLv3](LICENSE) 协议开源。
