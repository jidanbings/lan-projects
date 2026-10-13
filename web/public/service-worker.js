// 缓存版本。
//
// 注意：这个字符串以前是写死的（v1.0.59），只有手动改它才会更新缓存——结果是
// 用户升级 App / 电脑端之后，浏览器仍然按旧缓存提供前端代码，界面与新功能对不上，
// 而且没有任何提示。现在代码与语言包一律**网络优先**（见 fetch 处理），所以即使
// 忘了改这里也不会再吃到旧代码；这个版本号只用于"改过预缓存清单后强制清一次旧缓存"。
const cacheVersion = 'v1.2.0';
const cacheTitle = `lan-projects-cache-${cacheVersion}`;
const relativePathsToCache = [
'./',
    'images/web.ico',
    'index.html',
    'lang/ar.json',
    'lang/be.json',
    'lang/bg.json',
    'lang/bn.json',
    'lang/ca.json',
    'lang/cs.json',
    'lang/da.json',
    'lang/de.json',
    'lang/en.json',
    'lang/es.json',
    'lang/et.json',
    'lang/eu.json',
    'lang/fa.json',
    'lang/fi.json',
    'lang/fr.json',
    'lang/he.json',
    'lang/hu.json',
    'lang/id.json',
    'lang/it.json',
    'lang/ja.json',
    'lang/kab.json',
    'lang/kn.json',
    'lang/ko.json',
    'lang/nb.json',
    'lang/nl.json',
    'lang/nn.json',
    'lang/pl.json',
    'lang/pt-BR.json',
    'lang/ro.json',
    'lang/ru.json',
    'lang/sk.json',
    'lang/ta.json',
    'lang/th.json',
    'lang/tr.json',
    'lang/uk.json',
    'lang/zh-CN.json',
    'lang/zh-HK.json',
    'lang/zh-TW.json',
    'manifest.json',
    'robots.txt',
    'scripts/browser-tabs-connector.js',
    'scripts/libs/chacha20.js',
    'scripts/libs/crypto-util.js',
    'scripts/libs/heic2any.min.js',
    'scripts/libs/no-sleep.min.js',
    'scripts/libs/qr-code.min.js',
    'scripts/libs/x25519.js',
    'scripts/libs/zip.min.js',
    'scripts/localization.js',
    'scripts/main.js',
    'scripts/network.js',
    'scripts/persistent-storage.js',
    'scripts/ui-main.js',
    'scripts/ui.js',
    'scripts/util.js',
    'scripts/worker/canvas-worker.js',
    'sounds/blop.mp3',
    'sounds/blop.ogg',
    'styles/styles-deferred.css',
    'styles/styles-main.css',
];
const relativePathsNotToCache = [
    'config'
]

self.addEventListener('install', function(event) {
    // Perform install steps
    console.log("Cache files for sw:", cacheVersion);
    event.waitUntil(
        caches.open(cacheTitle)
            .then(function(cache) {
                return cache
                    .addAll(relativePathsToCache)
                    .then(_ => {
                        console.log('All files cached for sw:', cacheVersion);
                        self.skipWaiting();
                    });
            })
    );
});

// fetch the resource from the network
const fromNetwork = (request, timeout) =>
    new Promise((resolve, reject) => {
        const timeoutId = setTimeout(reject, timeout);
        fetch(request, {cache: "no-store"})
            .then(response => {
                if (response.redirected) {
                    throw new Error("Fetch is redirect. Abort usage and cache!");
                }

                clearTimeout(timeoutId);
                resolve(response);

                // Prevent requests that are in relativePathsNotToCache from being cached
                if (doNotCacheRequest(request)) return;

                updateCache(request)
                    .then(() => console.log("Cache successfully updated for", request.url))
                    .catch(err => console.log("Cache could not be updated for", request.url, err));
            })
            .catch(error => {
                // Handle any errors that occurred during the fetch
                console.error(`Could not fetch ${request.url}.`);
                reject(error);
            });
    });

// fetch the resource from the browser cache
const fromCache = request =>
    caches
        .open(cacheTitle)
        .then(cache =>
            cache.match(request)
        );

const rootUrl = location.href.substring(0, location.href.length - "service-worker.js".length);
const rootUrlLength = rootUrl.length;

const doNotCacheRequest = request => {
    const requestRelativePath = request.url.substring(rootUrlLength);
    // Never cache: the config endpoint, or blob: URL fetches - a received-file
    // blob would otherwise be written into the Cache API and balloon the
    // app/browser storage by the size of every transferred file.
    return relativePathsNotToCache.indexOf(requestRelativePath) !== -1
        || request.url.startsWith('blob:')
};

