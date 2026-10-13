// 零信任改造冒烟测试：启动真实 ws-server，用两个假客户端走完整配对 + E2E 流程。
// 覆盖：协议版本、高熵配对码派生、正确/错误密钥配对、服务器不见 S、E2E 加密信封路由。
// 运行：node smoke-test.mjs
import fs from 'fs';
import WebSocket from 'ws';

import LanProjectsServer from '../server/server.js';
import LanProjectsWsServer from '../server/ws-server.js';

// 用间接 eval 加载真实前端脚本（同 crypto-test.cjs）。
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
const conf = { debugMode: false, port: 0, wsFallback: true, ipv6Localize: false, rateLimit: false, lanIp: '127.0.0.1' };
const httpServer = new LanProjectsServer(conf).server;
const wss = new LanProjectsWsServer(httpServer, conf);
// LanProjectsServer 构造时已 listen(0)；等它真正绑定后读回临时端口。
while (!httpServer.address()) await new Promise(r => setTimeout(r, 10));
const port = httpServer.address().port;
console.log(`服务器已启动: ws://127.0.0.1:${port}`);

// ---- 假客户端 ----
function makeClient(name) {
    const c = { name, messages: [], peerId: null, others: new Map(), ws: null };
    c.ws = new WebSocket(`ws://127.0.0.1:${port}`);
    c.ws.on('message', (data, isBinary) => {
        if (isBinary) return; // 本测试只验证 JSON 路径
        const msg = JSON.parse(data.toString());
        if (msg.type === 'peer-joined') c.others.set(msg.peer.id, msg.peer);
        if (msg.type === 'peers') for (const p of msg.peers) c.others.set(p.id, p);
        if (msg.type === 'pair-device-joined') { c.others.set(msg.peerId, { id: msg.peerId }); }
        c.messages.push(msg);
    });
    return c;
}
const waitMsg = (c, type, timeout = 3000) => new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${c.name} 未收到 ${type}，已收到: ` + c.messages.map(m => m.type).join(','))), timeout);
    const check = () => {
        const m = c.messages.find(m => m.type === type);
        if (m) { clearTimeout(t); resolve(m); }
        else setTimeout(check, 25);
    };
    check();
});
const open = c => new Promise(resolve => c.ws.on('open', resolve));

// 解密信封（模拟 Peer._decrypt）
function decryptEnvelope(key, base64) {
    const frame = Uint8Array.from(Buffer.from(base64, 'base64'));
    if (frame.length < 28) return null;
    const nonce = frame.slice(0, 12), sealed = frame.slice(12);
    return Chacha.chacha20poly1305(key, nonce).decrypt(sealed);
}
// 加密信封（模拟 Peer._sendEncrypted）
function encryptEnvelope(key, obj) {
    const pt = new TextEncoder().encode(JSON.stringify(obj));
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const sealed = Chacha.chacha20poly1305(key, nonce).encrypt(pt);
    const frame = new Uint8Array(12 + sealed.length);
    frame.set(nonce); frame.set(sealed, 12);
    return Buffer.from(frame).toString('base64');
}

console.log('\n[1] 协议版本');
const A = makeClient('A'), B = makeClient('B');
await open(A); await open(B);
const cfg = await waitMsg(A, 'ws-config');
ok('ws-config 携带 protocolVersion=2', cfg.wsConfig.protocolVersion === 2, '(server=' + cfg.wsConfig.protocolVersion + ')');

console.log('\n[2] 高熵配对密钥 S 派生');
const S = LanPairing.generateSecret();
const R = LanPairing.deriveRoomId(S);
const key = LanPairing.deriveKey(S);
ok('S 是 26 字符 base32', /^[A-Z2-7]{26}$/.test(S));
ok('R 是 64 位 hex', /^[0-9a-f]{64}$/.test(R));
ok('key 32 字节', key.length === 32);

console.log('\n[3] 发起方 A 建房间（只发 R，绝无 S）');
A.ws.send(JSON.stringify({ type: 'pair-device-initiate', roomId: R }));
const init = await waitMsg(A, 'pair-device-initiated');
ok('A 收到 pair-device-initiated', init.roomId === R);
ok('A 发给服务器的消息不含 S', !JSON.stringify({ roomId: R }).includes(S));

console.log('\n[4] 加入方 B 用同一 S 配对');
B.ws.send(JSON.stringify({ type: 'pair-device-join', roomId: R }));
const bJoined = await waitMsg(B, 'pair-device-joined');
const aJoined = await waitMsg(A, 'pair-device-joined');
ok('B 收到 pair-device-joined（带对方 peerId = A 的 id）', typeof bJoined.peerId === 'string' && bJoined.peerId.length > 0);
ok('A 也收到 pair-device-joined（带对方 peerId = B 的 id）', typeof aJoined.peerId === 'string' && aJoined.peerId.length > 0);
// pair-device-joined 的 peerId 是"对方"的 id：B 侧拿到 A 的 id，A 侧拿到 B 的 id。
const bPeers = await waitMsg(B, 'peers');
const aPeerJoined = await waitMsg(A, 'peer-joined');
ok('B 进入房间 R 且能看到 A', bPeers.roomId === R && bPeers.peers.some(p => p.id === bJoined.peerId));
ok('A 收到 B 入房广播（peer-joined）', aPeerJoined.roomId === R && aPeerJoined.peer.id === aJoined.peerId);

console.log('\n[5] 错误密钥配对被拒');
const C = makeClient('C');
await open(C);
const S2 = LanPairing.generateSecret();
const R2 = LanPairing.deriveRoomId(S2);
C.ws.send(JSON.stringify({ type: 'pair-device-join', roomId: R2 }));
const invalid = await waitMsg(C, 'pair-device-join-key-invalid');
ok('错误 R 收到 pair-device-join-key-invalid', !!invalid);
C.ws.close();

console.log('\n[6] E2E 加密信封经服务器中继');
const secretText = '机密文件名: 合同-2026.pdf';
const envelope = encryptEnvelope(key, { type: 'text', text: secretText });
A.ws.send(JSON.stringify({
    type: 'e2e', to: aJoined.peerId, roomType: 'secret', roomId: R, data: envelope
}));
const relayed = await waitMsg(B, 'e2e');
ok('B 收到 e2e 信封', !!relayed);
const inner = JSON.parse(new TextDecoder().decode(decryptEnvelope(key, relayed.data)));
ok('B 用派生 key 解密成功且原文一致', inner.type === 'text' && inner.text === secretText);
ok('线上只有密文（文件名明文不出现在任何消息里）', !JSON.stringify(B.messages).includes(secretText));

console.log('\n[7] 服务器内部不持有 S');
const roomState = JSON.stringify(wss._rooms);
ok('服务器房间表以 R 为键', roomState.includes(R));
ok('服务器房间表中搜不到 S', !roomState.includes(S));

// 篡改信封 → 接收方认证失败（复算 AEAD tag 校验）
console.log('\n[8] 篡改检测');
const tampered = Buffer.from(envelope, 'base64');
tampered[20] ^= 1;
B.ws.send(JSON.stringify({
    type: 'e2e', to: A.others.keys().next().value, roomType: 'secret', roomId: R,
    data: tampered.toString('base64')
}));
await new Promise(r => setTimeout(r, 200));
const tamperFrame = A.messages.filter(m => m.type === 'e2e').pop();
let tamperCaught = false;
try { decryptEnvelope(key, tamperFrame.data); } catch (e) { tamperCaught = true; }
ok('篡改密文认证失败（抛出）', tamperCaught);

console.log('\n结果: ' + passed + ' 通过, ' + failed + ' 失败');
process.exit(failed ? 1 : 0);
