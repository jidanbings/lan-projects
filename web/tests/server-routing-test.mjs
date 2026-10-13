// ServerConnection 收包路由回归测试。
//
// 背景：e2e 加密信封（request/header/partition/text/e2e-confirm 的外层壳）由服务器
// 中继到接收方 WebSocket，但在 ServerConnection._onMessage 的 switch 里曾缺少
// case 'e2e'，导致所有加密信封在收包第一层就被当成 unknown message 丢弃——文件
// 传输彻底失效，而配对状态仍显示成功。本测试直接调用真实的 ServerConnection._onMessage，
// 锁定"e2e 必须转发到 ws-relay（进而由 Peer._onMessage 解密分派）"。
//
// 运行：node server-routing-test.mjs
import fs from 'fs';

global.window = globalThis;

// Events 的最小桩：network.js 通过裸标识 Events 引用它（真实实现是 ui-main.js 里
// 基于 window.dispatchEvent 的类）。这里用与真实类相同的 fire/on/off 签名，把
// fire 的载荷记下来供断言。
const fired = [];
global.Events = {
    fire: (type, detail) => fired.push({ type, detail }),
    on: () => {},
    off: () => {}
};

(0, eval)(fs.readFileSync('../public/scripts/libs/chacha20.js', 'utf8'));
(0, eval)(fs.readFileSync('../public/scripts/libs/crypto-util.js', 'utf8'));
// 顶层 class 声明不会跨间接 eval 泄漏（var 会、class 不会），因此在同一次 eval 的
// 末尾把 ServerConnection 挂到 globalThis 上取出来。
(0, eval)(fs.readFileSync('../public/scripts/network.js', 'utf8')
    + '\n;globalThis.__SC = ServerConnection;');
const ServerConnection = globalThis.__SC;

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) { passed++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
    else { failed++; console.log('  ✗ FAIL: ' + name); }
};

// 构造一个不跑构造函数的 ServerConnection（只测收包路由）。
function makeSC(wsFallback = true) {
    const sc = Object.create(ServerConnection.prototype);
    sc._wsConfig = { wsFallback };
    return sc;
}

console.log('[1] e2e 信封必须转发到 ws-relay（修复核心断言）');
{
    const sc = makeSC(true);
    const e2e = { type: 'e2e', data: '3I/hFEnzl9ZAduaysVMRnSOuA9S5u/LNuI4jxtam6f8c0mlhZh', roomType: 'ip', roomId: '10.194.78.119', sender: { id: 'peer-b' } };
    fired.length = 0;
    sc._onMessage(JSON.stringify(e2e));
    const relay = fired.find(f => f.type === 'ws-relay');
    ok('e2e 触发 ws-relay', !!relay);
    ok('ws-relay 载荷就是原 e2e 消息（Peer._onMessage 据此解密分派）', !!relay && JSON.parse(relay.detail).type === 'e2e' && JSON.parse(relay.detail).sender.id === 'peer-b');
    ok('没有再落入 unknown message（没有别的异常事件）', fired.every(f => f.type === 'ws-relay'));
}

console.log('[2] 其它 ws-fallback 中继类型照常转发');
{
    const sc = makeSC(true);
    const types = ['request', 'header', 'partition', 'partition-received', 'progress',
        'files-transfer-response', 'file-transfer-complete', 'message-transfer-complete',
        'text', 'display-name-changed', 'ws-chunk'];
    for (const type of types) {
        const msg = { type, data: 'x', roomType: 'secret', roomId: 'r', sender: { id: 'peer-b' } };
        fired.length = 0;
        sc._onMessage(JSON.stringify(msg));
        const relay = fired.find(f => f.type === 'ws-relay');
        ok(`${type} 触发 ws-relay`, !!relay, relay ? `(relay=${relay.detail.length}B)` : '');
    }
}

console.log('[3] 路由类消息走各自事件');
{
    const sc = makeSC(true);
    fired.length = 0;
    sc._onMessage(JSON.stringify({ type: 'signal', sender: { id: 'x' } }));
    ok('signal 触发 signal 事件', fired.some(f => f.type === 'signal'));
    fired.length = 0;
    sc._onMessage(JSON.stringify({ type: 'peers', peers: [], roomType: 'secret', roomId: 'r' }));
    ok('peers 触发 peers 事件', fired.some(f => f.type === 'peers'));
}

console.log('[4] wsFallback=false 时 e2e 不转发（WebRTC 直连模式下服务器本就不该中继）');
{
    const sc = makeSC(false);
    fired.length = 0;
    sc._onMessage(JSON.stringify({ type: 'e2e', data: 'x', roomType: 'secret', roomId: 'r', sender: { id: 'b' } }));
    ok('不触发 ws-relay', !fired.some(f => f.type === 'ws-relay'));
}

console.log('\n结果: ' + passed + ' 通过, ' + failed + ' 失败');
process.exit(failed ? 1 : 0);
