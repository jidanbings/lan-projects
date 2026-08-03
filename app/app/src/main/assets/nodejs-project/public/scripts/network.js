class ServerConnection {

    // Set by a 'ws-chunk-prep' message: the peer id whose binary chunk is next.
    // Binary WebSocket frames carry no routing info, so the sender announces
    // the recipient first (JSON) and the receiver remembers who sent it.
    _pendingBinaryFrom = null;

    constructor() {
        Events.on('pagehide', _ => this._disconnect());
        Events.on(window.visibilityChangeEvent, _ => this._onVisibilityChange());

        if (navigator.connection) {
            navigator.connection.addEventListener('change', _ => this._reconnect());
        }

        Events.on('room-secrets', e => this.send({ type: 'room-secrets', roomSecrets: e.detail }));
        Events.on('join-ip-room', _ => this.send({ type: 'join-ip-room'}));
        Events.on('room-secrets-deleted', e => this.send({ type: 'room-secrets-deleted', roomSecrets: e.detail}));
        Events.on('regenerate-room-secret', e => this.send({ type: 'regenerate-room-secret', roomSecret: e.detail}));
        Events.on('pair-device-initiate', _ => this._onPairDeviceInitiate());
        Events.on('pair-device-join', e => this._onPairDeviceJoin(e.detail));
        Events.on('pair-device-cancel', _ => this.send({ type: 'pair-device-cancel' }));

        Events.on('create-public-room', _ => this._onCreatePublicRoom());
        Events.on('join-public-room', e => this._onJoinPublicRoom(e.detail.roomId, e.detail.createIfInvalid));
        Events.on('leave-public-room', _ => this._onLeavePublicRoom());

        Events.on('offline', _ => clearTimeout(this._reconnectTimer));
        Events.on('online', _ => this._connect());

        this._getConfig().then(() => this._connect());
    }

    _getConfig() {
        console.log("Loading config...")
        return new Promise((resolve, reject) => {
            let xhr = new XMLHttpRequest();
            xhr.addEventListener("load", () => {
                if (xhr.status === 200) {
                    // Config received
                    let config = JSON.parse(xhr.responseText);
                    console.log("Config loaded:", config)
                    this._config = config;
                    Events.fire('config', config);
                    resolve()
                } else if (xhr.status < 200 || xhr.status >= 300) {
                    retry(xhr);
                }
            })

            xhr.addEventListener("error", _ => {
                retry(xhr);
            });

            function retry(request) {
                setTimeout(function () {
                    openAndSend(request)
                }, 1000)
            }

            function openAndSend() {
                xhr.open('GET', 'config');
                xhr.send();
            }

            openAndSend(xhr);
        })
    }

    _setWsConfig(wsConfig) {
        this._wsConfig = wsConfig;
        Events.fire('ws-config', wsConfig);
    }

    _connect() {
        clearTimeout(this._reconnectTimer);
        // 不能以 navigator.onLine 判断是否连接：服务器就是本页同源，
        // 纯局域网 / 热点（无外网）时 navigator.onLine 为 false 但本地服务器可达，
        // 以前这里会因 _isOffline() 直接 return，导致配对 / 公共房间被误拦。
        if (this._isConnected() || this._isConnecting()) return;
        if (this._isReconnect) {
            Events.fire('notify-user', {
                message: Localization.getTranslation("notifications.connecting"),
                persistent: true
            });
        }
        const ws = new WebSocket(this._endpoint());
        ws.binaryType = 'arraybuffer';
        ws.onopen = _ => this._onOpen();
        ws.onmessage = e => this._onMessage(e.data);
        ws.onclose = _ => this._onDisconnect();
        ws.onerror = e => this._onError(e);
        this._socket = ws;
    }

    _onOpen() {
        console.log('WS: server connected');
        Events.fire('ws-connected');
        if (this._isReconnect) Events.fire('notify-user', Localization.getTranslation("notifications.connected"));
    }

    _onPairDeviceInitiate() {
        if (!this._isConnected()) {
            Events.fire('notify-user', Localization.getTranslation("notifications.online-requirement-pairing"));
            return;
        }
        this.send({ type: 'pair-device-initiate' });
    }

    _onPairDeviceJoin(pairKey) {
        if (!this._isConnected()) {
            setTimeout(() => this._onPairDeviceJoin(pairKey), 1000);
            return;
        }
        this.send({ type: 'pair-device-join', pairKey: pairKey });
    }

    _onCreatePublicRoom() {
        if (!this._isConnected()) {
            Events.fire('notify-user', Localization.getTranslation("notifications.online-requirement-public-room"));
            return;
        }
        this.send({ type: 'create-public-room' });
    }

    _onJoinPublicRoom(roomId, createIfInvalid) {
        if (!this._isConnected()) {
            setTimeout(() => this._onJoinPublicRoom(roomId), 1000);
            return;
        }
        this.send({ type: 'join-public-room', publicRoomId: roomId, createIfInvalid: createIfInvalid });
    }

    _onLeavePublicRoom() {
        if (!this._isConnected()) {
            setTimeout(() => this._onLeavePublicRoom(), 1000);
            return;
        }
        this.send({ type: 'leave-public-room' });
    }

    _onMessage(msg) {
        // A non-string message is a raw binary file chunk (WebSocket binary
        // frame). The server announces the sender just before it via a
        // 'ws-chunk-prep' message, which set _pendingBinaryFrom.
        if (typeof msg !== 'string') {
            Events.fire('ws-binary', { from: this._pendingBinaryFrom, data: msg });
            return;
        }
        msg = JSON.parse(msg);
        // Only log important non-frequent messages (UI already shows the rest)
        const noisy = ['ping','pong','ws-chunk','ws-chunk-prep','partition','partition-received','progress',
            'file-transfer-complete','message-transfer-complete','peer-joined','peer-left',
            'files-transfer-response','request','header','text','display-name-changed','signal'];
        if (!noisy.includes(msg.type)) {
            console.log('WS receive:', msg);
        }
        switch (msg.type) {
            case 'ws-chunk-prep':
                this._pendingBinaryFrom = msg.from;
                break;
            case 'ws-config':
                this._setWsConfig(msg.wsConfig);
                break;
            case 'peers':
                this._onPeers(msg);
                break;
            case 'peer-joined':
                Events.fire('peer-joined', msg);
                break;
            case 'peer-left':
                Events.fire('peer-left', msg);
                break;
            case 'signal':
                Events.fire('signal', msg);
                break;
            case 'ping':
                this.send({ type: 'pong' });
                break;
            case 'display-name':
                this._onDisplayName(msg);
                break;
            case 'pair-device-initiated':
                Events.fire('pair-device-initiated', msg);
                break;
            case 'pair-device-joined':
                Events.fire('pair-device-joined', msg);
                break;
            case 'pair-device-join-key-invalid':
                Events.fire('pair-device-join-key-invalid');
                break;
            case 'pair-device-canceled':
                Events.fire('pair-device-canceled', msg.pairKey);
                break;
            case 'join-key-rate-limit':
                Events.fire('notify-user', Localization.getTranslation("notifications.rate-limit-join-key"));
                break;
            case 'secret-room-deleted':
                Events.fire('secret-room-deleted', msg.roomSecret);
                break;
            case 'room-secret-regenerated':
                Events.fire('room-secret-regenerated', msg);
                break;
            case 'public-room-id-invalid':
                Events.fire('public-room-id-invalid', msg.publicRoomId);
                break;
            case 'public-room-created':
                Events.fire('public-room-created', msg.roomId);
                break;
            case 'public-room-left':
                Events.fire('public-room-left');
                break;
            case 'request':
            case 'header':
            case 'partition':
            case 'partition-received':
            case 'progress':
            case 'files-transfer-response':
            case 'file-transfer-complete':
            case 'message-transfer-complete':
            case 'text':
            case 'display-name-changed':
                // ws-fallback
                if (this._wsConfig.wsFallback) {
                    Events.fire('ws-relay', JSON.stringify(msg));
                }
                else {
                    console.log("WS receive: message type is for websocket fallback only but websocket fallback is not activated on this instance.")
                }
                break;
            case 'ws-chunk':
                // ws-fallback
                if (this._wsConfig.wsFallback) {
                    Events.fire('ws-relay', JSON.stringify(msg));
                }
                else {
                    console.log("WS receive: message type is for websocket fallback only but websocket fallback is not activated on this instance.")
                }
                break;
            default:
                console.error('WS receive: unknown message type', msg);
        }
    }

    send(msg) {
        if (!this._isConnected()) return;
        // Only log important messages (UI already shows file transfers, peer events, etc.)
        const noisy = ['pong','ws-chunk','ws-chunk-prep','partition','partition-received','progress',
            'file-transfer-complete','message-transfer-complete','signal','request','header'];
        if (!noisy.includes(msg.type)) {
            console.log("WS send:", msg)
        }
        this._socket.send(JSON.stringify(msg));
    }

    /** Send raw binary data (a file chunk) as a binary WebSocket frame. */
    sendBinary(data) {
        if (!this._isConnected()) return;
        this._socket.send(data);
    }

    _onPeers(msg) {
        // Debug: record exactly what the server told us is in this room, so the
        // Android 查看日志 screen can show whether a fresh server really sends
        // an empty peer list or whether it is sending stale peers.
        console.log('[debug] server sent peers, count='
            + (msg.peers ? msg.peers.length : 0)
            + ' room=' + msg.roomType + ':' + msg.roomId
            + ' names=' + (msg.peers || []).map(p => p.name ? p.name.deviceName : '?').join(','));
        Events.fire('peers', msg);
    }

    _onDisplayName(msg) {
        // Add peerId and peerIdHash to sessionStorage to authenticate as the same device on page reload
        sessionStorage.setItem('peer_id', msg.peerId);
        sessionStorage.setItem('peer_id_hash', msg.peerIdHash);

        // Add peerId to localStorage to mark it for other lan-projects tabs on the same browser
        BrowserTabsConnector
            .addPeerIdToLocalStorage()
            .then(peerId => {
                if (!peerId) return;

                // Only now join rooms
                Events.fire('join-ip-room');
                PersistentStorage.getAllRoomSecrets()
                    .then(roomSecrets => {
                        Events.fire('room-secrets', roomSecrets);
                    });
            });

        Events.fire('display-name', msg);
    }

    _endpoint() {
        const protocol = location.protocol.startsWith('https') ? 'wss' : 'ws';
        // Check whether the instance specifies another signaling server otherwise use the current instance for signaling
        let wsServerDomain = this._config.signalingServer
            ? this._config.signalingServer
            : location.host + location.pathname;

        let wsUrl = new URL(protocol + '://' + wsServerDomain + 'server');

        wsUrl.searchParams.append('webrtc_supported', window.isRtcSupported ? 'true' : 'false');

        const peerId = sessionStorage.getItem('peer_id');
        const peerIdHash = sessionStorage.getItem('peer_id_hash');
        if (peerId && peerIdHash) {
            wsUrl.searchParams.append('peer_id', peerId);
            wsUrl.searchParams.append('peer_id_hash', peerIdHash);
        }

        return wsUrl.toString();
    }

    _disconnect() {
        this.send({ type: 'disconnect' });

        const peerId = sessionStorage.getItem('peer_id');
        BrowserTabsConnector
            .removePeerIdFromLocalStorage(peerId)
            .then(_ => {
                console.log("successfully removed peerId from localStorage");
            });

        if (!this._socket) return;

        this._socket.onclose = null;
        this._socket.close();
        this._socket = null;
        Events.fire('ws-disconnected');
        this._isReconnect = true;
    }

    _onDisconnect() {
        console.log('WS: server disconnected');
        setTimeout(() => {
            this._isReconnect = true;
            Events.fire('ws-disconnected');
            this._reconnectTimer = setTimeout(() => this._connect(), 1000);
        }, 100); //delay for 100ms to prevent flickering on page reload
    }

    _onVisibilityChange() {
        if (window.hiddenProperty) return;
        this._connect();
    }

    _isConnected() {
        return this._socket && this._socket.readyState === this._socket.OPEN;
    }

    _isConnecting() {
        return this._socket && this._socket.readyState === this._socket.CONNECTING;
    }

    _onError(e) {
        console.error(e);
    }

    _reconnect() {
        this._disconnect();
        this._connect();
    }
}

