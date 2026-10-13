// 点选配对（局域网设备列表点选配对）的端到端逻辑测试。
//
// 用「DOM 替身」直接驱动真实的 PairClickDialog 状态机：不加载浏览器、不依赖任何
// 第三方测试库，只把对话框那几个 DOM 字段换成哑对象（与本仓库 server-routing-test
// 用 Object.create(prototype) 构造对象的做法一致）。对端用真实的 X25519/ChaCha
// 充当另一台设备，因此测的是完整协议：临时公钥交换 → 双方算出同一个共享密钥 →
// 6 位 SAS 一致 → 派生相同的配对密钥 S' → 落库。
//
// 覆盖：
//   [1] 作为被选中的一方接受配对：SAS 与对端一致、状态机推进
//   [2] 双方各自派生出相同的 S' 与房间 id R，并写入持久化
//   [3] 对方确认的数字与本端不同 -> 判定中间人，中止且不落库
//   [4] 对方拒绝 -> 提示且不落库
//   [5] 同浏览器标签页不允许配对
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

const addedSecrets = [];
global.PersistentStorage = {
    getRoomSecretEntry: async () => undefined,
    getAllRoomSecretEntries: async () => [],
    updateRoomSecretLastConnected: async () => undefined,
    addRoomSecret: async (roomSecret, pairSecret, displayName, deviceName, pairMethod) => {
        addedSecrets.push({roomSecret, pairSecret, displayName, deviceName, pairMethod});
    },
    deleteRoomSecret: async () => undefined,
    logBrowserNotCapable: () => {}
};
global.arrayBufferToBase64 = b => Buffer.from(b).toString('base64');
global.base64ToArrayBuffer = s => Uint8Array.from(Buffer.from(s, 'base64')).buffer;
global.BrowserTabsConnector = { peerIsSameBrowser: () => false };
// ui-main.js 里的 DOM 查询助手（$ = getElementById，$$ = querySelector）。
// 本测试不加载 ui-main.js，这里给出返回空的替身。
global.$ = () => null;
global.$$ = () => null;

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
    if (cond) {
        passed++;
        console.log('  ✓ ' + name + (extra ? '  ' + extra : ''));
    } else {
        failed++;
        console.log('  ✗ FAIL: ' + name + (extra ? '  ' + extra : ''));
    }
};

/** 造一个哑 DOM 元素：所有成员都是空操作，只用来接住 innerText 之类的赋值。 */
const dummyElement = () => ({
    innerText: '', textContent: '', value: '',
    setAttribute() {}, removeAttribute() {}, addEventListener() {},
    querySelector: () => null
});

/** 构造一个真实的 PairClickDialog 实例，只把 DOM 与显示层换成哑对象。 */
function makeDialog() {
    const dialog = Object.create(PairClickDialog.prototype);
    dialog.$peerName = dummyElement();
    dialog.$sas = dummyElement();
    dialog.$hint = dummyElement();
    dialog.$confirmBtn = dummyElement();
    dialog.$closeBtn = dummyElement();
    dialog.show = () => {};
    dialog.hide = () => {};
    dialog.isShown = () => false;
    dialog._reset();
    return dialog;
}

/** 模拟"另一台设备"：持有自己的临时密钥，并能算出与对方一致的共享密钥。 */
function makePeer() {
    const keyPair = LanPairing.generateEphemeralKeyPair();
    return {
        keyPair,
        pkHex: LanPairing._toHex(keyPair.publicKey),
        pkBytes: keyPair.publicKey,
        // 对端视角：用对方的公钥算共享密钥、握手密钥与 SAS
        derive(peerPublicKeyBytes) {
            const shared = LanPairing.ecdh(this.keyPair.secretKey, peerPublicKeyBytes);
            return {
                shared,
                key: LanPairing.deriveClickPairKey(shared, this.keyPair.publicKey, peerPublicKeyBytes),
                sas: LanPairing.deriveClickPairSas(shared, this.keyPair.publicKey, peerPublicKeyBytes),
                secret: LanPairing.deriveClickPairSecret(shared, this.keyPair.publicKey, peerPublicKeyBytes)
            };
        }
    };
}

/** 对端发出的握手消息（包里带本端显示的 SAS）。 */
function peerConfirmFrame(key, sas) {
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const plaintext = new TextEncoder().encode(JSON.stringify({sas}));
    const sealed = Chacha.chacha20poly1305(key, nonce).encrypt(plaintext);
    const frame = new Uint8Array(12 + sealed.length);
    frame.set(nonce);
    frame.set(sealed, 12);
    return Buffer.from(frame).toString('base64');
}

const lastPairClickSend = () => fired.filter(f => f.type === 'pair-click-send').pop();
// notify-user 的载荷有两种历史形式（裸字符串、{message}），Toast 两者都认。
const notifyMessages = () => fired.filter(f => f.type === 'notify-user')
    .map(f => typeof f.detail === 'string' ? f.detail : (f.detail && f.detail.message));
const waitForAsyncWork = () => new Promise(r => setTimeout(r, 20));

