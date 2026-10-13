// 双端握手回归测试：用**真实的 Peer/WSPeer 类**跑完整会话建立过程。
//
// 背景（这是一个真实缺陷的回归锁）：会话 nonce 曾经被装在"用它自己派生出来的
// 密钥"加密的确认包里传递 —— 鸡生蛋，对方手里没有 nonce 就推不出那把密钥，
// 也就永远解不开装着 nonce 的包。后果是两台已配对的设备各自停在不同的密钥上
// （一方 HKDF(S,nonce)、另一方 HKDF(S)），每个数据帧 AEAD 认证失败被丢弃，
// 而界面仍显示"配对成功"、配对状态一切正常。crypto-test / smoke-test /
// transfer-test 都只覆盖密码学原语与中继路由，没有实例化 Peer，所以没有拦住它。
//
// 本测试覆盖：
//   [1] 四种 (isCaller, isCaller) 组合下双方都能收敛到同一把会话密钥
//   [2] 会话密钥不等于裸 HKDF(S)（确实用上了 nonce 与临时 ECDH）
//   [3] 真实文件帧加解密一致（1MB）
//   [4] 每会话临时密钥（前向保密）双方完成、临时私钥用完即销毁
//   [5] 会话隔离：另一对设备解不开本会话的数据
//   [6] 韧性：PCS 的 ready 包丢失时仍能双向通信，且接收方会自行补上换钥
//   [7] 篡改确认包 -> 认证失败并提示，不会静默进入可传状态
//   [8] 点选配对：SAS 双方一致、S' 双方一致且合法、中间人场景 SAS 不同
//
// 运行：node pairing-handshake-test.mjs
import fs from 'fs';

const REPO = '..';
global.window = globalThis;

const fired = [];
global.Events = {
    fire: (type, detail) => fired.push({ type, detail }),
    on: () => {},
    off: () => {}
};
global.Localization = { getTranslation: key => key };
global.PersistentStorage = {
    getRoomSecretEntry: async () => undefined,
    getAllRoomSecretEntries: async () => [],
    updateRoomSecretLastConnected: async () => undefined,
    logBrowserNotCapable: () => {}
};
global.document = { getElementById: () => null };
global.BrowserTabsConnector = { peerIsSameBrowser: () => false };
global.sessionStorage = { getItem: () => null, setItem: () => {} };
// util.js 里的两个转换助手（deferred script，这里直接桩掉）
global.arrayBufferToBase64 = b => Buffer.from(b).toString('base64');
global.base64ToArrayBuffer = s => Uint8Array.from(Buffer.from(s, 'base64')).buffer;

for (const file of ['libs/chacha20.js', 'libs/crypto-util.js', 'libs/x25519.js']) {
    (0, eval)(fs.readFileSync(`${REPO}/public/scripts/${file}`, 'utf8'));
}
// 顶层 class 声明不跨间接 eval 泄漏（var 会、class 不会），因此在同一次 eval
// 的末尾把需要的类挂到 globalThis 上取出来。
(0, eval)(fs.readFileSync(`${REPO}/public/scripts/network.js`, 'utf8')
    + '\n;globalThis.__WSPeer = WSPeer;');

const WSPeer = globalThis.__WSPeer;
const { LanPairing } = window;

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) {
        passed++;
        console.log('  ✓ ' + name + (extra ? '  ' + extra : ''));
    } else {
        failed++;
        console.log('  ✗ FAIL: ' + name + (extra ? '  ' + extra : ''));
    }
};

/**
 * 让两台真实 WSPeer 通过一个"假服务器"互发消息，跑完整会话建立。
 * dropTypes 里的消息类型会被丢弃，用于模拟丢包。
 */