class Peer {

    constructor(serverConnection, isCaller, peerId, roomType, roomId) {
        this._server = serverConnection;
        this._isCaller = isCaller;
        this._peerId = peerId;

        this._roomIds = {};
        this._encryptionKey = null;
        this._keyExchangeDone = false;

        this._updateRoomIds(roomType, roomId);

        this._filesQueue = [];
        this._busy = false;

        // evaluate auto accept
        this._evaluateAutoAccept();
    }

    sendJSON(message) {
        this._send(JSON.stringify(message));
    }

    // Is overwritten in expanding classes
    _send(message) {}

    sendDisplayName(displayName) {
        this.sendJSON({type: 'display-name-changed', displayName: displayName});
    }

    _isSameBrowser() {
        return BrowserTabsConnector.peerIsSameBrowser(this._peerId);
    }

    _isPaired() {
        return !!this._roomIds['secret'];
    }

    _getPairSecret() {
        return this._roomIds['secret'];
    }

    _regenerationOfPairSecretNeeded() {
        return this._getPairSecret() && this._getPairSecret().length !== 256
    }

    _getRoomTypes() {
        return Object.keys(this._roomIds);
    }

    _updateRoomIds(roomType, roomId) {
        const roomTypeIsSecret = roomType === "secret";
        const roomIdIsNotPairSecret = this._getPairSecret() !== roomId;

        // if peer is another browser tab, peer is not identifiable with roomSecret as browser tabs share all roomSecrets
        // -> do not delete duplicates and do not regenerate room secrets
        if (!this._isSameBrowser()
            && roomTypeIsSecret
            && this._isPaired()
            && roomIdIsNotPairSecret) {
            // multiple roomSecrets with same peer -> delete old roomSecret
            PersistentStorage
                .deleteRoomSecret(this._getPairSecret())
                .then(deletedRoomSecret => {
                    if (deletedRoomSecret) console.log("Successfully deleted duplicate room secret with same peer: ", deletedRoomSecret);
                });
        }

        this._roomIds[roomType] = roomId;

        if (!this._isSameBrowser()
            &&  roomTypeIsSecret
            &&  this._isPaired()
            &&  this._regenerationOfPairSecretNeeded()
            &&  this._isCaller) {
            // increase security by initiating the increase of the roomSecret length
            // from 64 chars (<v1.7.0) to 256 chars (v1.7.0+)
            console.log('RoomSecret is regenerated to increase security')
            Events.fire('regenerate-room-secret', this._getPairSecret());
        }

        // Re-evaluate the encryption key whenever a room is added (e.g. a pairing
        // secret is established after the initial IP room). No-op if already derived.
        this._initEncryption();
    }

