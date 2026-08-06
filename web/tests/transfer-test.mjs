// 传输链路端到端验证：走真实 ws-server，用两个假客户端完成一次完整文件传输的
// 线协议握手（request -> response -> header -> 二进制分块 -> partition 控制流），
// 全程真实加解密（ChaCha20-Poly1305 AEAD + HKDF 派生），字节级比对重建结果。
//
// 专门覆盖传输被报告"传不了文件"后的回归面：
//   [1] e2e 信封经服务器中继：按 secret 房间路由
//   [2] e2e 信封经服务器中继：按 ip 房间路由（WSPeer._getRoomTypes()[0] 两种顺序都覆盖）
//   [3] 二进制分块经 ws-chunk-prep + 原始帧中继，接收端解密后字节级一致
//   [4] partition / partition-received 控制流加密往返
//   [5] 服务器路由字段仅透传，不解密（信封对服务器是黑盒）
// 运行：node transfer-test.mjs
import fs from 'fs';
import WebSocket from 'ws';

import LanProjectsServer from '../server/server.js';
import LanProjectsWsServer from '../server/ws-server.js';

// 用间接 eval 加载真实前端脚本（同 crypto-test.cjs / smoke-test.mjs）。
global.window = globalThis;
(0, eval)(fs.readFileSync('../public/scripts/libs/chacha20.js', 'utf8'));
(0, eval)(fs.readFileSync('../public/scripts/libs/crypto-util.js', 'utf8'));
(0, eval)(fs.readFileSync('../public/scripts/network.js', 'utf8'));

const { LanPairing, Chacha } = window;

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) { passed++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
    else { failed++; console.log('  ✗ FAIL: ' + name); }
};

// ---- 起服务器 ----
const conf = { debugMode: false, port: 0, wsFallback: true, ipv6Localize: false, rateLimit: false, lanIp: '192.168.0.26' };
const httpServer = new LanProjectsServer(conf).server;
const wss = new LanProjectsWsServer(httpServer, conf);
while (!httpServer.address()) await new Promise(r => setTimeout(r, 10));
const port = httpServer.address().port;
console.log(`服务器已启动: ws://127.0.0.1:${port}（lanIp=${conf.lanIp}）`);

// ---- 假客户端：记录 JSON 消息与二进制帧，按 network.js 的收包路径分派 ----
function makeClient(name) {
    const c = { name, messages: [], binaryChunks: [], peerId: null, others: new Map(), ws: null };
    c.ws = new WebSocket(`ws://127.0.0.1:${port}`);
    c.ws.binaryType = 'arraybuffer';
    c.ws.on('message', (data, isBinary) => {
        if (isBinary) {
            // ServerConnection._onMessage 对二进制帧 fire 'ws-binary'，路由信息
            // 来自上一条 ws-chunk-prep 的 from。
            c.binaryChunks.push({ from: c.pendingBinaryFrom, data });
            return;
        }
        const msg = JSON.parse(data.toString());
        if (msg.type === 'ws-chunk-prep') c.pendingBinaryFrom = msg.from;
        if (msg.type === 'peer-joined') c.others.set(msg.peer.id, msg.peer);
        if (msg.type === 'peers') for (const p of msg.peers) c.others.set(p.id, p);
        if (msg.type === 'pair-device-joined') c.others.set(msg.peerId, { id: msg.peerId });
        c.messages.push(msg);
    });
    return c;
}
const waitMsg = (c, type, timeout = 4000) => new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${c.name} 未收到 ${type}，已收到: ` + c.messages.map(m => m.type).join(','))), timeout);
    const check = () => {
        const m = c.messages.find(m => m.type === type);
        if (m) { clearTimeout(t); resolve(m); }
        else setTimeout(check, 25);
    };
    check();
});
const open = c => new Promise(resolve => c.ws.on('open', resolve));

// 等待某客户端收到的第 n 条 type 消息（前面的已消费，不能复用 waitMsg）。
const waitNth = (c, type, n, timeout = 4000) => new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${c.name} 未收到第${n}条 ${type}，已收到 ${c.messages.filter(m => m.type === type).length} 条`)), timeout);
    const check = () => {
        const list = c.messages.filter(m => m.type === type);
        if (list.length >= n) { clearTimeout(t); resolve(list[n - 1]); }
        else setTimeout(check, 25);
    };
    check();
});

