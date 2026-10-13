// 两台设备的点选配对端到端测试（真实 DOM + 真实 ServerConnection + 真实 ws-server）。
//
// 为什么需要它：此前的测试要么只测密码学原语、要么用"DOM 替身"直接调对话框方法，
// 都没有覆盖**真实页面上的真实交互**。于是"两端都停在六个点、看起来像没有数字"
// 这类问题溜了过去——协议明明是通的，卡住的是"对方要先去点接受"这一步没有任何
// 可见引导。这里把两台设备摆在真实环境里，按用户的操作顺序点一遍。
//
// 依赖 jsdom（仓库不引入测试依赖，装了才跑；没装会打印跳过提示并以 0 退出）：
//   npm i --no-save jsdom      # 在 web/ 目录下
//   node ui-two-device-test.mjs
// 也可以指定路径：JSDOM_PATH=<绝对路径>/node_modules/jsdom node ui-two-device-test.mjs
//
// 运行：node ui-two-device-test.mjs
import fs from 'fs';
import path from 'path';
import vm from 'vm';
import {pathToFileURL} from 'url';
import {createRequire} from 'module';

const require = createRequire(import.meta.url);
let JSDOM, VirtualConsole;
try {
    ({JSDOM, VirtualConsole} = require(process.env.JSDOM_PATH || 'jsdom'));
} catch (e) {
    console.log('跳过 ui-two-device-test：未安装 jsdom。');
    console.log('  npm i --no-save jsdom   # 在 web/ 目录下执行后重跑');
    process.exit(0);
}

const PUB = path.resolve('../public');
const SERVER = path.resolve('../server');
// 界面文案已翻译过，断言要用真实译文而不是 key
const ZH = JSON.parse(fs.readFileSync(path.join(PUB, 'lang/zh-CN.json'), 'utf8'));

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) { passed++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
    else { failed++; console.log('  ✗ FAIL: ' + name + (extra ? '  ' + extra : '')); }
};

// ---- 起真实服务器 ----------------------------------------------------------
const {default: LanProjectsServer} = await import(pathToFileURL(path.join(SERVER, 'server.js')).href);
const {default: LanProjectsWsServer} = await import(pathToFileURL(path.join(SERVER, 'ws-server.js')).href);
const conf = {debugMode: false, port: 0, wsFallback: true, ipv6Localize: false, rateLimit: false, lanIp: '192.168.0.26'};
const httpServer = new LanProjectsServer(conf).server;
new LanProjectsWsServer(httpServer, conf);
while (!httpServer.address()) await new Promise(r => setTimeout(r, 10));
const port = httpServer.address().port;

const sleep = ms => new Promise(r => setTimeout(r, ms));

/**
 * 一台"设备"：真实 index.html + 全部真实脚本 + 真实 ServerConnection。
 * 只补 jsdom 缺失的浏览器能力（canvas / IndexedDB / innerText 等），不替应用做任何事。
 */