    _removeRoomType(roomType) {
        delete this._roomIds[roomType];

        Events.fire('room-type-removed', {
            peerId: this._peerId,
            roomType: roomType
        });
    }

    _evaluateAutoAccept() {
        if (!this._isPaired()) {
            this._setAutoAccept(false);
            return;
        }

        PersistentStorage
            .getRoomSecretEntry(this._getPairSecret())
            .then(roomSecretEntry => {
                const autoAccept = roomSecretEntry
                    ? roomSecretEntry.entry.auto_accept
                    : false;
                this._setAutoAccept(autoAccept);
            })
            .catch(_ => {
                this._setAutoAccept(false);
            });
    }

    _setAutoAccept(autoAccept) {
        this._autoAccept = !this._isSameBrowser()
            ? autoAccept
            : false;
    }

    // ---- End-to-end encryption ---------------------------------------------
    // Shared by every peer connection type (WebRTC data channel and WebSocket
    // relay), so the key derivation and cipher are identical on all clients:
    // the web app, the Android app (same frontend in a WebView) and any future
    // desktop app embedding the same frontend. File data is never sent without
    // an established key.

    _initEncryption() {
        if (this._keyExchangeDone) return;
        // Security policy: only PAIRED peers may transfer. The E2E key is
        // derived from the PAIRING SECRET shared only with this peer
        // (established out-of-band via the pairing QR / 6-digit code, so an
        // on-network attacker cannot derive it). The dedup in _updateRoomIds
        // keeps exactly one secret per peer, so both sides hold the same one
        // and derive the same key - transfers never corrupt.
        //
        // Unpaired peers get NO key on purpose: _requireEncryption() then
        // refuses the transfer. There is deliberately no public fallback key.
        const secret = this._getPairSecret();
        if (secret && secret.length >= 4) {
            this._deriveKey(secret + 'lan-projects-encryption-salt');
        }
    }

    _deriveKey(secret) {
        // Create a 32-byte key from the pairing secret using simple hashing
        // This works in all browsers (no crypto.subtle dependency)
        const encoder = new TextEncoder();
        const data = encoder.encode(secret);
        const key = new Uint8Array(32);
        for (let i = 0; i < 32; i++) {
            let hash = i;
            for (let j = 0; j < data.length; j++) {
                hash = ((hash << 5) - hash) + data[j];
                hash = hash & hash;
            }
            key[i] = Math.abs(hash) % 256;
        }
        this._encryptionKey = key;
        this._keyExchangeDone = true;
        Events.fire('e2e-encryption-active');
        const badge = document.getElementById('e2e-badge');
        if (badge) badge.removeAttribute('hidden');
    }

    _encrypt(data) {
        if (!this._encryptionKey) return data;
        // ChaCha20 stream cipher - strong (TLS 1.3 / WireGuard) and far faster
        // in pure JS than AES (noble-ciphers, ~12x vs aes-js), which matters on
        // weak phones for big files. WebCrypto's crypto.subtle is unavailable
        // on the non-HTTPS LAN origin, so this is the fast pure-JS choice.
        // A fresh random 12-byte nonce is prefixed to the ciphertext.
        const nonce = crypto.getRandomValues(new Uint8Array(12));
        const cipher = new Uint8Array(
            window.Chacha.chacha20(this._encryptionKey, nonce, new Uint8Array(data)));
        const result = new Uint8Array(nonce.length + cipher.length);
        result.set(nonce);
        result.set(cipher, nonce.length);
        return result.buffer;
    }

