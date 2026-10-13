// 传输吞吐基准（走真实服务器中继 + 真实 ChaCha20-Poly1305 加解密）。
//
// 用途：改动传输路径（分块大小 / partition 大小 / 发送端流控）时，先在这里量出
// MB/s 再决定，而不是凭感觉调参——仓库里 1MB 分块这个选择当年就是实测出来的。
//
// 说明：本基准跑在本地回环上，网络不是瓶颈，量的是**代码路径**（每块加密 +
// WebSocket 组帧 + 服务器中继 + 解密）的吞吐。真机上的绝对数字会低很多，但
// 各种参数之间的相对高低是一致的。
//
// 运行：node throughput-bench.mjs [总MB]
import fs from 'fs';
import WebSocket from 'ws';

import LanProjectsServer from '../server/server.js';
import LanProjectsWsServer from '../server/ws-server.js';

global.window = globalThis;
(0, eval)(fs.readFileSync('../public/scripts/libs/chacha20.js', 'utf8'));
(0, eval)(fs.readFileSync('../public/scripts/libs/crypto-util.js', 'utf8'));
(0, eval)(fs.readFileSync('../public/scripts/network.js', 'utf8'));

const { LanPairing, Chacha } = window;

const TOTAL_MB = Number(process.argv[2] || 64);

const conf = { debugMode: false, port: 0, wsFallback: true, ipv6Localize: false, rateLimit: false, lanIp: '192.168.0.26' };
const httpServer = new LanProjectsServer(conf).server;
const wss = new LanProjectsWsServer(httpServer, conf);
while (!httpServer.address()) await new Promise(r => setTimeout(r, 10));
const port = httpServer.address().port;

function makeClient(name) {
    const c = { name, received: 0, ws: null, prepFrom: null, onBinary: null };
    c.ws = new WebSocket(`ws://127.0.0.1:${port}`);
    c.ws.binaryType = 'arraybuffer';
    c.ws.on('message', (data, isBinary) => {
        if (isBinary) {
            if (c.onBinary) c.onBinary(data);
            return;
        }
        const msg = JSON.parse(data.toString());
        if (msg.type === 'ws-chunk-prep') c.prepFrom = msg.from;
        if (msg.type === 'peers') c.others = msg.peers.map(p => p.id);
        if (msg.type === 'peer-joined') c.otherId = msg.peer.id;
        if (msg.type === 'pair-device-joined') c.otherId = msg.peerId;
        if (msg.type === 'ws-chunk-pause') c.paused = true;
        if (msg.type === 'ws-chunk-resume') c.paused = false;
    });
    return c;
}
const open = c => new Promise(resolve => c.ws.on('open', resolve));
const nextTick = () => new Promise(r => setImmediate(r));
const sleep = ms => new Promise(r => setTimeout(r, ms));

const key = LanPairing.deriveSessionKey(LanPairing.generateSecret(), LanPairing.generateSessionNonce());
const CHUNK = 1048576;
const fileBytes = new Uint8Array(TOTAL_MB * 1024 * 1024);
for (let i = 0; i < fileBytes.length; i += 4096) fileBytes[i] = i & 0xff;

function encryptFrame(chunk) {
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const sealed = Chacha.chacha20poly1305(key, nonce).encrypt(chunk);
    const frame = new Uint8Array(12 + sealed.length);
    frame.set(nonce);
    frame.set(sealed, 12);
    return frame;
}

/**
 * 跑一次传输。
 * @param chunkBytes     每个二进制帧的字节数
 * @param partitionBytes 每个 partition 的字节数（决定 ws-chunk-prep 授权批次大小）
 * @param paceBytes      发送端 socket 缓冲超过该值时等待（0 = 不做发送端流控）
 */
async function run({ chunkBytes, partitionBytes, paceBytes }) {
    const A = makeClient('A');
    const B = makeClient('B');
    await Promise.all([open(A), open(B)]);
    // 两个客户端都要进 IP 房间，服务器才有房间可路由（否则 ws-chunk-prep 找不到
    // 收件人，二进制帧永远收不到，基准会直接挂住）。
    A.ws.send(JSON.stringify({ type: 'join-ip-room' }));
    B.ws.send(JSON.stringify({ type: 'join-ip-room' }));
    // 先入房的一方从 'peer-joined' 得知对方，后入房的一方从 'peers' 列表得知。
    const otherId = () => A.otherId || (A.others && A.others[0]);
    for (let i = 0; i < 300 && !otherId(); i++) await sleep(10);
    if (!otherId()) throw new Error('A 没有看到 B，IP 房间未建立');

    const senderId = otherId();
    let received = 0;
    let done = null;
    const finished = new Promise(r => done = r);
    B.onBinary = data => {
        const view = new Uint8Array(data);
        Chacha.chacha20poly1305(key, view.subarray(0, 12)).decrypt(view.subarray(12));
        received += view.length - 28;
        if (received >= fileBytes.length) done();
    };

    const chunksPerPartition = Math.max(1, Math.min(Math.ceil(partitionBytes / chunkBytes), 64));
    let grantLeft = 0;

    const t0 = performance.now();
    for (let off = 0; off < fileBytes.length; off += chunkBytes) {
        if (grantLeft <= 0) {
            A.ws.send(JSON.stringify({
                type: 'ws-chunk-prep', to: senderId, roomType: 'ip', count: chunksPerPartition
            }));
            grantLeft = chunksPerPartition;
        }
        grantLeft--;
        A.ws.send(encryptFrame(fileBytes.subarray(off, Math.min(off + chunkBytes, fileBytes.length))), { binary: true });

        if (paceBytes > 0) {
            while (A.ws.bufferedAmount > paceBytes) await sleep(5);
        } else {
            await nextTick();
        }
    }
    await finished;
    const dt = (performance.now() - t0) / 1000;

    A.ws.close();
    B.ws.close();
    return { mbps: (fileBytes.length / 1048576) / dt, seconds: dt };
}

console.log(`服务器: ws://127.0.0.1:${port} | 传输总量: ${TOTAL_MB} MB | 分块: 1 MB\n`);
console.log('配置                                | 吞吐      | 用时');
console.log('------------------------------------|-----------|--------');

const configs = [
    { label: '1MB 分块 / partition 10MB（当前实现）', chunkBytes: 1 * 1024 * 1024, partitionBytes: 10e6, paceBytes: 0 },
    { label: '512KB 分块 / partition 10MB', chunkBytes: 512 * 1024, partitionBytes: 10e6, paceBytes: 0 },
    { label: '2MB 分块 / partition 10MB', chunkBytes: 2 * 1024 * 1024, partitionBytes: 10e6, paceBytes: 0 },
    { label: '4MB 分块 / partition 10MB', chunkBytes: 4 * 1024 * 1024, partitionBytes: 10e6, paceBytes: 0 },
    { label: '1MB 分块 / partition 32MB', chunkBytes: 1 * 1024 * 1024, partitionBytes: 32e6, paceBytes: 0 },
    { label: '1MB 分块 + 发送端缓冲限 4MB', chunkBytes: 1 * 1024 * 1024, partitionBytes: 10e6, paceBytes: 4 * 1024 * 1024 },
];

for (const cfg of configs) {
    // 每档跑两次取较快的一次，抵消首次运行的 JIT/预热差异
    const r1 = await run(cfg);
    const r2 = await run(cfg);
    const best = r1.mbps > r2.mbps ? r1 : r2;
    console.log(`${cfg.label.padEnd(35)} | ${best.mbps.toFixed(0).padStart(6)} MB/s | ${best.seconds.toFixed(2)}s`);
}

httpServer.close();
process.exit(0);
