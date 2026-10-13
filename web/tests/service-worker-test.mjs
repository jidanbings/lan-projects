// Service Worker 预缓存清单一致性测试。
//
// 背景：清单以前漏掉了新加的资源（例如点选配对新增的 scripts/libs/x25519.js），
// 而 SW 的缓存版本又是写死的——两者叠加会让升级后的用户拿到"半新半旧"的前端。
// 这个测试锁住"public/ 下的每个文件都在预缓存清单里"，防止再次漂移。
//
// 运行：node service-worker-test.mjs
import fs from 'fs';
import path from 'path';

const PUB = '../public';
const SW = '../public/service-worker.js';

let passed = 0, failed = 0;
const ok = (name, cond, extra = '') => {
    if (cond) { passed++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
    else { failed++; console.log('  ✗ FAIL: ' + name + (extra ? '  ' + extra : '')); }
};

const src = fs.readFileSync(SW, 'utf8');
const match = src.match(/const relativePathsToCache = \[([\s\S]*?)\];/);
ok('能解析出预缓存清单', !!match);

const listed = (match ? match[1] : '')
    .split('\n')
    .map(line => line.trim().replace(/,$/, '').replace(/'/g, '').trim())
    .filter(Boolean);

const files = [];
(function walk(dir, rel) {
    for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
        const full = path.join(dir, entry.name);
        const relPath = rel ? `${rel}/${entry.name}` : entry.name;
        if (entry.isDirectory()) walk(full, relPath);
        else if (entry.name !== 'service-worker.js') files.push(relPath);
    }
})(PUB, '');

const missing = files.filter(f => !listed.includes(f));
const stale = listed.filter(l => l !== './' && !files.includes(l));

console.log(`  清单 ${listed.size || listed.length} 条 / 实际文件 ${files.length} 个`);
ok('清单覆盖 public 下所有文件', missing.length === 0, missing.length ? '缺失: ' + missing.join(', ') : '');
ok('清单里没有已删除的文件', stale.length === 0, stale.length ? '多余: ' + stale.join(', ') : '');
ok('清单含根路径 ./', listed.includes('./'));
ok('新增的 x25519.js 在清单里（曾经漏过）', listed.includes('scripts/libs/x25519.js'));

// 缓存版本不再写死成远古值：它至少应该是个可维护的字符串，且 fetch 策略里
// 代码/语言包必须是网络优先（否则升级后仍可能吃旧代码）。
ok('缓存版本不是历史遗留的 v1.0.59', !src.includes("cacheVersion = 'v1.0.59'"));
ok('代码/页面/语言包走网络优先', /isCodeOrMarkup/.test(src) && /fromNetwork\(event\.request, 10000\)[\s\S]{0,80}fromCacheOrFail/.test(src));

console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
process.exit(failed ? 1 : 0);