    _decrypt(data) {
        if (!this._encryptionKey) return data;
        const view = new Uint8Array(data);
        if (view.length < 12) return data;
        const nonce = view.slice(0, 12);
        const cipher = view.slice(12);
        return window.Chacha.chacha20(this._encryptionKey, nonce, cipher).buffer;
    }

    /**
     * Make sure the E2E key exists before sending file data. Returns the key,
     * or null if encryption could not be established (in which case the
     * transfer is rejected).
     */
    _requireEncryption() {
        if (!this._encryptionKey) this._initEncryption();
        if (!this._encryptionKey) {
            console.error('Blocked: peers not paired, transfer rejected');
            Events.fire('notify-user', { message: '❌ 未配对，已拒绝传输。请先点击顶栏 🔗 配对' });
            this._busy = false;
            this._filesQueue = [];
            return null;
        }
        return this._encryptionKey;
    }

    async requestFileTransfer(files) {
        let header = [];
        let totalSize = 0;
        let imagesOnly = true
        for (let i=0; i<files.length; i++) {
            Events.fire('set-progress', {peerId: this._peerId, progress: 0.8*i/files.length, status: 'prepare'})
            header.push({
                name: files[i].name,
                mime: files[i].type,
                size: files[i].size
            });
            totalSize += files[i].size;
            if (files[i].type.split('/')[0] !== 'image') imagesOnly = false;
        }

        Events.fire('set-progress', {peerId: this._peerId, progress: 0.8, status: 'prepare'})

        let dataUrl = '';
        if (files[0].type.split('/')[0] === 'image') {
            try {
                dataUrl = await getThumbnailAsDataUrl(files[0], 400, null, 0.9);
            } catch (e) {
                console.error(e);
            }
        }

        Events.fire('set-progress', {peerId: this._peerId, progress: 1, status: 'prepare'})

        this._filesRequested = files;

        this.sendJSON({type: 'request',
            header: header,
            totalSize: totalSize,
            imagesOnly: imagesOnly,
            thumbnailDataUrl: dataUrl
        });
        Events.fire('set-progress', {peerId: this._peerId, progress: 0, status: 'wait'})
    }

    async sendFiles() {
        for (let i=0; i<this._filesRequested.length; i++) {
            this._filesQueue.push(this._filesRequested[i]);
        }
        this._filesRequested = null
        if (this._busy) return;
        this._dequeueFile();
    }

    _dequeueFile() {
        this._busy = true;
        const file = this._filesQueue.shift();
        this._sendFile(file);
    }

    async _sendFile(file) {
        this.sendJSON({
            type: 'header',
            size: file.size,
            name: file.name,
            mime: file.type
        });
        this._chunker = new FileChunker(file,
            chunk => this._send(chunk),
            offset => this._onPartitionEnd(offset));
        this._chunker.nextPartition();
    }

    _onPartitionEnd(offset) {
        this.sendJSON({ type: 'partition', offset: offset });
    }

    _onReceivedPartitionEnd(offset) {
        this.sendJSON({ type: 'partition-received', offset: offset });
    }

    _sendNextPartition() {
        if (!this._chunker || this._chunker.isFileEnd()) return;
        this._chunker.nextPartition();
    }

    _sendProgress(progress) {
        this.sendJSON({ type: 'progress', progress: progress });
    }

    _onMessage(message) {
        if (typeof message !== 'string') {
            this._onChunkReceived(message);
            return;
        }
        const messageJSON = JSON.parse(message);
        switch (messageJSON.type) {
            case 'request':
                this._onFilesTransferRequest(messageJSON);
                break;
            case 'header':
                this._onFileHeader(messageJSON);
                break;
            case 'partition':
                this._onReceivedPartitionEnd(messageJSON);
                break;
            case 'partition-received':
                this._sendNextPartition();
                break;
            case 'progress':
                this._onDownloadProgress(messageJSON.progress);
                break;
            case 'files-transfer-response':
                this._onFileTransferRequestResponded(messageJSON);
                break;
            case 'file-transfer-complete':
                this._onFileTransferCompleted();
                break;
            case 'message-transfer-complete':
                this._onMessageTransferCompleted();
                break;
            case 'text':
                this._onTextReceived(messageJSON);
                break;
            case 'display-name-changed':
                this._onDisplayNameChanged(messageJSON);
                break;
        }
    }

    _onFilesTransferRequest(request) {
        if (this._requestPending) {
            // Only accept one request at a time per peer
            this.sendJSON({type: 'files-transfer-response', accepted: false});
            return;
        }
        if (window.iOS && request.totalSize >= 200*1024*1024) {
            // iOS Safari can only put 400MB at once to memory.
            // Request to send them in chunks of 200MB instead:
            this.sendJSON({type: 'files-transfer-response', accepted: false, reason: 'ios-memory-limit'});
            return;
        }

        this._requestPending = request;

        if (this._autoAccept) {
            // auto accept if set via Edit Paired Devices Dialog
            this._respondToFileTransferRequest(true);
            return;
        }

        // default behavior: show user transfer request
        Events.fire('files-transfer-request', {
            request: request,
            peerId: this._peerId
        });
    }

    _respondToFileTransferRequest(accepted) {
        this.sendJSON({type: 'files-transfer-response', accepted: accepted});
        if (accepted) {
            this._requestAccepted = this._requestPending;
            this._totalBytesReceived = 0;
            this._busy = true;
            this._filesReceived = [];
        }
        this._requestPending = null;
    }

    _onFileHeader(header) {
        if (this._requestAccepted && this._requestAccepted.header.length) {
            this._lastProgress = 0;
            this._digester = new FileDigester({size: header.size, name: header.name, mime: header.mime},
                this._requestAccepted.totalSize,
                this._totalBytesReceived,
                fileBlob => this._onFileReceived(fileBlob)
            );
        }
    }

    _abortTransfer() {
        Events.fire('set-progress', {peerId: this._peerId, progress: 1, status: 'wait'});
        Events.fire('notify-user', Localization.getTranslation("notifications.files-incorrect"));
        this._filesReceived = [];
        this._requestAccepted = null;
        this._digester = null;
        throw new Error("Received files differ from requested files. Abort!");
    }

