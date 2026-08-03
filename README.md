# lan-projects

> 🔒 局域网文件共享工具 — 在局域网内安全、高速地传输文件。跨平台的 AirDrop 替代方案。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Node](https://img.shields.io/badge/node-%3E%3D18-brightgreen)](web/package.json)

---

## 特性

| 特性 | 说明 |
|------|------|
| 🌐 **局域网高速传输** | 基于 WebSocket 中继，充分利用局域网带宽，不消耗互联网流量 |
| 🔐 **端到端加密** | 所有传输强制加密，没有密钥就无法传输，防被动嗅探 |
| 📱 **Android App** | 手机装上 App 自己就能当服务器，无需电脑；App 与网页版完全互通 |
| 📷 **扫码连接** | App 扫码对方二维码即可加入，也可显示本机二维码供他人扫码 |
| 📶 **网络状态一目了然** | 首页自动识别并显示当前网络（WiFi 名 / 移动数据 / 个人热点）与 IP，可直接开启 / 关闭 WiFi、移动数据、热点 |
| ⚡ **一键启动** | 电脑双击 `desktop\start.bat` 即可运行，自动安装依赖 |
| 🚀 **大文件秒存** | App 收到的大文件原生写盘（1GB 约 3 秒），不经过缓慢的 JS 桥 |
| 🔗 **持久配对** | 6 位配对码或二维码绑定设备，长期使用无需重复配对 |
| 🌍 **多语言** | 支持 30+ 种语言界面 |

---

## 快速使用（两种方式）

lan-projects 有两种用法，任选其一：

| 方式 | 适用场景 | 说明 |
|------|----------|------|
| **① 电脑端服务器** | 电脑上跑服务器，其他设备用浏览器访问 | 需要装 Node.js |
| **② Android App** | 手机 App 自己当服务器，或两台手机互传 | 直接装 APK，无需电脑 |

### 方式①：电脑端服务器

1. 电脑装 [Node.js ≥ 18](https://nodejs.org/)
2. **双击 `desktop\start.bat`**（Windows）
3. 启动后显示局域网地址，如 `http://192.168.1.100:3000`
4. 手机/其他电脑连**同一个 WiFi**，浏览器打开这个地址即可互传文件

### 方式②：Android App

1. 安装 `lan-projects-vX.X.X-release.apk`
2. 打开 App 首页，两种入口：
   - **📱 用本设备作为启动**：本机当服务器，其他设备扫码加入
   - **📷 扫码连接**：扫对方二维码（可连接 / 配对 / 加入房间）
3. 对方在传输页显示二维码，你扫码即可连接
4. 首页右上角「⚙ 设置 → **检查更新**」可从 GitHub 获取并安装最新版本

> 电脑端和 App 用的是**同一套网页代码**，两者完全互通，可以混用（App 连电脑服务器，电脑浏览器连手机服务器）。

---

## 系统要求

**📱 Android App（手机即服务器）**

| 项目 | 要求 |
|------|------|
| 系统版本 | **Android 9.0（API 28）及以上**，无上限；`targetSdk 35`，Android 15 / 16 实测可用，更高的系统版本向后兼容 |
| 处理器 | **64 位 ARM（arm64-v8a）**——Android 9+ 的绝大多数手机都满足，极少数低端 32 位设备除外 |
| 安装方式 | 直接安装 `lan-projects-vX.X.X-release.apk`，无需电脑 |

> 低于 Android 9，或 32 位处理器的设备**无法安装**；App 只打 `arm64-v8a` 一个架构，安装包更小。`targetSdk` 不是版本上限，新系统照常运行，只是不会触发更新系统的强制行为变更。

**💻 电脑端（桌面服务器 / 浏览器连接）**

| 系统 | 能否使用 | 怎么跑 |
|------|----------|--------|
| **Windows 10 / 11**（64 位） | ✅ 推荐 | 双击 `desktop\start.bat`，自动装依赖并启动 |
| **macOS** | ✅ | 装 [Node.js ≥ 18](https://nodejs.org/) 后 `cd web && npm install && npm start` |
| **Linux** | ✅ | 同上（需 Node.js ≥ 18） |

> 电脑端统一要求 **Node.js ≥ 18（64 位）**；浏览器打开 `http://局域网IP:3000` 即可互传，桌面浏览器还支持 WebRTC 直连（不过服务器中继）。

---

## 启动

### 电脑端

**Windows：** 双击 `desktop\start.bat`（首次自动装依赖，自动清理 3000 端口残留进程）。

**Mac / Linux：**

```bash
cd web
npm install
npm start
```

启动成功显示：

```
lan-projects is running on port 3000
局域网访问: http://192.168.1.100:3000
```

关闭服务按 **Ctrl + C**。

### Android App

安装 APK 后直接打开使用，无需任何启动步骤（点"用本设备作为启动"即在本机起服务器）。

---

## 端到端加密

lan-projects **所有文件传输强制加密，且必须先配对**。未配对的连接会直接拒绝传输。

| 连接类型 | 加密方式 | 说明 |
|----------|----------|------|
| 🔗 **已配对设备** | 配对秘密派生密钥 + **AES-256** 加密 | 密钥只有收发双方知道（线下扫码 / 配对码建立），同一网络抓包也解不开 |

页面底部显示 **🔒 端对端加密** 表示传输已加密。

> 加密使用 AES-256（纯 JS 实现）+ 随机 nonce，无需 HTTPS 证书即可在局域网正常工作。
>
> **未配对的设备不能互传文件**——先点顶栏 🔗 配对（扫码或输入 6 位配对码），配对后即可安全传输。

---

## 设备配对

**配对是互传文件的前置条件**——未配对设备之间的传输会被拒绝（保证加密钥匙只有双方知道）：

1. 点按顶部 **🔗 配对图标**
2. **发起方**扫描二维码或向对方提供 6 位配对码
3. **接收方**在配对弹窗中输入 6 位配对码
4. 配对成功后自动存储，下次无需重新配对
5. 配对后即可互传文件，传输全程 AES-256 加密

---

## 端口被占用

`desktop\start.bat` 启动时**自动清理**残留进程，不会端口冲突。

**Windows：**

```bash
netstat -ano | findstr :3000
taskkill -f -pid <进程PID>
```

**Mac / Linux：**

```bash
lsof -ti:3000 | xargs kill -9
```

---

## 隐私说明

- **完全离线**：所有数据在局域网内传输，**不经过公网服务器**
- **端到端加密**：即使有人连上你的 WiFi 抓包，也无法解密传输内容
- **扫码连接仅限局域网服务器**：扫一扫只识别 lan-projects 服务器的连接 / 配对 / 房间二维码，公网网站二维码会被拒绝；App 内遇到指向公网的链接或重定向也会改用系统浏览器打开，恶意网页无法借 App 写文件
- **无注册无追踪**：不用注册账号，不收集任何信息；App 不包含任何统计/广告/云上报 SDK
- **可选配对**：配对后自动识别设备，支持自动接收文件
- **检查更新走加速代理**：仅当你主动点「设置 → 检查更新」时才联网，App 查询 GitHub 最新版本并下载安装包；国内直连 GitHub 不通时会**自动经第三方加速代理**（ghfast.top 等）转发下载、GitHub 直连兜底，请求不携带任何个人数据

---

## 项目结构

```
lan-projects/
├── app/                      # Android App（嵌入 Node.js 运行时，手机即服务器）
│   └── app/src/main/
│       ├── assets/nodejs-project/   # 打包进 APK 的 web 代码（构建时同步）
│       ├── java/io/lanprojects/phone/  # Android 源码
│       ├── jniLibs/          # libnode.so（Node 运行时）
│       └── cpp/              # JNI 桥（node_bridge.cpp）
├── web/                      # 共享代码（App 和电脑端共用，唯一代码源）
│   ├── public/               # 前端页面（index.html / scripts / styles / images / lang）
│   ├── server/               # Node 服务器（index.js / server.js / ws-server.js）
│   ├── package.json          # 依赖配置（express / ws 等）
│   └── node_modules/         # 依赖（不提交 git）
├── desktop/                  # 电脑端启动
│   └── start.bat             # Windows 一键启动（自动切到 web\ 运行）
├── README.md                 # 本文档（使用 / 启动）
├── ARCHITECTURE.md           # 架构介绍（面向用户：系统组成、传输链路、安全模型）
├── updatelog.md              # 更新日志
└── LICENSE
```

> **web/** 是 App 和电脑端**共享的唯一代码源**：电脑端 `desktop\start.bat` 直接跑它，Android 打包时由开发机上的 `build-apk.bat`（仓库外）同步进 assets。

---

## 更新记录

- 每次发布的功能 / 修复 / 安全更新，详情请见 **[更新文档](https://github.com/jidanbings/lan-projects/blob/main/updatelog.md)**。
- App 内「设置 → **检查更新**」可一键查询并安装最新版本（国内直连 GitHub 不通时自动走加速代理下载）。

---

## 许可证

本项目基于 MIT 许可证开源。详见 [LICENSE](LICENSE)。