function makeDevice(name) {
    const logs = [];
    const vc = new VirtualConsole();
    ['error', 'warn'].forEach(t => vc.on(t, (...a) => logs.push(t + ': ' + a.map(String).join(' ').slice(0, 160))));
    vc.on('jsdomError', e => logs.push('jsdomError: ' + (e.message || e)));

    const dom = new JSDOM(fs.readFileSync(path.join(PUB, 'index.html'), 'utf8'), {
        url: `http://127.0.0.1:${port}/`,
        runScripts: 'outside-only',
        pretendToBeVisual: true,
        virtualConsole: vc
    });
    const {window} = dom;
    const ctx = dom.getInternalVMContext();

    Object.defineProperty(window.HTMLElement.prototype, 'innerText', {
        get() { return this.textContent; },
        set(v) { this.textContent = v; }
    });
    if (!window.crypto || !window.crypto.getRandomValues) {
        Object.defineProperty(window, 'crypto', {
            value: {getRandomValues: a => { for (let i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256); return a; }}
        });
    }
    window.matchMedia = () => ({matches: false, addEventListener() {}, removeEventListener() {}});
    window.isRtcSupported = false;
    window.HTMLCanvasElement.prototype.getContext = () => new Proxy({}, {
        get: (t, k) => k === 'measureText' ? (() => ({width: 0})) : (() => {}),
        set: () => true
    });

    const run = (code, filename) => vm.runInContext(code, ctx, {filename: filename || 'inline'});
    for (const f of ['scripts/ui-main.js', 'scripts/localization.js', 'scripts/persistent-storage.js',
        'scripts/browser-tabs-connector.js', 'scripts/util.js', 'scripts/libs/chacha20.js',
        'scripts/libs/crypto-util.js', 'scripts/libs/x25519.js', 'scripts/libs/qr-code.min.js',
        'scripts/network.js', 'scripts/ui.js']) {
        run(fs.readFileSync(path.join(PUB, f), 'utf8'), f);
    }

    run(`
Localization.defaultLocale = 'en';
Localization.locale = 'zh-CN';
Localization.translationsDefaultLocale = ${fs.readFileSync(path.join(PUB, 'lang/en.json'), 'utf8')};
Localization.translations = ${fs.readFileSync(path.join(PUB, 'lang/zh-CN.json'), 'utf8')};
window.__notify = [];
Events.on('notify-user', e => window.__notify.push(typeof e.detail === 'string' ? e.detail : e.detail.message));
window.__saved = [];
// jsdom 没有 IndexedDB：把存储层换成内存桩，配对流程本身照走
PersistentStorage.getAllRoomSecretEntries = () => Promise.resolve([]);
PersistentStorage.getAllRoomSecrets = () => Promise.resolve([]);
PersistentStorage.getRoomSecretEntry = () => Promise.resolve(undefined);
PersistentStorage.deleteRoomSecret = () => Promise.resolve();
PersistentStorage.updateRoomSecretLastConnected = () => Promise.resolve();
PersistentStorage.updateRoomSecretAutoConnect = () => Promise.resolve();
PersistentStorage.addRoomSecret = (r, s, d, dn, m) => {
    window.__saved.push({roomSecret: r, pairSecret: s, pairMethod: m});
    return Promise.resolve();
};
window.__peers = {};
Events.on('peers', e => { (e.detail.peers || []).forEach(p => window.__peers[p.id] = p); });
Events.on('peer-joined', e => window.__peers[e.detail.peer.id] = e.detail.peer);
window.__sc = new ServerConnection();
window.__dialog = new PairClickDialog();
'ok'
`);

    return {name, window, logs, run, dom};
}

const q = (dev, sel) => dev.window.document.querySelector(sel);
const text = (dev, sel) => { const el = q(dev, sel); return el ? el.innerText : null; };
const visible = (dev, sel) => { const el = q(dev, sel); return !!el && !el.hasAttribute('hidden'); };
const sasShown = dev => visible(dev, '#pair-click-dialog .pair-click-sas');
const sasText = dev => text(dev, '#pair-click-dialog .pair-click-sas');
const hint = dev => text(dev, '#pair-click-dialog .pair-click-hint');
const shown = dev => q(dev, '#pair-click-dialog').hasAttribute('show');
const click = (dev, sel) => q(dev, sel).dispatchEvent(new dev.window.MouseEvent('click', {bubbles: true, cancelable: true}));
const waitFor = async (fn, timeout, label) => {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        try { if (fn()) return true; } catch (e) { /* 继续等 */ }
        await sleep(100);
    }
    throw new Error('等待超时: ' + label);
};

console.log(`服务器: http://127.0.0.1:${port}`);
const A = makeDevice('A');
const B = makeDevice('B');
await waitFor(() => Object.keys(A.run('window.__peers')).length > 0, 15000, 'A 看到 B');
const peerIdOfB = Object.keys(A.run('window.__peers'))[0];
console.log(`两台设备已连上（A 看到 B: ${!!peerIdOfB}）\n`);

console.log('[1] A 点「配对」——等待期不能显示数字，且要明确告诉用户去对方设备点接受');
A.run(`window.__dialog._startWith(${JSON.stringify(peerIdOfB)})`);
await sleep(400);
ok('A 弹出配对窗口', shown(A));
ok('A 等待期**不显示**数字位（不再用 ··· 冒充）', !sasShown(A));
ok('A 显示了转圈状态区', visible(A, '#pair-click-dialog .pair-click-waiting'));
ok('A 的提示里写明了要让对方点接受', /接受/.test(hint(A) || ''), JSON.stringify(hint(A)));
ok('A 此时没有可点的确认按钮', !visible(A, '#pair-click-confirm'));