    _onChunkReceived(chunk) {
        if(!this._digester || !(chunk.byteLength || chunk.size)) return;

        this._digester.unchunk(chunk);
        const progress = this._digester.progress;

        if (progress > 1) {
            this._abortTransfer();
        }

        this._onDownloadProgress(progress);

        // occasionally notify sender about our progress
        if (progress - this._lastProgress < 0.005 && progress !== 1) return;
        this._lastProgress = progress;
        this._sendProgress(progress);
    }

    _onDownloadProgress(progress) {
        Events.fire('set-progress', {peerId: this._peerId, progress: progress, status: 'transfer'});
    }

    async _onFileReceived(fileBlob) {
        const acceptedHeader = this._requestAccepted.header.shift();
        this._totalBytesReceived += fileBlob.size;

        this.sendJSON({type: 'file-transfer-complete'});

        const sameSize = fileBlob.size === acceptedHeader.size;
        const sameName = fileBlob.name === acceptedHeader.name
        if (!sameSize || !sameName) {
            this._abortTransfer();
        }

        // include for compatibility with Android app
        Events.fire('file-received', fileBlob);

        this._filesReceived.push(fileBlob);
        if (!this._requestAccepted.header.length) {
            this._busy = false;
            Events.fire('set-progress', {peerId: this._peerId, progress: 0, status: 'process'});
            Events.fire('files-received', {peerId: this._peerId, files: this._filesReceived, imagesOnly: this._requestAccepted.imagesOnly, totalSize: this._requestAccepted.totalSize});
            this._filesReceived = [];
            this._requestAccepted = null;
        }
    }

    _onFileTransferCompleted() {
        this._chunker = null;
        if (!this._filesQueue.length) {
            this._busy = false;
            Events.fire('notify-user', Localization.getTranslation("notifications.file-transfer-completed"));
            Events.fire('files-sent'); // used by Android app
        }
        else {
            this._dequeueFile();
        }
    }

    _onFileTransferRequestResponded(message) {
        if (!message.accepted) {
            Events.fire('set-progress', {peerId: this._peerId, progress: 1, status: 'wait'});
            this._filesRequested = null;
            if (message.reason === 'ios-memory-limit') {
                Events.fire('notify-user', Localization.getTranslation("notifications.ios-memory-limit"));
            }
            return;
        }
        Events.fire('file-transfer-accepted');
        Events.fire('set-progress', {peerId: this._peerId, progress: 0, status: 'transfer'});
        this.sendFiles();
    }

    _onMessageTransferCompleted() {
        Events.fire('notify-user', Localization.getTranslation("notifications.message-transfer-completed"));
    }

    sendText(text) {
        const unescaped = btoa(unescape(encodeURIComponent(text)));
        this.sendJSON({ type: 'text', text: unescaped });
    }

    _onTextReceived(message) {
        if (!message.text) return;
        const escaped = decodeURIComponent(escape(atob(message.text)));
        Events.fire('text-received', { text: escaped, peerId: this._peerId });
        this.sendJSON({ type: 'message-transfer-complete' });
    }

    _onDisplayNameChanged(message) {
        const displayNameHasChanged = this._displayName !== message.displayName

        if (message.displayName && displayNameHasChanged) {
            this._displayName = message.displayName;
        }

        Events.fire('peer-display-name-changed', {peerId: this._peerId, displayName: message.displayName});

        if (!displayNameHasChanged) return;
        Events.fire('notify-peer-display-name-changed', this._peerId);
    }
}

class RTCPeer extends Peer {

    constructor(serverConnection, isCaller, peerId, roomType, roomId, rtcConfig, peerLanIp) {
        super(serverConnection, isCaller, peerId, roomType, roomId);

        this.rtcSupported = true;
        this.rtcConfig = rtcConfig
        this._peerLanIp = peerLanIp

        if (!this._isCaller) return; // we will listen for a caller
        this._connect();
    }

    _connect() {
        if (!this._conn || this._conn.signalingState === "closed") this._openConnection();

        if (this._isCaller) {
            this._openChannel();
        }
        else {
            this._conn.ondatachannel = e => this._onChannelOpened(e);
        }
    }

    _openConnection() {
        this._conn = new RTCPeerConnection(this.rtcConfig);
        this._conn.onicecandidate = e => this._onIceCandidate(e);
        this._conn.onicecandidateerror = e => this._onError(e);
        this._conn.onconnectionstatechange = _ => this._onConnectionStateChange();
        this._conn.oniceconnectionstatechange = e => this._onIceConnectionStateChange(e);
    }

    _openChannel() {
        if (!this._conn) return;

        const channel = this._conn.createDataChannel('data-channel', {
            ordered: true,
            reliable: true // Obsolete. See https://developer.mozilla.org/en-US/docs/Web/API/RTCDataChannel/reliable
        });
        channel.onopen = e => this._onChannelOpened(e);
        channel.onerror = e => this._onError(e);

        this._conn
            .createOffer()
            .then(d => this._onDescription(d))
            .catch(e => this._onError(e));
    }

    _onDescription(description) {
        // description.sdp = description.sdp.replace('b=AS:30', 'b=AS:1638400');
        this._conn
            .setLocalDescription(description)
            .then(_ => this._sendSignal({ sdp: description }))
            .catch(e => this._onError(e));
    }

    _onIceCandidate(event) {
        if (!event.candidate) return;
        this._sendSignal({ ice: event.candidate });
    }

    onServerMessage(message) {
        if (!this._conn) this._connect();

        if (message.sdp) {
            this._conn
                .setRemoteDescription(message.sdp)
                .then(_ => {
                    if (message.sdp.type === 'offer') {
                        return this._conn
                            .createAnswer()
                            .then(d => this._onDescription(d));
                    }
                })
                .catch(e => this._onError(e));
        }
        else if (message.ice) {
            this._conn
                .addIceCandidate(new RTCIceCandidate(message.ice))
                .catch(e => this._onError(e));
        }
    }