function handshake({callerA = false, callerB = true, dropTypes = []} = {}) {
    const secret = LanPairing.generateSecret();
    const roomId = LanPairing.deriveRoomId(secret);
    LanPairing.secretMap.set(roomId, secret);

    const sentA = [], sentB = [];
    const peerA = new WSPeer({send: m => sentA.push(m), sendBinary: () => {}}, callerA, 'B', 'secret', roomId);
    const peerB = new WSPeer({send: m => sentB.push(m), sendBinary: () => {}}, callerB, 'A', 'secret', roomId);

    const delivered = {A: 0, B: 0};
    let rounds = 0;
    while ((sentA.length || sentB.length) && rounds++ < 40) {
        const fromA = sentA.shift();
        if (fromA) delivered.A += relay(fromA, peerB, 'A');
        const fromB = sentB.shift();
        if (fromB) delivered.B += relay(fromB, peerA, 'B');
    }

    function relay(message, target, fromId) {
        if (!message || message.type !== 'e2e') return 0;
        // 解出内层类型用于丢包模拟（测试里直接看信封长度不足以判断，故解一次）
        let innerType = null;
        try {
            const key = fromId === 'A' ? peerA._encryptionKey : peerB._encryptionKey;
            const frame = Buffer.from(message.data, 'base64');
            const plain = window.Chacha.chacha20poly1305(
                key, new Uint8Array(frame.subarray(0, 12))).decrypt(new Uint8Array(frame.subarray(12)));
            innerType = JSON.parse(Buffer.from(plain).toString('utf8')).type;
        } catch (e) {
            // 解不开就按原样投递，由被测代码自己处理
        }
        if (innerType && dropTypes.includes(innerType)) return 0;
        target._onE2e({type: 'e2e', data: message.data, sender: {id: fromId}});
        return 1;
    }

    LanPairing.secretMap.delete(roomId);
    return {peerA, peerB, delivered};
}

const sameKey = (a, b) => Buffer.from(a._encryptionKey).equals(Buffer.from(b._encryptionKey));
const canTalk = (from, to, text = 'x') =>
    to._decrypt(from._encrypt(new TextEncoder().encode(text))) !== null;

console.log('[1] 四种 (isCaller, isCaller) 组合都必须收敛到同一把会话密钥');
for (const callerA of [true, false]) {
    for (const callerB of [true, false]) {
        const {peerA, peerB} = handshake({callerA, callerB});
        const label = `isCaller A=${callerA} B=${callerB}`;
        ok(`${label}：双方密钥一致`, sameKey(peerA, peerB));
        ok(`${label}：A→B 与 B→A 双向可解`, canTalk(peerA, peerB) && canTalk(peerB, peerA));
        ok(`${label}：双方确认握手完成`, peerA._e2eConfirmed === true && peerB._e2eConfirmed === true);
    }
}

console.log('\n[2] 会话密钥确实用上了 nonce 与临时 ECDH');
{
    const {peerA, peerB} = handshake();
    ok('最终密钥 != 握手密钥 K0=HKDF(S)', !Buffer.from(peerA._encryptionKey).equals(Buffer.from(peerA._baseKey)));
    ok('双方派生的会话 nonce 相同', peerA._agreedNonce === peerB._agreedNonce && !!peerA._agreedNonce);
    ok('各自持有的随机 nonce 不同（双方都注入了熵）', peerA._sessionNonce !== peerB._sessionNonce);
}

console.log('\n[3] 真实文件帧（1MB）加解密一致');
{
    const {peerA, peerB} = handshake();
    const payload = new Uint8Array(1024 * 1024);
    for (let i = 0; i < payload.length; i++) payload[i] = i & 0xff;
    const plain = peerB._decrypt(peerA._encrypt(payload));
    ok('1MB 帧解密成功', plain !== null);
    ok('字节级一致', plain !== null && Buffer.from(plain).equals(Buffer.from(payload)));
}

console.log('\n[4] 每会话临时密钥（前向保密）');
{
    const {peerA, peerB} = handshake();
    ok('双方都完成了临时密钥协商', peerA._pcsDone === true && peerB._pcsDone === true);
    ok('临时私钥用完即销毁', peerA._pcsSecretKey === null && peerB._pcsSecretKey === null);
    ok('最终密钥 != 仅由 S 派生的会话密钥 K1',
        !Buffer.from(peerA._encryptionKey).equals(Buffer.from(peerA._prevKey)));
}

console.log('\n[5] 会话隔离：另一对设备解不开本会话数据');
{
    const {peerA} = handshake();
    const other = handshake();
    ok('第三方 B 解不开 A 的帧', !canTalk(peerA, other.peerB));
    ok('第三方 A 解不开对方的帧', !canTalk(other.peerA, handshake().peerB));
}

