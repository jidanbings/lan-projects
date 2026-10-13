// 点选配对的**安全与异常路径**测试（不依赖浏览器：用 DOM 替身直接驱动真实状态机）。
//
// 正常流程、拒绝、超时由 ui-two-device-test.mjs 在真实 DOM + 真实服务器上覆盖；
// 这里专测那些不好在真实设备上复现的分支：
//   [1] 对方确认包里的数字与本端不同（中间人）-> 中止，不得落库
//   [2] 对方的确认包解不开（密钥不一致）-> 判定验证失败，不得落库
//   [3] 同一浏览器的另一个标签页不允许配对
//   [4] 本机正在配对时收到新的请求 -> 明确回绝（reason=busy），不让对方干等
//   [5] 「至少一方确认即可」：本端从未点确认，仅凭对方确认也能完成，且两端 S' 相同
//   [6] 完成的配对会通知服务器加入秘密房间
//
// 运行：node pair-click-ui-test.mjs
import fs from 'fs';

const REPO = '..';
global.window = globalThis;

const fired = [];
global.Events = {
    fire: (type, detail) => fired.push({type, detail}),
    on: () => {},
    off: () => {}
};
global.Localization = { getTranslation: key => key, escapeHTML: s => String(s == null ? '' : s) };
global.document = { getElementById: () => null, querySelector: () => null };
global.sessionStorage = { getItem: () => null, setItem: () => {} };
global.$ = () => null;
global.$$ = () => null;
global.arrayBufferToBase64 = b => Buffer.from(b).toString('base64');
global.base64ToArrayBuffer = s => Uint8Array.from(Buffer.from(s, 'base64')).buffer;

const addedSecrets = [];
global.PersistentStorage = {
    getRoomSecretEntry: async () => undefined,
    getAllRoomSecretEntries: async () => [],
    updateRoomSecretLastConnected: async () => undefined,
    addRoomSecret: async (roomSecret, pairSecret, displayName, deviceName, pairMethod) => {
        addedSecrets.push({roomSecret, pairSecret, pairMethod});
    },
    deleteRoomSecret: async () => undefined,
    logBrowserNotCapable: () => {}
};
global.BrowserTabsConnector = { peerIsSameBrowser: () => false };

for (const file of ['libs/chacha20.js', 'libs/crypto-util.js', 'libs/x25519.js']) {
    (0, eval)(fs.readFileSync(`${REPO}/public/scripts/${file}`, 'utf8'));
}
(0, eval)(fs.readFileSync(`${REPO}/public/scripts/network.js`, 'utf8'));
(0, eval)(fs.readFileSync(`${REPO}/public/scripts/ui.js`, 'utf8')
    + '\n;globalThis.__PairClickDialog = PairClickDialog;');

const PairClickDialog = globalThis.__PairClickDialog;
const {LanPairing, Chacha} = window;

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) { passed++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
    else { failed++; console.log('  ✗ FAIL: ' + name + (extra ? '  ' + extra : '')); }
};

const dummyElement = () => ({
    innerText: '', textContent: '', value: '',
    setAttribute() {}, removeAttribute() {}, hasAttribute() { return false; },
    addEventListener() {}, querySelector: () => null
});

/** 真实的 PairClickDialog 实例，只把 DOM 与显示层换成哑对象。 */
function makeDialog() {
    const dialog = Object.create(PairClickDialog.prototype);
    for (const f of ['$peerName', '$sas', '$waiting', '$hint', '$error', '$confirmBtn', '$fallbackBtn']) {
        dialog[f] = dummyElement();
    }
    dialog.show = () => {};
    dialog.hide = () => {};
    dialog.isShown = () => dialog._phase !== null;
    dialog._reset();
    dialog._finalized = false;
    return dialog;
}

/** 模拟对端设备：持有自己的临时密钥，能算出与本端一致的共享密钥。 */
function makePeer() {
    const keyPair = LanPairing.generateEphemeralKeyPair();
    return {
        keyPair,
        pkHex: LanPairing._toHex(keyPair.publicKey),
        derive(peerPublicKeyBytes) {
            const shared = LanPairing.ecdh(keyPair.secretKey, peerPublicKeyBytes);
            return {
                shared,
                key: LanPairing.deriveClickPairKey(shared, keyPair.publicKey, peerPublicKeyBytes),
                sas: LanPairing.deriveClickPairSas(shared, keyPair.publicKey, peerPublicKeyBytes),
                secret: LanPairing.deriveClickPairSecret(shared, keyPair.publicKey, peerPublicKeyBytes)
            };
        }
    };
}

const peerConfirmFrame = (key, sas) => {
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const sealed = Chacha.chacha20poly1305(key, nonce)
        .encrypt(new TextEncoder().encode(JSON.stringify({sas})));
    const frame = new Uint8Array(12 + sealed.length);
    frame.set(nonce);
    frame.set(sealed, 12);
    return Buffer.from(frame).toString('base64');
};

/** 走到"数字已显示、等待确认"的状态：本机被点名 -> 接受。 */
function reachComparePhase() {
    addedSecrets.length = 0;
    fired.length = 0;
    const dialog = makeDialog();
    const peer = makePeer();
    dialog._onMessage({type: 'pair-click-request', sender: {id: 'peer-b'}, pk: peer.pkHex});
    dialog._onConfirmClicked();          // 点「接受配对」
    const accept = fired.filter(f => f.type === 'pair-click-send').pop();
    const peerView = peer.derive(LanPairing._fromHex(accept.detail.payload.pk, 32));
    return {dialog, peer, peerView};
}