    _onChannelOpened(event) {
        console.log('RTC: channel opened with', this._peerId);
        const channel = event.channel || event.target;
        channel.binaryType = 'arraybuffer';
        channel.onmessage = e => this._onMessage(e.data);
        channel.onclose = _ => this._onChannelClosed();
        this._channel = channel;
        Events.on('beforeunload', e => this._onBeforeUnload(e));
        Events.on('pagehide', _ => this._onPageHide());
        Events.fire('peer-connected', {peerId: this._peerId, connectionHash: this.getConnectionHash()});
    }

    _onMessage(message) {
        if (typeof message !== 'string') {
            // File data chunk: must be end-to-end encrypted
            if (!this._encryptionKey) {
                console.warn('Blocked: received file chunk but peers not paired, dropping');
                return;
            }
            message = this._decrypt(message);
            super._onMessage(message);
            return;
        }
        console.log('RTC:', JSON.parse(message));
        super._onMessage(message);
    }

    getConnectionHash() {
        const localDescriptionLines = this._conn.localDescription.sdp.split("\r\n");
        const remoteDescriptionLines = this._conn.remoteDescription.sdp.split("\r\n");
        let localConnectionFingerprint, remoteConnectionFingerprint;
        for (let i=0; i<localDescriptionLines.length; i++) {
            if (localDescriptionLines[i].startsWith("a=fingerprint:")) {
                localConnectionFingerprint = localDescriptionLines[i].substring(14);
                break;
            }
        }
        for (let i=0; i<remoteDescriptionLines.length; i++) {
            if (remoteDescriptionLines[i].startsWith("a=fingerprint:")) {
                remoteConnectionFingerprint = remoteDescriptionLines[i].substring(14);
                break;
            }
        }
        const combinedFingerprints = this._isCaller
            ? localConnectionFingerprint + remoteConnectionFingerprint
            : remoteConnectionFingerprint + localConnectionFingerprint;
        let hash = cyrb53(combinedFingerprints).toString();
        while (hash.length < 16) {
            hash = "0" + hash;
        }
        return hash;
    }

    _onBeforeUnload(e) {
        if (this._busy) {
            e.preventDefault();
            return Localization.getTranslation("notifications.unfinished-transfers-warning");
        }
    }

    _onPageHide() {
        this._disconnect();
    }

    _disconnect() {
        if (this._conn && this._channel) {
            this._channel.onclose = null;
            this._channel.close();
        }
        Events.fire('peer-disconnected', this._peerId);
    }

    _onChannelClosed() {
        console.log('RTC: channel closed', this._peerId);
        Events.fire('peer-disconnected', this._peerId);
        if (!this._isCaller) return;
        this._connect(); // reopen the channel
    }

    _onConnectionStateChange() {
        console.log('RTC: state changed:', this._conn.connectionState);
        switch (this._conn.connectionState) {
            case 'disconnected':
                Events.fire('peer-disconnected', this._peerId);
                this._onError('rtc connection disconnected');
                break;
            case 'failed':
                Events.fire('peer-disconnected', this._peerId);
                this._onError('rtc connection failed');
                break;
        }
    }

    _onIceConnectionStateChange() {
        switch (this._conn.iceConnectionState) {
            case 'failed':
                this._onError('ICE Gathering failed');
                break;
            default:
                console.log('ICE Gathering', this._conn.iceConnectionState);
        }
    }

    _onError(error) {
        console.error(error);
    }

    _send(message) {
        if (!this._channel) this.refresh();
        if (message instanceof ArrayBuffer) {
            // File data chunk: require E2E and encrypt before sending
            if (!this._requireEncryption()) return;
            message = this._encrypt(message);
        }
        this._channel.send(message);
    }

    _sendSignal(signal) {
        signal.type = 'signal';
        signal.to = this._peerId;
        signal.roomType = this._getRoomTypes()[0];
        signal.roomId = this._roomIds[this._getRoomTypes()[0]];
        this._server.send(signal);
    }

    refresh() {
        // check if channel is open. otherwise create one
        if (this._isConnected() || this._isConnecting()) return;

        // only reconnect if peer is caller
        if (!this._isCaller) return;

        this._connect();
    }

    _isConnected() {
        return this._channel && this._channel.readyState === 'open';
    }

    _isConnecting() {
        return this._channel && this._channel.readyState === 'connecting';
    }

    sendDisplayName(displayName) {
        if (!this._isConnected()) return;
        super.sendDisplayName(displayName);
    }
}

class WSPeer extends Peer {

    constructor(serverConnection, isCaller, peerId, roomType, roomId) {
        super(serverConnection, isCaller, peerId, roomType, roomId);

        this.rtcSupported = false;

        if (!this._isCaller) return;
        this._sendSignal();
    }

    _sendSignal(connected = false) {
        this.sendJSON({ type: 'signal', connected });
    }

    onServerMessage(message) {
        this._peerId = message.sender.id;
        Events.fire('peer-connected', {
            peerId: message.sender.id,
            connectionHash: this.getConnectionHash()
        });
        if (message.connected) return;
        this._sendSignal(true);
    }

    _send(chunk) {
        if (!this._requireEncryption()) return;
        chunk = this._encrypt(chunk);
        // Send the encrypted bytes as a raw BINARY WebSocket frame instead of
        // base64-in-JSON. This removes the 33% base64 overhead AND the
        // JS atob/btoa cost that capped transfers at ~5 MB/s. The server is
        // told the recipient first (ws-chunk-prep) so it can route the binary
        // frame, and forwards a matching prep to the receiver.
        this.sendJSON({ type: 'ws-chunk-prep', to: this._peerId });
        this._server.sendBinary(chunk);
    }

    sendJSON(message) {
        message.to = this._peerId;
        message.roomType = this._getRoomTypes()[0];
        message.roomId = this._roomIds[this._getRoomTypes()[0]];
        this._server.send(message);
    }

    getConnectionHash() {
        return "";
    }
}

class PeersManager {