// 解密信封（与 network.js Peer._decrypt 同构）
function decryptEnvelope(key, base64) {
    const frame = Uint8Array.from(Buffer.from(base64, 'base64'));
    if (frame.length < 28) return null;
    const nonce = frame.slice(0, 12), sealed = frame.slice(12);
    return Chacha.chacha20poly1305(key, nonce).decrypt(sealed);
}
// 加密信封（与 network.js Peer._sendEncrypted 同构）
function encryptEnvelope(key, obj) {
    const pt = new TextEncoder().encode(JSON.stringify(obj));
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const sealed = Chacha.chacha20poly1305(key, nonce).encrypt(pt);
    const frame = new Uint8Array(12 + sealed.length);
    frame.set(nonce); frame.set(sealed, 12);
    return Buffer.from(frame).toString('base64');
}
// 加密二进制帧（与 network.js Peer._encrypt 同构：nonce(12)‖ct‖tag(16)）
function encryptFrame(key, bytes) {
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const sealed = Chacha.chacha20poly1305(key, nonce).encrypt(bytes);
    const frame = new Uint8Array(12 + sealed.length);
    frame.set(nonce); frame.set(sealed, 12);
    return frame.buffer;
}
function decryptFrame(key, buf) {
    const view = new Uint8Array(buf);
    if (view.length < 28) return null;
    const nonce = view.slice(0, 12), sealed = view.slice(12);
    return Chacha.chacha20poly1305(key, nonce).decrypt(sealed);
}
// 发送端信封：WSPeer.sendJSON 会附加 to/roomType/roomId（与真实前端一致）。
function sendE2e(c, key, to, roomType, roomId, inner) {
    c.ws.send(JSON.stringify({
        type: 'e2e', to, roomType, roomId,
        data: encryptEnvelope(key, inner)
    }));
}
// 发送二进制分块：先 ws-chunk-prep 声明接收方，再发原始二进制帧。
function sendBinary(c, key, to, bytes) {
    c.ws.send(JSON.stringify({ type: 'ws-chunk-prep', to }));
    c.ws.send(encryptFrame(key, bytes));
}

// ---- 建立配对 + 派生密钥 ----
const S = LanPairing.generateSecret();
const R = LanPairing.deriveRoomId(S);
const key = LanPairing.deriveKey(S);

const A = makeClient('A'), B = makeClient('B');
await open(A); await open(B);
const cfg = await waitMsg(A, 'ws-config');
ok('ws-config protocolVersion=2', cfg.wsConfig.protocolVersion === 2);

A.ws.send(JSON.stringify({ type: 'pair-device-initiate', roomId: R }));
await waitMsg(A, 'pair-device-initiated');
B.ws.send(JSON.stringify({ type: 'pair-device-join', roomId: R }));
const aJoined = await waitMsg(A, 'pair-device-joined');
const bJoined = await waitMsg(B, 'pair-device-joined');
// A 侧：B 的 peerId = aJoined.peerId；B 侧：A 的 peerId = bJoined.peerId。
const aPeerId = aJoined.peerId, bPeerId = bJoined.peerId;
ok('配对完成（A 侧拿到 B 的 id，B 侧拿到 A 的 id）', !!aPeerId && !!bPeerId);
// 双方都入 secret 房间 R：等各自收到对应 room 宣告。
await waitMsg(B, 'peers').then(m => ok('B 在 secret 房间 R 看到 A', m.roomType === 'secret' && m.roomId === R && m.peers.some(p => p.id === bPeerId)));
await waitMsg(A, 'peer-joined').then(m => ok('A 收到 B 入 secret 房间 R', m.roomType === 'secret' && m.roomId === R && m.peer.id === aPeerId));

// 双方也都在 ip 房间（模拟真实客户端连接后 join-ip-room）。
A.ws.send(JSON.stringify({ type: 'join-ip-room' }));
B.ws.send(JSON.stringify({ type: 'join-ip-room' }));
await new Promise(r => setTimeout(r, 150));

console.log('\n[1] e2e 信封经服务器中继（secret 房间路由）');
let e2eCountA = 0, e2eCountB = 0;
sendE2e(A, key, aPeerId, 'secret', R, { type: 'text', text: '机密内容-s-房间' });
const sRelay = await waitNth(B, 'e2e', ++e2eCountB);
const sInner = JSON.parse(new TextDecoder().decode(decryptEnvelope(key, sRelay.data)));
ok('B 解密出明文且一致', sInner.type === 'text' && sInner.text === '机密内容-s-房间');
ok('线上不含明文（文件名/文本不出现在任何消息）', !JSON.stringify(B.messages).includes('机密内容-s-房间'));
// 服务器房间表以 R 为键，搜不到 S
ok('服务器房间表含 R、不含 S', JSON.stringify(wss._rooms).includes(R) && !JSON.stringify(wss._rooms).includes(S));