console.log('\n[2] B 收到请求（走真实服务器中继）');
await waitFor(() => shown(B), 8000, 'B 弹出配对请求');
ok('B 弹出配对窗口', shown(B));
ok('B 显示"请求与本机配对"', /请求/.test(hint(B) || ''), JSON.stringify(hint(B)));
ok('B 此时也不显示数字（还没交换公钥）', !sasShown(B));
ok('B 的主按钮是「接受配对」且可点', visible(B, '#pair-click-confirm') && /接受/.test(text(B, '#pair-click-confirm')));

console.log('\n[3] B 点「接受配对」——两端应显示相同的 6 位数字');
click(B, '#pair-click-confirm');
await waitFor(() => sasShown(A) && sasShown(B), 8000, '两端显示数字');
ok('A 显示数字', sasShown(A), JSON.stringify(sasText(A)));
ok('B 显示数字', sasShown(B), JSON.stringify(sasText(B)));
ok('两端数字相同', sasText(A) === sasText(B));
ok('显示的是 6 位数字（3-3 分组）', /^\d{3} \d{3}$/.test(sasText(A) || ''), JSON.stringify(sasText(A)));
ok('提示改成了"核对数字"', /核对/.test(hint(A) || ''), JSON.stringify(hint(A)));

console.log('\n[4] 只由 A 一方确认 —— 单方确认即应完成配对（规范做法）');
click(A, '#pair-click-confirm');
await sleep(1200);
const savedA = A.run('window.__saved');
const savedB = B.run('window.__saved');
ok('A 落库一条配对记录', savedA.length === 1);
ok('B 无需再点一次：对方确认后自动完成', savedB.length === 1);
ok('两端配对密钥相同',
    savedA.length === 1 && savedB.length === 1 && savedA[0].pairSecret === savedB[0].pairSecret,
    savedA.length ? savedA[0].pairSecret : '');
ok('配对方式记为 click', savedA.length === 1 && savedA[0].pairMethod === 'click');
ok('两端弹窗都已收起', !shown(A) && !shown(B));
ok('两端都提示配对成功',
    A.run('window.__notify').includes(ZH.notifications['pairing-success'])
    && B.run('window.__notify').includes(ZH.notifications['pairing-success']),
    JSON.stringify(A.run('window.__notify')));

console.log('\n[5] 对方拒绝：要给出发起方可见的结果与兜底入口');
{
    A.run('window.__saved.length = 0; window.__notify.length = 0;');
    B.run('window.__saved.length = 0; window.__notify.length = 0;');
    A.run(`window.__dialog._startWith(${JSON.stringify(peerIdOfB)})`);
    await waitFor(() => shown(B), 8000, 'B 再次弹出请求');
    // B 点关闭（= 拒绝）
    click(B, '#pair-click-dialog [close]');
    await sleep(600);
    ok('A 弹窗仍在，并给出了失败原因', shown(A) && !!text(A, '#pair-click-dialog .pair-click-error'),
        JSON.stringify(text(A, '#pair-click-dialog .pair-click-error')));
    ok('A 给出了「改用二维码 / 配对码」的兜底入口', visible(A, '#pair-click-fallback'));
    ok('没有落库', A.run('window.__saved').length === 0 && B.run('window.__saved').length === 0);
    A.run('window.__dialog._cancel()');
}

console.log('\n[6] 超时：等不到对方时要明确说"没有响应"，并可改用二维码');
{
    A.run(`window.__dialog._startWith(${JSON.stringify(peerIdOfB)})`);
    await sleep(300);
    A.run(`window.__dialog._fail('dialogs.click-pair-no-response')`);   // 直接触发超时分支
    await sleep(200);
    ok('A 显示超时/无响应文案', /没有响应/.test(text(A, '#pair-click-dialog .pair-click-error') || ''),
        JSON.stringify(text(A, '#pair-click-dialog .pair-click-error')));
    ok('A 提供兜底入口', visible(A, '#pair-click-fallback'));
    A.run('window.__dialog._cancel()');
}

const errors = [...A.logs, ...B.logs].filter(l => /jsdomError/.test(l) && !/blur/.test(l));
ok('两端没有意外的运行期错误', errors.length === 0, errors.slice(0, 3).join(' | '));

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
httpServer.close();
process.exit(failed ? 1 : 0);
