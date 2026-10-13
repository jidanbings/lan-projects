// 点选配对握手的**服务器中继**测试：真实 ws-server + 两个真实 WebSocket 客户端。
//
// 为什么单独测这一层：真实 DOM 的仿真曾经绕过服务器（测试自己把消息递给对方），
// 于是"消息其实没被中继"这种情况完全测不出来；而点选配对的第一条消息必须在双方
// **还没有任何共享密钥**时就能送达，走的是服务器的按房间转发通道。
//
// 运行：node click-pair-relay-test.mjs
import WebSocket from 'ws';
import LanProjectsServer from '../server/server.js';
import LanProjectsWsServer from '../server/ws-server.js';

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) { passed++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
    else { failed++; console.log('  ✗ FAIL: ' + name + (extra ? '  ' + extra : '')); }
};

const conf = {debugMode: false, port: 0, wsFallback: true, ipv6Localize: false, rateLimit: false, lanIp: '192.168.0.26'};
const httpServer = new LanProjectsServer(conf).server;
new LanProjectsWsServer(httpServer, conf);
while (!httpServer.address()) await new Promise(r => setTimeout(r, 10));
const port = httpServer.address().port;

function makeClient(name) {
    const c = {name, received: [], peerId: null, others: [], ws: null};
    c.ws = new WebSocket(`ws://127.0.0.1:${port}`);
    c.ws.on('message', data => {
        const msg = JSON.parse(data.toString());
        c.received.push(msg);
        if (msg.type === 'display-name') c.peerId = msg.peerId;
        if (msg.type === 'peers') c.others = msg.peers.map(p => p.id);
        if (msg.type === 'peer-joined') c.others.push(msg.peer.id);
    });
    return c;
}
const open = c => new Promise(r => c.ws.on('open', r));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const got = (c, type) => c.received.filter(m => m.type === type);

const A = makeClient('A'), B = makeClient('B');
await Promise.all([open(A), open(B)]);
A.ws.send(JSON.stringify({type: 'join-ip-room'}));
B.ws.send(JSON.stringify({type: 'join-ip-room'}));
await sleep(300);

console.log(`服务器 ws://127.0.0.1:${port}`);
ok('两台客户端互相可见（IP 房间）', A.others.includes(B.peerId) && B.others.includes(A.peerId));
ok('peerId 是合法 UUID（服务器转发的前提）',
    /^([0-9]|[a-f]){8}-(([0-9]|[a-f]){4}-){3}([0-9]|[a-f]){12}$/.test(A.peerId));

console.log('\n[1] 四条握手消息都会被中继');
const roundTrip = async (type, from, to, payload) => {
    to.received.length = 0;
    from.ws.send(JSON.stringify({...payload, type, to: to.peerId, roomType: 'ip'}));
    await sleep(150);
    return got(to, type);
};

const req = await roundTrip('pair-click-request', A, B, {pk: 'aa'.repeat(32)});
ok('pair-click-request 送达', req.length === 1);
ok('公钥原样送达', req.length === 1 && req[0].pk === 'aa'.repeat(32));
ok('带上了发送方标识（接收方据此判断来源）', req.length === 1 && req[0].sender.id === A.peerId);

const acc = await roundTrip('pair-click-accept', B, A, {pk: 'bb'.repeat(32)});
ok('pair-click-accept 送达', acc.length === 1 && acc[0].pk === 'bb'.repeat(32));

const conf1 = await roundTrip('pair-click-confirm', A, B, {data: 'xx'});
ok('pair-click-confirm 送达', conf1.length === 1);

const rej = await roundTrip('pair-click-reject', B, A, {reason: 'busy'});
ok('pair-click-reject 送达', rej.length === 1);
ok('拒绝原因原样送达', rej.length === 1 && rej[0].reason === 'busy');

console.log('\n[2] 旧版客户端兼容性：消息不会被发给不在房间里的人');
{
    B.received.length = 0;
    A.ws.send(JSON.stringify({type: 'pair-click-request', to: '99999999-9999-9999-9999-999999999999', roomType: 'ip', pk: 'cc'.repeat(32)}));
    await sleep(150);
    ok('陌生 peerId 不会误投给房间里的其他人', got(B, 'pair-click-request').length === 0);
}

console.log('\n[3] 配对取消会通知对方（旧实现只通知取消者本人）');
{
    A.ws.send(JSON.stringify({type: 'pair-device-initiate', roomId: 'ab'.repeat(32)}));
    await sleep(150);
    B.received.length = 0;
    // B 也进同一个待配对房间，然后 A 取消
    B.ws.send(JSON.stringify({type: 'pair-device-join', roomId: 'ab'.repeat(32)}));
    await sleep(200);
    A.ws.send(JSON.stringify({type: 'pair-device-cancel', roomId: 'ab'.repeat(32)}));
    await sleep(250);
    ok('仍在等待的一方收到 pair-device-peer-canceled', got(B, 'pair-device-peer-canceled').length === 1);
    ok('取消方自己也收到 pair-device-canceled', got(A, 'pair-device-canceled').length === 1);
}

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
A.ws.close(); B.ws.close(); httpServer.close();
process.exit(failed ? 1 : 0);
