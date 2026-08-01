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

1. 安装 `lan-projects-vX.X.X-debug.apk`
2. 打开 App 首页，两种模式：
   - **📱 用本设备作为启动**：本机当服务器，其他设备访问本机地址
   - **🔗 连接其他设备**：加入另一台手机/电脑的服务器（两台手机互传选这个）
   - **📷 扫码连接**：扫对方的二维码直接加入
3. 对方进「设置 → 二维码/传输页二维码」显示二维码，你扫码即可连接

> 电脑端和 App 用的是**同一套网页代码**，两者完全互通，可以混用（App 连电脑服务器，电脑浏览器连手机服务器）。

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

lan-projects **所有文件传输强制加密**，没有加密的连接会拒绝传输。

| 连接类型 | 加密方式 | 说明 |
|----------|----------|------|
| 🌐 **同一局域网** | 共享 IP 房间 ID 派生密钥 + XOR 流密码 + 随机 IV | 收发双方必然得到同一把钥匙，防被动嗅探 |

页面底部显示 **🔒 端对端加密** 表示传输已加密。

> 加密使用 `crypto.getRandomValues()` + XOR 流密码，无需 HTTPS 证书即可在局域网正常工作。接收端无对应密钥时直接丢弃数据，绝不落盘。
>
> 注：密钥从收发双方共享的局域网房间 ID 派生，保证两台设备钥匙一致、文件不损坏。

---

## 设备配对

配对后设备之间使用更强加密，且自动识别：

1. 点按顶部 **🔗 配对图标**
2. **发起方**扫描二维码或向对方提供 6 位配对码
3. **接收方**在配对弹窗中输入 6 位配对码
4. 配对成功后自动存储，下次无需重新配对

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
- **无注册无追踪**：不用注册账号，不收集任何信息；App 不包含任何统计/广告/云上报 SDK
- **可选配对**：配对后自动识别设备，支持自动接收文件

---

## 项目结构

```
lan-projects/
├── app/                      # Android App（嵌入 Node.js 运行时，手机即服务器）
│   ├── build-apk.bat         # 一键打包 APK（自动从 web\ 同步代码 + 构建 + 清理）
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
└── LICENSE
```

> **web/** 是 App 和电脑端**共享的唯一代码源**：电脑端 `desktop\start.bat` 直接跑它，Android 打包时由 `app\build-apk.bat` 同步进 assets。

---

## 许可证

本项目基于 MIT 许可证开源。详见 [LICENSE](LICENSE)。