    constructor(serverConnection) {
        this.peers = {};
        this._server = serverConnection;
        Events.on('signal', e => this._onMessage(e.detail));
        Events.on('ws-binary', e => this._onWsBinary(e.detail));
        Events.on('peers', e => this._onPeers(e.detail));
        Events.on('files-selected', e => this._onFilesSelected(e.detail));
        Events.on('respond-to-files-transfer-request', e => this._onRespondToFileTransferRequest(e.detail))
        Events.on('send-text', e => this._onSendText(e.detail));
        Events.on('peer-left', e => this._onPeerLeft(e.detail));
        Events.on('peer-joined', e => this._onPeerJoined(e.detail));
        Events.on('peer-connected', e => this._onPeerConnected(e.detail.peerId));
        Events.on('peer-disconnected', e => this._onPeerDisconnected(e.detail));

        // this device closes connection
        Events.on('room-secrets-deleted', e => this._onRoomSecretsDeleted(e.detail));
        Events.on('leave-public-room', e => this._onLeavePublicRoom(e.detail));

        // peer closes connection
        Events.on('secret-room-deleted', e => this._onSecretRoomDeleted(e.detail));

        Events.on('room-secret-regenerated', e => this._onRoomSecretRegenerated(e.detail));
        Events.on('display-name', e => this._onDisplayName(e.detail.displayName));
        Events.on('self-display-name-changed', e => this._notifyPeersDisplayNameChanged(e.detail));
        Events.on('notify-peer-display-name-changed', e => this._notifyPeerDisplayNameChanged(e.detail));
        Events.on('auto-accept-updated', e => this._onAutoAcceptUpdated(e.detail.roomSecret, e.detail.autoAccept));
        Events.on('ws-disconnected', _ => this._onWsDisconnected());
        Events.on('ws-relay', e => this._onWsRelay(e.detail));
        Events.on('ws-config', e => this._onWsConfig(e.detail));
    }

    _onWsConfig(wsConfig) {
        this._wsConfig = wsConfig;
    }

    _onMessage(message) {
        const peerId = message.sender.id;
        this.peers[peerId].onServerMessage(message);
    }

    _refreshPeer(peerId, roomType, roomId) {
        if (!this._peerExists(peerId)) return false;

        const peer = this.peers[peerId];
        const roomTypesDiffer = Object.keys(peer._roomIds)[0] !== roomType;
        const roomIdsDiffer = peer._roomIds[roomType] !== roomId;

        // if roomType or roomId for roomType differs peer is already connected
        // -> only update roomSecret and reevaluate auto accept
        if (roomTypesDiffer || roomIdsDiffer) {
            peer._updateRoomIds(roomType, roomId);
            peer._evaluateAutoAccept();

            return true;
        }

        peer.refresh();

        return true;
    }

    _createOrRefreshPeer(isCaller, peerId, roomType, roomId, rtcSupported, peerLanIp) {
        if (this._peerExists(peerId)) {
            this._refreshPeer(peerId, roomType, roomId);
            return;
        }

        if (this._wsConfig.wsFallback) {
            // Use WebSocket relay (data goes through server, stays on LAN)
            this.peers[peerId] = new WSPeer(this._server, isCaller, peerId, roomType, roomId);
        }
        else if (window.isRtcSupported && rtcSupported) {
            this.peers[peerId] = new RTCPeer(this._server, isCaller, peerId, roomType, roomId, this._wsConfig.rtcConfig, peerLanIp);
        }
        else {
            console.warn("Websocket fallback is not activated on this instance.\n" +
                "Activate WebRTC in this browser or ask the admin of this instance to activate the websocket fallback.")
        }
    }

    _onPeerJoined(message) {
        this._createOrRefreshPeer(false, message.peer.id, message.roomType, message.roomId, message.peer.rtcSupported, message.peer.lanIp);
    }

    _onPeers(message) {
        message.peers.forEach(peer => {
            this._createOrRefreshPeer(true, peer.id, message.roomType, message.roomId, peer.rtcSupported, peer.lanIp);
        })
    }

    _onWsRelay(message) {
        if (!this._wsConfig.wsFallback) return;

        const messageJSON = JSON.parse(message);
        const peer = this.peers[messageJSON.sender.id];
        if (!peer) return;

        if (messageJSON.type === 'ws-chunk') {
            // Legacy text relay (still supported, but the app now sends binary)
            if (!peer._encryptionKey) {
                console.warn('Blocked: received ws-chunk but peers not paired, dropping');
                return;
            }
            let data = base64ToArrayBuffer(messageJSON.chunk);
            if (peer._encryptionKey) {
                data = peer._decrypt(data);
            }
            peer._onMessage(data);
        } else {
            peer._onMessage(message);
        }
    }

    /** A raw binary file chunk arrived over the WebSocket (fast path). */
    _onWsBinary({from, data}) {
        const peer = this.peers[from];
        if (!peer) return;
        if (!peer._encryptionKey) {
            console.warn('Blocked: received binary chunk but peers not paired, dropping');
            return;
        }
        const decrypted = peer._decrypt(data);
        peer._onMessage(decrypted);
    }

    _onRespondToFileTransferRequest(detail) {
        this.peers[detail.to]._respondToFileTransferRequest(detail.accepted);
    }

    async _onFilesSelected(message) {
        let files = mime.addMissingMimeTypesToFiles([...message.files]);
        await this.peers[message.to].requestFileTransfer(files);
    }

    _onSendText(message) {
        this.peers[message.to].sendText(message.text);
    }

    _onPeerLeft(message) {
        if (this._peerExists(message.peerId) && this._webRtcSupported(message.peerId)) {
            console.log('WSPeer left:', message.peerId);
        }
        if (message.disconnect === true) {
            // if user actively disconnected from lan-projects server, disconnect all peer to peer connections immediately
            this._disconnectOrRemoveRoomTypeByPeerId(message.peerId, message.roomType);

            // If no peers are connected anymore, we can safely assume that no other tab on the same browser is connected:
            // Tidy up peerIds in localStorage
            if (Object.keys(this.peers).length === 0) {
                BrowserTabsConnector
                    .removeOtherPeerIdsFromLocalStorage()
                    .then(peerIds => {
                        if (!peerIds) return;
                        console.log("successfully removed other peerIds from localStorage");
                    });
            }
        }
    }

    _onPeerConnected(peerId) {
        this._notifyPeerDisplayNameChanged(peerId);
        // Show E2E encryption badge if encryption is established on this peer
        const peer = this.peers[peerId];
        if (peer && peer._keyExchangeDone) {
            Events.fire('e2e-encryption-active');
        }
    }