console.log('\n[2] e2e 信封经服务器中继（ip 房间路由 —— 覆盖 _getRoomTypes()[0]==ip 顺序）');
sendE2e(B, key, bPeerId, 'ip', conf.lanIp, { type: 'text', text: '机密内容-ip-房间' });
const ipRelay = await waitNth(A, 'e2e', ++e2eCountA);
const ipInner = JSON.parse(new TextDecoder().decode(decryptEnvelope(key, ipRelay.data)));
ok('A 解密出明文且一致', ipInner.type === 'text' && ipInner.text === '机密内容-ip-房间');

// ---- 完整传输握手（字节级重建） ----
console.log('\n[3] 完整传输握手：request -> response -> header -> 二进制分块 -> 重建');
// 发送方 A 构造一个 3 MB 伪文件
const fileBytes = new Uint8Array(3 * 1024 * 1024);
for (let i = 0; i < fileBytes.length; i++) fileBytes[i] = (i * 31 + 7) & 0xff; // 确定性伪随机
const fileName = '合同-2026.pdf';
const CHUNK = 256 * 1024;   // 测试用小块（真实前端为 1 MB），多块走同一路径
const totalSize = fileBytes.length;

// A 发起请求（元数据加密）
sendE2e(A, key, aPeerId, 'secret', R, { type: 'request', header: [{ name: fileName, mime: 'application/pdf', size: totalSize }], totalSize, imagesOnly: false, thumbnailDataUrl: '' });
const reqAtB = await waitNth(B, 'e2e', ++e2eCountB);
const requestInner = JSON.parse(new TextDecoder().decode(decryptEnvelope(key, reqAtB.data)));
ok('B 解出 request 元数据', requestInner.type === 'request' && requestInner.header[0].name === fileName && requestInner.totalSize === totalSize);
ok('服务器看得到的中继消息里没有文件名明文', !JSON.stringify(B.messages).includes(fileName));

// B 接受（明文 control message，与前端一致）
B.ws.send(JSON.stringify({ type: 'files-transfer-response', to: bPeerId, roomType: 'secret', roomId: R, accepted: true }));
await waitMsg(A, 'files-transfer-response');

// A 发 header（加密）
sendE2e(A, key, aPeerId, 'secret', R, { type: 'header', size: totalSize, name: fileName, mime: 'application/pdf' });
await waitNth(B, 'e2e', ++e2eCountB);

// A 逐块发送二进制分块
for (let off = 0; off < fileBytes.length; off += CHUNK) {
    sendBinary(A, key, aPeerId, fileBytes.slice(off, off + CHUNK));
}

// B 等所有分块到达并解密重建
await new Promise(r => setTimeout(r, 800));
let receivedBytes = 0;
const rebuilt = [];
for (const chunk of B.binaryChunks) {
    // 服务器用 ws-chunk-prep 的 from 标记二进制帧的真实来源（B 侧看到的是 A 的 id）。
    ok('二进制帧 from 路由正确（指向发送方 A）', chunk.from === bPeerId);
    const pt = decryptFrame(key, chunk.data);
    if (pt) { rebuilt.push(Buffer.from(pt)); receivedBytes += pt.length; }
    else { ok('每个分块都可解密', false); }
}
const rebuiltBuf = Buffer.concat(rebuilt);
ok('接收字节数 == 发送字节数', receivedBytes === totalSize, `(${receivedBytes} / ${totalSize})`);
ok('重建内容与原文字节级一致', rebuiltBuf.equals(Buffer.from(fileBytes)));

// A 发 partition 结束控制，B 回 partition-received（都加密）
sendE2e(A, key, aPeerId, 'secret', R, { type: 'partition', offset: totalSize });
await waitNth(B, 'e2e', ++e2eCountB);
sendE2e(B, key, bPeerId, 'secret', R, { type: 'partition-received', offset: totalSize });
const prAtA = await waitNth(A, 'e2e', ++e2eCountA);
const prInner = JSON.parse(new TextDecoder().decode(decryptEnvelope(key, prAtA.data)));
ok('partition-received 加密往返一致', prInner.type === 'partition-received' && prInner.offset === totalSize);

console.log('\n结果: ' + passed + ' 通过, ' + failed + ' 失败');
process.exit(failed ? 1 : 0);
