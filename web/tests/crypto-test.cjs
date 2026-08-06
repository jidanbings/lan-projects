// 密码学单元测试 —— 零信任改造的加解密栈自检。
// 运行：node crypto-test.js
//
// 覆盖（全部对齐 web/public/scripts/network.js 与 vendored 库的真实实现）：
//   1. 配对密钥 S：16 字节随机 -> 26 字符 RFC 4648 base32，往返一致
//   2. HKDF-SHA256 派生：R（64 hex）与 key（32 字节）确定性、单向
//   3. ChaCha20-Poly1305 AEAD：往返解密、篡改检测、帧布局 nonce(12)‖ct‖tag(16)
//   4. 配对码 normalize/isValid/format（大写、去连字符、剔非 base32 字符）
const fs = require('fs');
const path = require('path');
const assert = require('assert');

// 用间接 eval 在全局作用域加载浏览器脚本（无 module.exports）。
global.window = globalThis;
function loadClassic(rel) {
    (0, eval)(fs.readFileSync(path.join(__dirname, rel), 'utf8'));
}
loadClassic('../public/scripts/libs/chacha20.js');
loadClassic('../public/scripts/libs/crypto-util.js');
loadClassic('../public/scripts/network.js');   // 定义 window.LanPairing 等

const {LanPairing, Chacha, CryptoUtil} = window;

let passed = 0;
function check(name, fn) {
    fn();
    passed++;
    console.log('  ✓ ' + name);
}

console.log('1) 配对密钥 S：base32 生成与往返');
check('generateSecret() 返回 26 字符 [A-Z2-7]', () => {
    for (let i = 0; i < 200; i++) {
        const s = LanPairing.generateSecret();
        assert.strictEqual(s.length, 26);
        assert.ok(/^[A-Z2-7]{26}$/.test(s), '非法字符: ' + s);
    }
});
// 独立解码器交叉验证 base32 往返（16 字节 -> 26 字符 -> 16 字节）。
function base32Decode(str) {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    let bits = 0, value = 0;
    const out = [];
    for (const ch of str) {
        value = (value << 5) | alphabet.indexOf(ch);
        bits += 5;
        if (bits >= 8) {
            out.push((value >>> (bits - 8)) & 255);
            bits -= 8;
        }
    }
    return new Uint8Array(out);
}
check('16 字节 S <-> base32 往返一致', () => {
    for (let i = 0; i < 200; i++) {
        const s = LanPairing.generateSecret();
        const bytes = base32Decode(s);
        assert.strictEqual(bytes.length, 16);
        assert.strictEqual(LanPairing._bytesToBase32(bytes), s);
    }
});

console.log('2) HKDF-SHA256 派生（R 与会话密钥）');
check('deriveRoomId 返回 64 位十六进制且确定性', () => {
    const S = LanPairing.generateSecret();
    assert.ok(/^[0-9a-f]{64}$/.test(LanPairing.deriveRoomId(S)));
    assert.strictEqual(LanPairing.deriveRoomId(S), LanPairing.deriveRoomId(S));
});
check('deriveKey 返回 32 字节且确定性', () => {
    const S = LanPairing.generateSecret();
    const k1 = LanPairing.deriveKey(S), k2 = LanPairing.deriveKey(S);
    assert.strictEqual(k1.length, 32);
    assert.deepStrictEqual(Array.from(k1), Array.from(k2));
});
check('不同 S 派生出不同 R 与 key', () => {
    const s1 = LanPairing.generateSecret(), s2 = LanPairing.generateSecret();
    assert.notStrictEqual(LanPairing.deriveRoomId(s1), LanPairing.deriveRoomId(s2));
    assert.notStrictEqual(
        Buffer.from(LanPairing.deriveKey(s1)).toString('hex'),
        Buffer.from(LanPairing.deriveKey(s2)).toString('hex'));
});
check('R 与 key 由不同 info 派生（R != key 的前 32 字节）', () => {
    const S = LanPairing.generateSecret();
    assert.notStrictEqual(
        Buffer.from(LanPairing.deriveKey(S)).toString('hex'),
        LanPairing.deriveRoomId(S));
});

console.log('3) ChaCha20-Poly1305 AEAD：往返 / 防篡改 / 帧布局');
function makeFrame(key, plaintext) {
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const sealed = Chacha.chacha20poly1305(key, nonce).encrypt(plaintext);
    const frame = new Uint8Array(12 + sealed.length);
    frame.set(nonce);
    frame.set(sealed, 12);
    return frame;
}
function openFrame(key, frame) {
    const nonce = frame.slice(0, 12);
    const sealed = frame.slice(12);
    return Chacha.chacha20poly1305(key, nonce).decrypt(sealed);
}
check('往返解密得到原文', () => {
    const key = LanPairing.deriveKey(LanPairing.generateSecret());
    const pt = new Uint8Array([1, 2, 3, 4, 5]);
    const back = openFrame(key, makeFrame(key, pt));
    assert.deepStrictEqual(Array.from(back), Array.from(pt));
});
check('帧布局 = nonce(12)‖ct‖tag(16)', () => {
    const key = LanPairing.deriveKey(LanPairing.generateSecret());
    const pt = new Uint8Array([7, 8, 9]);
    assert.strictEqual(makeFrame(key, pt).length, 12 + 3 + 16);
});
check('篡改密文 -> 认证失败抛错', () => {
    const key = LanPairing.deriveKey(LanPairing.generateSecret());
    const frame = makeFrame(key, new Uint8Array([1, 2, 3, 4, 5]));
    const tampered = frame.slice();
    tampered[20] ^= 1;
    assert.throws(() => openFrame(key, tampered), /invalid tag/);
});
check('错密钥 -> 认证失败抛错', () => {
    const keyA = LanPairing.deriveKey(LanPairing.generateSecret());
    const keyB = LanPairing.deriveKey(LanPairing.generateSecret());
    const frame = makeFrame(keyA, new Uint8Array([1, 2, 3]));
    assert.throws(() => openFrame(keyB, frame), /invalid tag/);
});
check('短帧（< 12+16）无法通过', () => {
    const key = LanPairing.deriveKey(LanPairing.generateSecret());
    assert.throws(() => openFrame(key, new Uint8Array(27)), /at least 16 bytes/);
});

console.log('4) 配对码 normalize / isValid / format');
check('normalizeCode 大写、去连字符/空格、剔非 base32', () => {
    assert.strictEqual(LanPairing.normalizeCode('abcd-efgh ijkl'), 'ABCDEFGHIJKL');
    assert.strictEqual(LanPairing.normalizeCode('a-b-c 01'), 'ABC');
});
check('isValidCode 只认 26 字符大写 base32', () => {
    const s = LanPairing.generateSecret();
    assert.ok(LanPairing.isValidCode(s));
    assert.ok(!LanPairing.isValidCode(s.toLowerCase()));
    assert.ok(!LanPairing.isValidCode(s.slice(0, 25)));
    assert.ok(!LanPairing.isValidCode('0'.repeat(26)));   // '0' 不在 base32（A-Z 与 2-7）内
});
check('formatCode 按 9-9-8 分组', () => {
    const s = LanPairing.generateSecret();
    assert.strictEqual(LanPairing.formatCode(s), s.match(/.{1,9}/g).join('-'));
});

console.log('\n全部 ' + passed + ' 项断言通过 ✔');