    _peerExists(peerId) {
        return !!this.peers[peerId];
    }

    _webRtcSupported(peerId) {
        return this.peers[peerId].rtcSupported
    }

    _onWsDisconnected() {
        if (!this._wsConfig || !this._wsConfig.wsFallback) return;

        for (const peerId in this.peers) {
            if (!this._webRtcSupported(peerId)) {
                Events.fire('peer-disconnected', peerId);
            }
        }
    }

    _onPeerDisconnected(peerId) {
        const peer = this.peers[peerId];
        delete this.peers[peerId];
        if (!peer || !peer._conn) return;
        if (peer._channel) peer._channel.onclose = null;
        peer._conn.close();
        peer._busy = false;
        peer._roomIds = {};
    }

    _onRoomSecretsDeleted(roomSecrets) {
        for (let i=0; i<roomSecrets.length; i++) {
            this._disconnectOrRemoveRoomTypeByRoomId('secret', roomSecrets[i]);
        }
    }

    _onLeavePublicRoom(publicRoomId) {
        this._disconnectOrRemoveRoomTypeByRoomId('public-id', publicRoomId);
    }

    _onSecretRoomDeleted(roomSecret) {
        this._disconnectOrRemoveRoomTypeByRoomId('secret', roomSecret);
    }

    _disconnectOrRemoveRoomTypeByRoomId(roomType, roomId) {
        const peerIds = this._getPeerIdsFromRoomId(roomId);

        if (!peerIds.length) return;

        for (let i=0; i<peerIds.length; i++) {
            this._disconnectOrRemoveRoomTypeByPeerId(peerIds[i], roomType);
        }
    }

    _disconnectOrRemoveRoomTypeByPeerId(peerId, roomType) {
        const peer = this.peers[peerId];

        if (!peer) return;

        if (peer._getRoomTypes().length > 1) {
            peer._removeRoomType(roomType);
        }
        else {
            Events.fire('peer-disconnected', peerId);
        }
    }

    _onRoomSecretRegenerated(message) {
        PersistentStorage
            .updateRoomSecret(message.oldRoomSecret, message.newRoomSecret)
            .then(_ => {
                console.log("successfully regenerated room secret");
                Events.fire("room-secrets", [message.newRoomSecret]);
            })
    }

    _notifyPeersDisplayNameChanged(newDisplayName) {
        this._displayName = newDisplayName ? newDisplayName : this._originalDisplayName;
        for (const peerId in this.peers) {
            this._notifyPeerDisplayNameChanged(peerId);
        }
    }

    _notifyPeerDisplayNameChanged(peerId) {
        const peer = this.peers[peerId];
        if (!peer) return;
        this.peers[peerId].sendDisplayName(this._displayName);
    }

    _onDisplayName(displayName) {
        this._originalDisplayName = displayName;
        // if the displayName has not been changed (yet) set the displayName to the original displayName
        if (!this._displayName) this._displayName = displayName;
    }

    _onAutoAcceptUpdated(roomSecret, autoAccept) {
        const peerId = this._getPeerIdsFromRoomId(roomSecret)[0];

        if (!peerId) return;

        this.peers[peerId]._setAutoAccept(autoAccept);
    }

    _getPeerIdsFromRoomId(roomId) {
        if (!roomId) return [];

        let peerIds = []
        for (const peerId in this.peers) {
            const peer = this.peers[peerId];

            // peer must have same roomId.
            if (Object.values(peer._roomIds).includes(roomId)) {
                peerIds.push(peer._peerId);
            }
        }
        return peerIds;
    }
}

class FileChunker {

    constructor(file, onChunk, onPartitionEnd) {
        // 1 MB chunks, sent as raw binary WebSocket frames (no base64).
        // Measured: larger chunks (4 MB) were SLOWER on real devices (bigger
        // buffers / bursty frames hurt more than per-chunk overhead saves), so
        // 1 MB stays the sweet spot.
        this._chunkSize = 1048576; // 1 MB
        this._maxPartitionSize = 10e6; // 10 MB - fewer control messages
        this._offset = 0;
        this._partitionSize = 0;
        this._file = file;
        this._onChunk = onChunk;
        this._onPartitionEnd = onPartitionEnd;
        this._reader = new FileReader();
        this._reader.addEventListener('load', e => this._onChunkRead(e.target.result));
    }

    nextPartition() {
        this._partitionSize = 0;
        this._readChunk();
    }

    _readChunk() {
        const chunk = this._file.slice(this._offset, this._offset + this._chunkSize);
        this._reader.readAsArrayBuffer(chunk);
    }

    _onChunkRead(chunk) {
        this._offset += chunk.byteLength;
        this._partitionSize += chunk.byteLength;
        this._onChunk(chunk);
        if (this.isFileEnd()) return;
        if (this._isPartitionEnd()) {
            this._onPartitionEnd(this._offset);
            return;
        }
        this._readChunk();
    }

    repeatPartition() {
        this._offset -= this._partitionSize;
        this.nextPartition();
    }

    _isPartitionEnd() {
        return this._partitionSize >= this._maxPartitionSize;
    }

    isFileEnd() {
        return this._offset >= this._file.size;
    }
}

class FileDigester {

    constructor(meta, totalSize, totalBytesReceived, callback) {
        this._buffer = [];
        this._bytesReceived = 0;
        this._size = meta.size;
        this._name = meta.name;
        this._mime = meta.mime;
        this._totalSize = totalSize;
        this._totalBytesReceived = totalBytesReceived;
        this._callback = callback;
    }

    unchunk(chunk) {
        this._buffer.push(chunk);
        this._bytesReceived += chunk.byteLength || chunk.size;
        this.progress = (this._totalBytesReceived + this._bytesReceived) / this._totalSize;
        if (isNaN(this.progress)) this.progress = 1

        if (this._bytesReceived < this._size) return;
        // we are done
        const blob = new Blob(this._buffer)
        this._buffer = null;
        this._callback(new File([blob], this._name, {
            type: this._mime || "application/octet-stream",
            lastModified: new Date().getTime()
        }));
    }
}