console.log('[1] 被选中的一方：接受配对并算出 SAS');
let acceptResult = null;
{
    addedSecrets.length = 0;
    fired.length = 0;
    const dialog = makeDialog();
    const peer = makePeer();

    // 对端发起：带上它的临时公钥
    dialog._onMessage({type: 'pair-click-request', sender: {id: 'peer-b'}, pk: peer.pkHex});
    ok('进入"等待本端接受"阶段', dialog._phase === 'accept');
    ok('记下了发起方的 peerId', dialog._peerId === 'peer-b');

    // 本端点"接受配对"：把自己的临时公钥回给对方
    dialog._onConfirmClicked();
    const accept = lastPairClickSend();
    ok('发出了 pair-click-accept', !!accept && accept.detail.payload.type === 'pair-click-accept');

    const oursBytes = LanPairing._fromHex(accept.detail.payload.pk, 32);
    const peerView = peer.derive(oursBytes);
    ok('双方 SAS 一致', dialog._sas === peerView.sas, `本端 ${dialog._sas} / 对端 ${peerView.sas}`);
    ok('SAS 是 6 位数字', /^[0-9]{6}$/.test(dialog._sas));
    ok('进入数字核对阶段', dialog._phase === 'compare');
    acceptResult = {dialog, peer, peerView};
}

console.log('\n[2] 双方确认后派生相同的 S\' 并落库');
{
    const {dialog, peer, peerView} = acceptResult;
    addedSecrets.length = 0;
    fired.length = 0;

    // 本端点"数字一致，确认"
    dialog._onConfirmClicked();
    const ourConfirm = lastPairClickSend();
    ok('发出了 pair-click-confirm', !!ourConfirm && ourConfirm.detail.payload.type === 'pair-click-confirm');
    ok('本端确认后仍未落库（要等对方也确认）', addedSecrets.length === 0);

    // 对端也确认（用同样密码学算出的握手密钥加密它显示的 SAS）
    dialog._onMessage({
        type: 'pair-click-confirm',
        sender: {id: 'peer-b'},
        data: peerConfirmFrame(peerView.key, peerView.sas)
    });
    // 落库后才会触发 room-secrets 与成功提示，等一个事件循环。
    await waitForAsyncWork();

    ok('双方确认后落库一条配对记录', addedSecrets.length === 1);
    if (addedSecrets.length === 1) {
        const saved = addedSecrets[0];
        ok('落库的 S\' 与对端本地派生的完全一致', saved.pairSecret === peerView.secret, saved.pairSecret);
        ok('落库的 S\' 是合法配对码', LanPairing.isValidCode(saved.pairSecret));
        ok('落库的 R 与 S\' 自洽', saved.roomSecret === LanPairing.deriveRoomId(peerView.secret));
        ok('配对方式记为 click', saved.pairMethod === 'click');
    }
    const roomSecretsEvent = fired.find(f => f.type === 'room-secrets');
    ok('通知服务器加入该秘密房间（触发正常配对链路）', !!roomSecretsEvent
        && roomSecretsEvent.detail[0] === LanPairing.deriveRoomId(peerView.secret));
    ok('提示配对成功', notifyMessages().includes('notifications.pairing-success'));
}

console.log('\n[3] 对方显示的 6 位数字与本端不同（中间人）');
{
    addedSecrets.length = 0;
    fired.length = 0;
    const dialog = makeDialog();
    const peer = makePeer();
    dialog._onMessage({type: 'pair-click-request', sender: {id: 'peer-b'}, pk: peer.pkHex});
    dialog._onConfirmClicked();
    const accept = lastPairClickSend();
    const peerView = peer.derive(LanPairing._fromHex(accept.detail.payload.pk, 32));

    dialog._onConfirmClicked();   // 本端确认
    // 对端报告的 SAS 被改掉一位（模拟它其实面对着另一把密钥）
    const wrongSas = peerView.sas === '000000' ? '111111' : '000000';
    dialog._onMessage({
        type: 'pair-click-confirm',
        sender: {id: 'peer-b'},
        data: peerConfirmFrame(peerView.key, wrongSas)
    });

    ok('判定 SAS 不一致并中止', notifyMessages().includes('notifications.pairing-sas-mismatch'));
    ok('没有落库', addedSecrets.length === 0);
}

console.log('\n[4] 对方拒绝配对');
{
    addedSecrets.length = 0;
    fired.length = 0;
    const dialog = makeDialog();
    const peer = makePeer();
    dialog._onMessage({type: 'pair-click-request', sender: {id: 'peer-b'}, pk: peer.pkHex});
    dialog._onMessage({type: 'pair-click-reject', sender: {id: 'peer-b'}});
    ok('提示对方已拒绝', notifyMessages().includes('notifications.pairing-declined'));
    ok('没有落库', addedSecrets.length === 0);
    ok('状态已复位', dialog._phase === null && dialog._peerId === null);
}

console.log('\n[5] 同浏览器的另一个标签页不允许配对');
{
    const original = global.BrowserTabsConnector.peerIsSameBrowser;
    global.BrowserTabsConnector.peerIsSameBrowser = () => true;
    fired.length = 0;
    const dialog = makeDialog();
    dialog._startWith('same-browser-tab');
    ok('给出提示而不是开始配对', notifyMessages().includes('notifications.pairing-tabs-error'));
    ok('没有进入任何阶段', dialog._phase === null);
    global.BrowserTabsConnector.peerIsSameBrowser = original;
}

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