const lastSend = () => fired.filter(f => f.type === 'pair-click-send').pop();
const verifyFailed = () => PairClickDialog.DEVICE_PLACEHOLDER_KEYS.includes('x') || true;

console.log('[1] 对方确认包里的数字与本端不同（中间人特征）');
{
    const {dialog, peerView} = reachComparePhase();
    const wrongSas = peerView.sas === '000000' ? '111111' : '000000';
    dialog._onMessage({
        type: 'pair-click-confirm', sender: {id: 'peer-b'},
        data: peerConfirmFrame(peerView.key, wrongSas)
    });
    ok('判定为疑似中间人并中止', dialog._phase === 'failed'
        && dialog._errorKey === 'notifications.pairing-sas-mismatch', String(dialog._errorKey));
    ok('没有落库', addedSecrets.length === 0);
    ok('给出了醒目提示', fired.some(f => f.type === 'notify-user'));
}

console.log('\n[2] 对方的确认包解不开（密钥不一致）');
{
    const {dialog} = reachComparePhase();
    const otherPeer = makePeer();                       // 第三方的密钥，解不开
    const bogus = LanPairing.deriveClickPairKey(
        new Uint8Array(32), otherPeer.keyPair.publicKey, new Uint8Array(32));
    dialog._onMessage({
        type: 'pair-click-confirm', sender: {id: 'peer-b'},
        data: peerConfirmFrame(bogus, '123456')
    });
    ok('判定为验证失败', dialog._phase === 'failed'
        && dialog._errorKey === 'notifications.pairing-verification-failed', String(dialog._errorKey));
    ok('没有落库', addedSecrets.length === 0);
}

console.log('\n[3] 同一浏览器的另一个标签页不允许配对');
{
    const original = global.BrowserTabsConnector.peerIsSameBrowser;
    global.BrowserTabsConnector.peerIsSameBrowser = () => true;
    fired.length = 0;
    addedSecrets.length = 0;
    const dialog = makeDialog();
    dialog._startWith('same-browser-tab');
    ok('给出提示而不是开始配对',
        fired.some(f => f.type === 'notify-user'
            && (typeof f.detail === 'string' ? f.detail : f.detail.message) === 'notifications.pairing-tabs-error'));
    ok('没有进入任何阶段', dialog._phase === null);
    ok('没有发出配对请求', !fired.some(f => f.type === 'pair-click-send'));
    global.BrowserTabsConnector.peerIsSameBrowser = original;
}

console.log('\n[4] 本机正在配对时收到新请求 -> 明确回绝（reason=busy）');
{
    fired.length = 0;
    const dialog = makeDialog();
    const first = makePeer();
    const second = makePeer();
    dialog._onMessage({type: 'pair-click-request', sender: {id: 'peer-b'}, pk: first.pkHex});
    ok('（前提）本机已在处理第一台设备的请求', dialog._phase === 'incoming');
    dialog._onMessage({type: 'pair-click-request', sender: {id: 'peer-c'}, pk: second.pkHex});
    const reject = lastSend();
    ok('回了一条拒绝', !!reject && reject.detail.payload.type === 'pair-click-reject');
    ok('拒绝原因是忙碌', !!reject && reject.detail.payload.reason === 'busy');
    ok('拒绝发给了新来的那台设备', !!reject && reject.detail.peerId === 'peer-c');
    ok('仍继续处理第一台设备', dialog._peerId === 'peer-b' && dialog._phase === 'incoming');
}

console.log('\n[5] 「至少一方确认即可」：本端没点确认，仅凭对方确认也能完成');
{
    const {dialog, peerView} = reachComparePhase();
    ok('（前提）已进入数字核对阶段', dialog._phase === 'compare' && !!dialog._sas);
    ok('此时本端尚未落库', addedSecrets.length === 0);
    dialog._onMessage({
        type: 'pair-click-confirm', sender: {id: 'peer-b'},
        data: peerConfirmFrame(peerView.key, peerView.sas)
    });
    await new Promise(r => setTimeout(r, 20));      // 落库在 Promise 里
    ok('仅凭对方确认即完成配对', addedSecrets.length === 1);
    ok('落库的 S\' 与对方本地派生的一致',
        addedSecrets.length === 1 && addedSecrets[0].pairSecret === peerView.secret,
        addedSecrets.length ? addedSecrets[0].pairSecret : '');
    ok('配对方式记为 click', addedSecrets.length === 1 && addedSecrets[0].pairMethod === 'click');
    ok('通知服务器加入该秘密房间',
        fired.some(f => f.type === 'room-secrets'
            && f.detail[0] === LanPairing.deriveRoomId(peerView.secret)));
}

console.log('\n[6] 完成后再收到对方迟到的"拒绝"不应再弹出错误');
{
    const {dialog, peerView} = reachComparePhase();
    dialog._onMessage({
        type: 'pair-click-confirm', sender: {id: 'peer-b'},
        data: peerConfirmFrame(peerView.key, peerView.sas)
    });
    await new Promise(r => setTimeout(r, 20));
    const savedBefore = addedSecrets.length;
    dialog._onMessage({type: 'pair-click-reject', sender: {id: 'peer-b'}, reason: 'declined'});
    ok('迟到的拒绝被忽略（不会把已完成的配对改判为失败）', dialog._phase !== 'failed');
    ok('没有重复落库', addedSecrets.length === savedBefore);
}

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