console.log('\n[6] 韧性：PCS ready 包丢失');
{
    const {peerA, peerB, delivered} = handshake({dropTypes: ['e2e-pcs-ready']});
    ok('ready 包确实被丢弃（测试前提成立）', delivered.A < 3 || delivered.B < 3);
    ok('双方仍能通信（接收方靠 K2 解密这一事实补上换钥）',
        canTalk(peerA, peerB) && canTalk(peerB, peerA));
    ok('丢包后仍收敛到同一把密钥', sameKey(peerA, peerB));
}

console.log('\n[7] 篡改确认包不会静默进入可传状态');
{
    const {peerA, peerB} = handshake();
    // 重新造一对，把 A 的确认包改动一个字节后投给 B
    const secret = LanPairing.generateSecret();
    const roomId = LanPairing.deriveRoomId(secret);
    LanPairing.secretMap.set(roomId, secret);
    const sentA = [];
    const victim = new WSPeer({send: m => sentA.push(m), sendBinary: () => {}}, false, 'B', 'secret', roomId);
    const tampered = Buffer.from(sentA[0].data, 'base64');
    tampered[tampered.length - 1] ^= 0xff;
    fired.length = 0;
    victim._onE2e({type: 'e2e', data: tampered.toString('base64'), sender: {id: 'A'}});
    ok('篡改后未进入已确认状态', victim._e2eConfirmed !== true);
    ok('发出了解密失败提示', fired.some(f => f.type === 'notify-user'));
    LanPairing.secretMap.delete(roomId);
    ok('（对照组）完整数据帧在正常握手下可解', canTalk(peerA, peerB));
}

console.log('\n[8] 点选配对：临时 ECDH + 6 位 SAS + 派生配对密钥 S\'');
{
    const a = LanPairing.generateEphemeralKeyPair();
    const b = LanPairing.generateEphemeralKeyPair();
    const sharedA = LanPairing.ecdh(a.secretKey, b.publicKey);
    const sharedB = LanPairing.ecdh(b.secretKey, a.publicKey);
    ok('双方 ECDH 共享密钥一致', Buffer.from(sharedA).equals(Buffer.from(sharedB)));

    const sasA = LanPairing.deriveClickPairSas(sharedA, a.publicKey, b.publicKey);
    const sasB = LanPairing.deriveClickPairSas(sharedB, b.publicKey, a.publicKey);
    ok('SAS 双方一致（公钥顺序不影响）', sasA === sasB);
    ok('SAS 是 6 位数字', /^[0-9]{6}$/.test(sasA), sasA);
    ok('SAS 显示分组为 3-3', LanPairing.formatSas(sasA) === `${sasA.slice(0, 3)} ${sasA.slice(3)}`);

    const secretA = LanPairing.deriveClickPairSecret(sharedA, a.publicKey, b.publicKey);
    const secretB = LanPairing.deriveClickPairSecret(sharedB, b.publicKey, a.publicKey);
    ok('派生出的配对密钥 S\' 双方一致', secretA === secretB);
    ok('S\' 是合法的 26 字符 base32 配对码', LanPairing.isValidCode(secretA), secretA);

    // 中间人：A 面对 M1、B 面对 M2，两侧 ECDH 结果不同 -> SAS 必然不同
    const m1 = LanPairing.generateEphemeralKeyPair();
    const m2 = LanPairing.generateEphemeralKeyPair();
    const sasToA = LanPairing.deriveClickPairSas(LanPairing.ecdh(a.secretKey, m1.publicKey), a.publicKey, m1.publicKey);
    const sasToB = LanPairing.deriveClickPairSas(LanPairing.ecdh(b.secretKey, m2.publicKey), b.publicKey, m2.publicKey);
    ok('中间人转接时两侧 SAS 不同（用户核对即可发现）', sasToA !== sasToB);

    // 点选配对产生的 S' 能直接进入正常的秘密房间链路
    const roomFromClick = LanPairing.deriveRoomId(secretA);
    const {peerA, peerB} = handshake();
    ok('点选配对派生出的 R 是合法房间 id', /^[0-9a-f]{64}$/.test(roomFromClick));
    ok('（对照）正常派生的房间 id 同样合法', /^[0-9a-f]{64}$/.test(peerA._roomIds['secret']));
}

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
