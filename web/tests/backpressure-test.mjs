// 传输背压（接收端积压）行为测试。
//
// 背景：旧实现在接收端 socket 缓冲超过 8MB 时**直接丢掉这个数据帧**。接收端的
// 文件于是缺了一块，直到整个文件传完比对大小才发现不符、整次传输作废——用户
// 看到的是"传到一半失败了"，日志里只有一行 BACKPRESSURE。现在改为让发送方暂停，
// 由接收端的实际处理速度决定节奏；只有在对方是旧客户端、缓冲冲过硬上限时才退回
// 丢弃，以免把跑在手机上的服务器进程撑爆。
//
// 本测试直接用假 socket 驱动真实的 LanProjectsWsServer 方法（不起网络），
// 并验证文件读取侧的暂停/恢复语义。
//
// 运行：node backpressure-test.mjs
import fs from 'fs';
import { createServer } from 'http';

import LanProjectsWsServer from '../server/ws-server.js';

global.window = globalThis;
(0, eval)(fs.readFileSync('../public/scripts/libs/chacha20.js', 'utf8'));
(0, eval)(fs.readFileSync('../public/scripts/libs/crypto-util.js', 'utf8'));
(0, eval)(fs.readFileSync('../public/scripts/network.js', 'utf8') + '\n;globalThis.__FileChunker = FileChunker;');

const FileChunker = globalThis.__FileChunker;

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) { passed++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
    else { failed++; console.log('  ✗ FAIL: ' + name + (extra ? '  ' + extra : '')); }
};

const conf = { debugMode: false, wsFallback: true, ipv6Localize: false, rateLimit: false, lanIp: '192.168.0.26' };
const wss = new LanProjectsWsServer(createServer(), conf);

const SENDER_ID = '11111111-1111-1111-1111-111111111111';
const RECIPIENT_ID = '22222222-2222-2222-2222-222222222222';

const OPEN = 1;
function fakePeer(id, bufferedAmount) {
    const peer = {
        id,
        sent: [],
        delayed: [],
        pausedForBackpressure: false,
        // _disconnect 会遍历这些状态（真实 Peer 上的字段）
        roomSecrets: [],
        publicRoomId: null,
        pendingPairRoom: null,
        socket: {
            OPEN,
            readyState: OPEN,
            bufferedAmount,
            send(data) { peer.sent.push(data); },
            terminate() {}
        },
        _setBuffered(v) { peer.socket.bufferedAmount = v; },
        _messages() { return peer.sent.filter(s => typeof s === 'string').map(s => JSON.parse(s)); },
        _frameCount() { return peer.sent.filter(s => typeof s !== 'string').length; }
    };
    return peer;
}

const bytes = new Uint8Array(1024).fill(7);
const settle = ms => new Promise(r => setTimeout(r, ms));

function setup(recipientBuffered) {
    const sender = fakePeer(SENDER_ID, 0);
    const recipient = fakePeer(RECIPIENT_ID, recipientBuffered);
    wss._peersById = {[SENDER_ID]: sender, [RECIPIENT_ID]: recipient};
    wss._pendingBinaryTo = {};
    return {sender, recipient};
}

console.log('[1] 接收端不积压：正常转发，不下发暂停');
{
    const {sender, recipient} = setup(0);
    wss._onWsChunkPrep(sender, {to: RECIPIENT_ID, count: 3});
    wss._onBinaryChunk(sender, bytes);
    ok('数据帧被转发', recipient._frameCount() === 1);
    ok('没有给发送方发暂停', sender._messages().every(m => m.type !== 'ws-chunk-pause'));
    ok('发送方未被标记为暂停', sender.pausedForBackpressure === false);
}

console.log('\n[2] 接收端积压：不丢帧，改为让发送方暂停');
{
    const {sender, recipient} = setup(10 * 1024 * 1024);   // 超过 8MB 阈值
    wss._onWsChunkPrep(sender, {to: RECIPIENT_ID, count: 3});
    ok('授权时就下发了暂停指令', sender._messages().some(m => m.type === 'ws-chunk-pause'));
    ok('发送方被标记为暂停', sender.pausedForBackpressure === true);

    const before = recipient._frameCount();
    wss._onBinaryChunk(sender, bytes);
    wss._onBinaryChunk(sender, bytes);
    ok('积压时数据帧仍然全部转发（不再丢帧）', recipient._frameCount() === before + 2);
    ok('暂停指令只发一次，不刷屏',
        sender._messages().filter(m => m.type === 'ws-chunk-pause').length === 1);
}

console.log('\n[3] 接收端消化完：下发恢复指令');
{
    const {sender, recipient} = setup(10 * 1024 * 1024);
    wss._onWsChunkPrep(sender, {to: RECIPIENT_ID, count: 3});
    recipient._setBuffered(1 * 1024 * 1024);               // 降到低水位以下
    await settle(200);                                     // 等轮询发现
    ok('下发恢复指令', sender._messages().some(m => m.type === 'ws-chunk-resume'));
    ok('发送方解除暂停', sender.pausedForBackpressure === false);
}

console.log('\n[4] 旧客户端不认暂停指令：冲到硬上限时才退回丢弃（保住服务器内存）');
{
    const {sender, recipient} = setup(40 * 1024 * 1024);   // 超过 32MB 硬上限
    wss._onWsChunkPrep(sender, {to: RECIPIENT_ID, count: 3});
    const before = recipient._frameCount();
    wss._onBinaryChunk(sender, bytes);
    ok('硬上限之上仍然丢弃以保护进程', recipient._frameCount() === before);
}

console.log('\n[5] 断开连接后不再有残留的暂停状态');
{
    const {sender, recipient} = setup(10 * 1024 * 1024);
    wss._onWsChunkPrep(sender, {to: RECIPIENT_ID, count: 3});
    ok('（前提）已暂停', sender.pausedForBackpressure === true);
    wss._keepAliveTimers = {};
    wss._disconnect(sender);
    ok('断开后暂停标志被清掉', sender.pausedForBackpressure === false);
}

console.log('\n[6] 文件读取侧：暂停时不再读新块，恢复后继续');
{
    const reads = [];
    const chunker = Object.create(FileChunker.prototype);
    chunker._chunkSize = 1024;
    chunker._maxPartitionSize = 1024 * 1024;
    chunker._offset = 0;
    chunker._partitionSize = 0;
    chunker._paused = false;
    chunker._file = {size: 4096, slice: (from, to) => ({from, to})};
    chunker._reader = {readAsArrayBuffer: () => reads.push(chunker._offset)};
    chunker._onChunk = () => {};
    chunker._onPartitionEnd = () => {};

    chunker._readChunk();
    ok('正常状态下会读取', reads.length === 1);

    chunker.pause();
    chunker._readChunk();
    ok('暂停后不再发起新的读取', reads.length === 1);

    chunker.resume();
    ok('恢复后继续读取', reads.length === 2);

    chunker._offset = 4096;      // 已到文件末尾
    chunker.pause();
    reads.length = 0;
    chunker.resume();
    ok('文件已读完时恢复不会多读一块', reads.length === 0);
}

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
