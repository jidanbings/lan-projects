class LanProjects {

    constructor() {
        this.$headerNotificationBtn = $('notification');
        this.$headerEditPairedDevicesBtn = $('edit-paired-devices');
        this.$footerPairedDevicesBadge = $$('.discovery-wrapper .badge-room-secret');
        this.$headerInstallBtn = $('install');

        this.deferredStyles = [
            "styles/styles-deferred.css"
        ];
        this.deferredScripts = [
            "scripts/browser-tabs-connector.js",
            "scripts/util.js",
            // Pure-JS ChaCha20 for E2E file encryption (fast in JS; WebCrypto's
            // crypto.subtle is unavailable on the non-HTTPS LAN origin). Loads
            // before network.js only defines classes, so runtime order is fine.
            "scripts/libs/chacha20.js",
            // HKDF-SHA256 / HMAC-SHA256 for key derivation (pair-room id + session key)
            "scripts/libs/crypto-util.js",
            // X25519 ECDH: 点选配对的临时密钥协商 + 每会话换钥（前向保密）
            "scripts/libs/x25519.js",
            "scripts/network.js",
            "scripts/ui.js",
            "scripts/libs/heic2any.min.js",
            "scripts/libs/no-sleep.min.js",
            "scripts/libs/qr-code.min.js",
            "scripts/libs/zip.min.js"
        ];

        this.registerServiceWorker();

        Events.on('beforeinstallprompt', e => this.onPwaInstallable(e));

        this.persistentStorage = new PersistentStorage();
        this.localization = new Localization();
        this.themeUI = new ThemeUI();
        this.backgroundCanvas = new BackgroundCanvas();
        this.headerUI = new HeaderUI();
        this.centerUI = new CenterUI();
        this.footerUI = new FooterUI();

        this.initialize()
            .then(_ => {
                console.log("Initialization completed.");
            });
    }

    async initialize() {
        // Translate page before fading in
        await this.localization.setInitialTranslation()
        console.log("Initial translation successful.");

        // Show "Loading..." until connected to WsServer
        await this.footerUI.showLoading();

        // Evaluate css shifting UI elements and fade in UI elements
        await this.evaluatePermissionsAndRoomSecrets();
        await this.headerUI.evaluateOverflowing();
        await this.headerUI.fadeIn();
        await this.footerUI._evaluateFooterBadges();
        await this.footerUI.fadeIn();
        await this.centerUI.fadeIn();
        await this.backgroundCanvas.fadeIn();

        // Load deferred assets
        console.log("Load deferred assets...");
        await this.loadDeferredAssets();
        if (this.failedAssets && this.failedAssets.length) {
            // 有脚本/样式没加载出来：继续 hydrate 只会在后面抛出更难懂的错
            // （要用到的类根本没定义）。这里直接给结论与刷新入口。
            console.error("Deferred assets failed to load:", this.failedAssets);
            await this.showStartupFailure();
            return;
        }
        console.log("Loading of deferred assets completed.");

        console.log("Hydrate UI...");
        await this.hydrate();
        console.log("UI hydrated.");

        // Evaluate url params as soon as ws is connected
        console.log("Evaluate URL params as soon as websocket connection is established.");
        Events.on('ws-connected', _ => this.evaluateUrlParams(), {once: true});
    }

    /**
     * 前端资源没加载全时的兜底呈现。
     *
     * 刻意不依赖 hydrate() 里创建的任何组件（它们可能正是因为脚本没加载出来而
     * 缺失）：直接改 DOM + 一句能照着做的话。旧实现在这种情况下会静默停在
     * "Loading..."，用户只能看到一个永远不动的转圈。
     */
    async showStartupFailure() {
        try {
            document.querySelectorAll('.opacity-0').forEach(el => el.classList.remove('opacity-0'));
            const container = document.querySelector('.toast-container');
            if (!container) return;
            container.hidden = false;
            const title = Localization.getTranslation('notifications.assets-load-failed-title');
            const body = Localization.getTranslation('notifications.assets-load-failed', null, {
                files: this.failedAssets.join(', ')
            });
            const reload = Localization.getTranslation('notifications.assets-reload');
            const box = document.createElement('div');
            box.className = 'startup-error';
            box.setAttribute('role', 'alert');
            box.innerHTML = `<strong></strong><p></p><button type="button"></button>`;
            box.querySelector('strong').textContent = title;
            box.querySelector('p').textContent = body;
            const btn = box.querySelector('button');
            btn.type = 'button';
            btn.textContent = reload;
            btn.addEventListener('click', _ => location.reload());
            container.innerHTML = '';
            container.appendChild(box);
        } catch (e) {
            console.error('Failed to render startup failure notice', e);
        }
    }

    registerServiceWorker() {
        if ('serviceWorker' in navigator) {
            navigator.serviceWorker
                .register('service-worker.js')
                .then(serviceWorker => {
                    console.log('Service Worker registered');
                    window.serviceWorker = serviceWorker
                });
        }
    }

    onPwaInstallable(e) {
        if (!window.matchMedia('(display-mode: standalone)').matches) {
            // only display install btn when not installed
            this.$headerInstallBtn.removeAttribute('hidden');
            this.$headerInstallBtn.addEventListener('click', () => {
                this.$headerInstallBtn.setAttribute('hidden', true);
                e.prompt();
            });
        }
        return e.preventDefault();
    }

    async evaluatePermissionsAndRoomSecrets() {
        // Check whether notification permissions have already been granted
        if ('Notification' in window && Notification.permission !== 'granted') {
            this.$headerNotificationBtn.removeAttribute('hidden');
        }

        let roomSecrets = await PersistentStorage.getAllRoomSecrets();
        if (roomSecrets.length > 0) {
            this.$headerEditPairedDevicesBtn.removeAttribute('hidden');
            this.$footerPairedDevicesBadge.removeAttribute('hidden');
        }
    }

    loadDeferredAssets() {
        this.failedAssets = [];
        const stylePromises = this.deferredStyles.map(url => this.loadAndApplyStylesheet(url));
        const scriptPromises = this.deferredScripts.map(url => this.loadAndApplyScript(url));

        return Promise.all([...stylePromises, ...scriptPromises]);
    }

    loadStyleSheet(url) {
        return new Promise((resolve, reject) => {
            let stylesheet = document.createElement('link');
            stylesheet.rel = 'preload';
            stylesheet.as = 'style';
            stylesheet.href = url;
            stylesheet.onload = _ => {
                stylesheet.onload = null;
                stylesheet.rel = 'stylesheet';
                resolve();
            };
            stylesheet.onerror = reject;

            document.head.appendChild(stylesheet);
        });
    }

    loadAndApplyStylesheet(url) {
        // 失败也必须 settle：旧实现在 catch 里从不 resolve，于是 Promise.all
        // 永远不完成，整个应用就永久停在 "Loading..." 上（连一句提示都没有）。
        return this.loadStyleSheet(url)
            .then(_ => console.log(`Stylesheet loaded successfully: ${url}`))
            .catch(error => {
                console.error('Error loading stylesheet:', error);
                this.failedAssets.push(url);
            });
    }

    loadScript(url) {
        return new Promise((resolve, reject) => {
            let script = document.createElement("script");
            script.src = url;
            script.onload = resolve;
            script.onerror = reject;

            document.body.appendChild(script);
        });
    }

    loadAndApplyScript(url) {
        // 同上：失败要 settle 并被记录下来，由 initialize() 统一给出可操作的提示。
        return this.loadScript(url)
            .then(_ => console.log(`Script loaded successfully: ${url}`))
            .catch(error => {
                console.error('Error loading script:', error);
                this.failedAssets.push(url);
            });
    }

    async hydrate() {
        this.aboutUI = new AboutUI();
        this.peersUI = new PeersUI();
        this.languageSelectDialog = new LanguageSelectDialog();
        this.receiveFileDialog = new ReceiveFileDialog();
        this.receiveRequestDialog = new ReceiveRequestDialog();
        this.sendTextDialog = new SendTextDialog();
        this.receiveTextDialog = new ReceiveTextDialog();
        this.pairDeviceDialog = new PairDeviceDialog();
        this.pairClickDialog = new PairClickDialog();
        this.clearDevicesDialog = new EditPairedDevicesDialog();
        this.publicRoomDialog = new PublicRoomDialog();
        this.base64Dialog = new Base64Dialog();
        this.shareTextDialog = new ShareTextDialog();
        this.toast = new Toast();
        this.notifications = new Notifications();
        this.networkStatusUI = new NetworkStatusUI();
        this.webShareTargetUI = new WebShareTargetUI();
        this.webFileHandlersUI = new WebFileHandlersUI();
        this.noSleepUI = new NoSleepUI();
        this.broadCast = new BrowserTabsConnector();
        this.server = new ServerConnection();
        this.peers = new PeersManager(this.server);

        // Show E2E encryption badge when encryption is established
        Events.on('e2e-encryption-active', _ => {
            const badge = document.getElementById('e2e-badge');
            if (badge) badge.removeAttribute('hidden');
        });
    }

    async evaluateUrlParams() {
        // get url params
        const urlParams = new URLSearchParams(window.location.search);
        const hash = window.location.hash.substring(1);

        // SECURITY: strip the query string from the address bar IMMEDIATELY, before
        // any of the handling below. A scanned pairing link carries the long-lived
        // master secret (?secret=...), and leaving it in the URL keeps it in the
        // address bar, the browser history, and any screenshot taken meanwhile.
        // Reading urlParams first (done above) makes this safe: the values are
        // already parsed into memory.
        const urlWithoutParams = getUrlWithoutArguments();
        window.history.replaceState({}, "Rewrite URL", urlWithoutParams);

        // evaluate url params
        if (urlParams.has('secret')) {
            // 扫码带出高熵配对密钥 S（26 字符 base32），自动加入配对。
            const secret = urlParams.get('secret');
            this.pairDeviceDialog._pairDeviceJoin(secret, {fromQr: true});
        }
        else if (urlParams.has('room_id')) {
            const roomId = urlParams.get('room_id');
            this.publicRoomDialog._joinPublicRoom(roomId);
        }
        else if (urlParams.has('base64text')) {
            const base64Text = urlParams.get('base64text');
            await this.base64Dialog.evaluateBase64Text(base64Text, hash);
        }
        else if (urlParams.has('base64zip')) {
            const base64Zip = urlParams.get('base64zip');
            await this.base64Dialog.evaluateBase64Zip(base64Zip, hash);
        }
        else if (urlParams.has("share_target")) {
            const shareTargetType = urlParams.get("share_target");
            const title = urlParams.get('title') || '';
            const text = urlParams.get('text') || '';
            const url = urlParams.get('url') || '';
            await this.webShareTargetUI.evaluateShareTarget(shareTargetType, title, text, url);
        }
        else if (urlParams.has("file_handler")) {
            await this.webFileHandlersUI.evaluateLaunchQueue();
        }
        else if (urlParams.has("init")) {
            const init = urlParams.get("init");
            if (init === "pair") {
                this.pairDeviceDialog._pairDeviceInitiate();
            }
            else if (init === "public_room") {
                this.publicRoomDialog._createPublicRoom();
            }
        }

        // remove url params from url
        // (already stripped at the top of this method, before handling; repeated
        // here is harmless and guards the case where handling added params)
        window.history.replaceState({}, "Rewrite URL", urlWithoutParams);

        console.log("URL params evaluated.");
    }
}

const lanProjects = new LanProjects();