// cache the current page to make it available for offline
const updateCache = request => new Promise((resolve, reject) => {
    caches
        .open(cacheTitle)
        .then(cache =>
            fetch(request, {cache: "no-store"})
                .then(response => {
                    if (response.redirected) {
                        throw new Error("Fetch is redirect. Abort usage and cache!");
                    }

                    cache
                        .put(request, response)
                        .then(() => resolve());
                })
                .catch(reason => reject(reason))
        );
});

/**
 * 代码与语言包是否走"网络优先"。
 *
 * 服务器就在局域网里，走一次网络的代价极小；而 cache-first 的代价是升级后
 * 用户可能长期看到旧前端（界面与新功能对不上，且毫无提示）。所以凡是"会随版本
 * 变化"的资源一律网络优先、失败再回落缓存，保留离线可用性。
 */
const isCodeOrMarkup = request => {
    const path = new URL(request.url).pathname;
    return path.endsWith('/') || /\.(html|js|css|json)$/i.test(path);
};

// general strategy when making a request:
// 1. 代码/页面/语言包：先取网络（同时更新缓存），取不到再回落缓存；
// 2. 其余资源（图片、声音等）：先取缓存，没有再去网络。
self.addEventListener('fetch', function(event) {
    if (event.request.method === "POST") {
        // Requests related to Web Share Target.
        event.respondWith((async () => {
            const share_url = await evaluateRequestData(event.request);
            return Response.redirect(encodeURI(share_url), 302);
        })());
    }
    else {
        // Regular requests not related to Web Share Target:
        // If request is excluded from cache -> respondWith fromNetwork
        // else -> try fromCache first
        event.respondWith(
            doNotCacheRequest(event.request)
                ? fromNetwork(event.request, 10000)
                : (isCodeOrMarkup(event.request)
                    ? fromNetwork(event.request, 10000)
                        .catch(error => {
                            console.warn("Network failed for", event.request.url, "- falling back to cache");
                            return fromCacheOrFail(event.request);
                        })
                    : fromCacheOrFail(event.request)
                        .catch(error => {
                            console.warn("Not in cache:", event.request.url, "- fetching from network");
                            return fromNetwork(event.request, 10000);
                        }))
        );
    }
});

const fromCacheOrFail = request =>
    fromCache(request).then(rsp => {
        if (!rsp) throw new Error("No match found.");
        return rsp;
    });


// on activation, we clean up the previously registered service workers
self.addEventListener('activate', evt => {
    console.log("Activate sw:", cacheVersion);
    evt.waitUntil(clients.claim());
    return evt.waitUntil(
        caches
            .keys()
            .then(cacheNames => {
                return Promise.all(
                    cacheNames.map(cacheName => {
                        if (cacheName !== cacheTitle) {
                            console.log("Delete cache:", cacheName);
                            return caches.delete(cacheName);
                        }
                    })
                );
            })
    )
});

const evaluateRequestData = function (request) {
    return new Promise(async (resolve) => {
        const formData = await request.formData();
        const title = formData.get("title");
        const text = formData.get("text");
        const url = formData.get("url");
        const files = formData.getAll("allfiles");

        const requestUrl = request.url;

        if (files && files.length > 0) {
            let fileObjects = [];
            for (let i=0; i<files.length; i++) {
                fileObjects.push({
                    name: files[i].name,
                    buffer: await files[i].arrayBuffer()
                });
            }

            const DBOpenRequest = indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = e => {
                const db = e.target.result;
                for (let i = 0; i < fileObjects.length; i++) {
                    const transaction = db.transaction('share_target_files', 'readwrite');
                    const objectStore = transaction.objectStore('share_target_files');

                    const objectStoreRequest = objectStore.add(fileObjects[i]);
                    objectStoreRequest.onsuccess = _ => {
                        if (i === fileObjects.length - 1) resolve(requestUrl + '?share_target=files');
                    }
                }
            }
            DBOpenRequest.onerror = _ => {
                resolve(requestUrl);
            }
        }
        else {
            let urlArgument = '?share_target=text';

            if (title) urlArgument += `&title=${title}`;
            if (text) urlArgument += `&text=${text}`;
            if (url) urlArgument += `&url=${url}`;

            resolve(requestUrl + urlArgument);
        }
    });
}
