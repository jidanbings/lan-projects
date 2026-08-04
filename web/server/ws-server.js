import {WebSocketServer} from "ws";
import crypto from "crypto"
import fs from "fs";

import Peer from "./peer.js";
import {hasher, randomizer} from "./helper.js";

// Append a line to the same server.log the Android app's 查看日志 screen reads
// (HOME is set to the app's files dir by NodeService). Used to diagnose the
// "很多设备" bug: whether a fresh server really has an empty room, and whether
// the same device connects more than once.
// Keep only the newest MAX_LOG_LINES lines, matching the Java-side cap in
// ServerLog.java (the two processes share server.log). Append first so a
// message is never lost before the trim runs.
const MAX_LOG_LINES = 100;
function dlog(msg) {
    try {
        if (!process.env.HOME) return;
        const file = process.env.HOME + '/server.log';
        fs.appendFileSync(file,
            new Date().toISOString().replace('T', ' ').slice(0, 19) + '  [node]  ' + msg + '\n');
        const text = fs.readFileSync(file, 'utf8').replace(/\n$/, '');
        const lines = text.split('\n');
        if (lines.length > MAX_LOG_LINES) {
            fs.writeFileSync(file, lines.slice(lines.length - MAX_LOG_LINES).join('\n') + '\n');
        }
    } catch (e) {
        // logging must never break the server
    }
}

export default class LanProjectsWsServer {

    constructor(server, conf) {
        this._conf = conf

        this._rooms = {}; // { roomId: peers[] }

        this._roomSecrets = {}; // { pairKey: roomSecret }
        this._keepAliveTimers = {};

        // Fast lookup for routing binary file chunks: peerId -> peer, and
        // senderId -> recipientId announced by 'ws-chunk-prep' before the raw
        // binary frame arrives (binary frames carry no routing info).
        this._peersById = {};
        this._pendingBinaryTo = {};

        this._wss = new WebSocketServer({ server });
        this._wss.on('connection', (socket, request) => this._onConnection(new Peer(socket, request, conf)));
    }

    _onConnection(peer) {
        dlog('CONNECT peer=' + peer.id + ' ip=' + peer.ip
            + ' (server process pid=' + process.pid + ')');
        this._peersById[peer.id] = peer;
        peer.socket.on('message', (message, isBinary) => {
            if (isBinary) {
                this._onBinaryChunk(peer, message);
            } else {
                this._onMessage(peer, message.toString());
            }
        });
        peer.socket.onerror = e => console.error(e);

        // Remove the peer the moment its socket closes, even if no 'disconnect'
        // message arrives (e.g. the app was killed or the WebView teardown never
        // fired pagehide). Without this a closed peer stayed in the room until
        // the keep-alive timed out, so reconnecting from the same device kept
        // adding a fresh entry and the room filled up with stale devices.
        // The close code tells us WHY the socket went away: 1000 = clean close
        // (the page/browser initiated it), 1006 = abnormal (network dropped or
        // the process was killed — no close frame), 1001 = page navigated away.
        peer.socket.on('close', (code, reason) => {
            dlog('SOCKET-CLOSE peer=' + peer.id + ' code=' + code
                + ' reason=' + reason);
            this._disconnect(peer);
        });

        // Protocol-level pong: a browser/ws client answers a WebSocket ping
        // frame in its network stack WITHOUT running any page JS, so an idle or
        // backgrounded page still keeps its connection alive (see _keepAlive).
        peer.socket.on('pong', () => this._setKeepAliveTimerToNow(peer));

        this._keepAlive(peer);

        this._send(peer, {
            type: 'ws-config',
            wsConfig: {
                wsFallback: this._conf.wsFallback
            }
        });

        // send displayName
        this._send(peer, {
            type: 'display-name',
            displayName: peer.name.displayName,
            deviceName: peer.name.deviceName,
            peerId: peer.id,
            peerIdHash: hasher.hashCodeSalted(peer.id)
        });
    }

    _onMessage(sender, message) {
        // Try to parse message
        try {
            message = JSON.parse(message);
        } catch (e) {
            console.warn("WS: Received JSON is malformed");
            return;
        }

        switch (message.type) {
            case 'disconnect':
                this._onDisconnect(sender);
                break;
            case 'pong':
                this._setKeepAliveTimerToNow(sender);
                break;
            case 'ping':
                // Client heartbeat: the page pings us so IT can detect a dead
                // server even when the TCP close never arrives (half-open
                // hotspot link after this phone exits / is killed). Reply
                // immediately; also count the ping as liveness so our own
                // keep-alive budget never reaps a healthy-but-idle peer.
                this._setKeepAliveTimerToNow(sender);
                this._send(sender, { type: 'pong' });
                break;
            case 'visibility':
                // Frontend reports its page hidden/visible (screen off, app
                // switcher, system file picker). Lets the log show whether a
                // peer's disconnect coincided with that page going hidden, so we
                // can tell "backgrounded WebView closed the socket" apart from a
                // genuine network drop.
                dlog('VISIBILITY peer=' + sender.id + ' hidden=' + message.hidden);
                break;
            case 'join-ip-room':
                this._joinIpRoom(sender);
                break;
            case 'room-secrets':
                this._onRoomSecrets(sender, message);
                break;
            case 'room-secrets-deleted':
                this._onRoomSecretsDeleted(sender, message);
                break;
            case 'pair-device-initiate':
                this._onPairDeviceInitiate(sender);
                break;
            case 'pair-device-join':
                this._onPairDeviceJoin(sender, message);
                break;
            case 'pair-device-cancel':
                this._onPairDeviceCancel(sender);
                break;
            case 'regenerate-room-secret':
                this._onRegenerateRoomSecret(sender, message);
                break;
            case 'create-public-room':
                this._onCreatePublicRoom(sender);
                break;
            case 'join-public-room':
                this._onJoinPublicRoom(sender, message);
                break;
            case 'leave-public-room':
                this._onLeavePublicRoom(sender);
                break;
            case 'signal':
                this._signalAndRelay(sender, message);
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
            case 'ws-chunk':
                // relay ws-fallback (legacy text relay, kept for compatibility)
                if (this._conf.wsFallback) {
                    this._signalAndRelay(sender, message);
                }
                else {
                    console.log("Websocket fallback is not activated on this instance.")
                }
                break;
            case 'ws-chunk-prep':
                // Sender announced the recipient of its next binary file chunk.
                this._onWsChunkPrep(sender, message);
                break;
        }
    }

    _signalAndRelay(sender, message) {
        const room = message.roomType === 'ip'
            ? this._ipRoomId()
            : message.roomId;

        // relay message to recipient
        if (message.to && Peer.isValidUuid(message.to) && this._rooms[room]) {
            const recipient = this._rooms[room][message.to];
            delete message.to;
            // add sender
            message.sender = {
                id: sender.id,
                rtcSupported: sender.rtcSupported
            };
            this._send(recipient, message);
        }
    }

    /**
     * Sender announces the recipient of its next BINARY file chunk. Remember
     * the route and tell the receiver which peer the upcoming frame is from
     * (binary frames carry no sender id).
     */
    _onWsChunkPrep(sender, message) {
        if (!message.to || !Peer.isValidUuid(message.to)) return;
        this._pendingBinaryTo[sender.id] = message.to;
        const recipient = this._peersById[message.to];
        if (recipient && recipient.socket.readyState === recipient.socket.OPEN) {
            this._send(recipient, { type: 'ws-chunk-prep', from: sender.id });
        }
    }

    /** Forward a raw binary file chunk to its announced recipient. */
    _onBinaryChunk(sender, data) {
        const to = this._pendingBinaryTo[sender.id];
        delete this._pendingBinaryTo[sender.id];
        if (!to) return;
        const recipient = this._peersById[to];
        if (recipient && recipient.socket.readyState === recipient.socket.OPEN) {
            recipient.socket.send(data, { binary: true });
        }
    }

    _onDisconnect(sender) {
        this._disconnect(sender);
    }

    _disconnect(sender) {
        dlog('DISCONNECT peer=' + sender.id + ' ip=' + sender.ip
            + ' remainingIpRoom=' + (this._rooms[this._ipRoomId()] ? Object.keys(this._rooms[this._ipRoomId()]).length : 0));

        this._removePairKey(sender.pairKey);
        sender.pairKey = null;

        this._cancelKeepAlive(sender);
        delete this._keepAliveTimers[sender.id];

        this._leaveIpRoom(sender, true);
        this._leaveAllSecretRooms(sender, true);
        this._leavePublicRoom(sender, true);

        delete this._peersById[sender.id];
        delete this._pendingBinaryTo[sender.id];

        sender.socket.terminate();
    }

    _onRoomSecrets(sender, message) {
        if (!message.roomSecrets) return;

        const roomSecrets = message.roomSecrets.filter(roomSecret => {
            return /^[\x00-\x7F]{64,256}$/.test(roomSecret);
        })

        if (!roomSecrets) return;

        this._joinSecretRooms(sender, roomSecrets);
    }

    _onRoomSecretsDeleted(sender, message) {
        if (!message.roomSecrets) return;
        for (let i = 0; i<message.roomSecrets.length; i++) {
            this._deleteSecretRoom(message.roomSecrets[i]);
        }
    }

    _deleteSecretRoom(roomSecret) {
        const room = this._rooms[roomSecret];
        if (!room) return;

        for (const peerId in room) {
            const peer = room[peerId];

            this._leaveSecretRoom(peer, roomSecret, true);

            this._send(peer, {
                type: 'secret-room-deleted',
                roomSecret: roomSecret,
            });
        }
    }

    _onPairDeviceInitiate(sender) {
        let roomSecret = randomizer.getRandomString(256);
        let pairKey = this._createPairKey(sender, roomSecret);

        if (sender.pairKey) {
            this._removePairKey(sender.pairKey);
        }
        sender.pairKey = pairKey;

        this._send(sender, {
            type: 'pair-device-initiated',
            roomSecret: roomSecret,
            pairKey: pairKey
        });
        this._joinSecretRoom(sender, roomSecret);
    }

    _onPairDeviceJoin(sender, message) {
        if (sender.rateLimitReached()) {
            this._send(sender, { type: 'join-key-rate-limit' });
            return;
        }

        if (!this._roomSecrets[message.pairKey] || sender.id === this._roomSecrets[message.pairKey].creator.id) {
            this._send(sender, { type: 'pair-device-join-key-invalid' });
            return;
        }

        const roomSecret = this._roomSecrets[message.pairKey].roomSecret;
        const creator = this._roomSecrets[message.pairKey].creator;
        this._removePairKey(message.pairKey);
        this._send(sender, {
            type: 'pair-device-joined',
            roomSecret: roomSecret,
            peerId: creator.id
        });
        this._send(creator, {
            type: 'pair-device-joined',
            roomSecret: roomSecret,
            peerId: sender.id
        });
        this._joinSecretRoom(sender, roomSecret);
        this._removePairKey(sender.pairKey);
    }

    _onPairDeviceCancel(sender) {
        const pairKey = sender.pairKey

        if (!pairKey) return;

        this._removePairKey(pairKey);
        this._send(sender, {
            type: 'pair-device-canceled',
            pairKey: pairKey,
        });
    }

    _onCreatePublicRoom(sender) {
        let publicRoomId = randomizer.getRandomString(5, true).toLowerCase();

        this._send(sender, {
            type: 'public-room-created',
            roomId: publicRoomId
        });

        this._joinPublicRoom(sender, publicRoomId);
    }

    _onJoinPublicRoom(sender, message) {
        if (sender.rateLimitReached()) {
            this._send(sender, { type: 'join-key-rate-limit' });
            return;
        }

        if (!this._rooms[message.publicRoomId] && !message.createIfInvalid) {
            this._send(sender, { type: 'public-room-id-invalid', publicRoomId: message.publicRoomId });
            return;
        }

        this._leavePublicRoom(sender);
        this._joinPublicRoom(sender, message.publicRoomId);
    }

    _onLeavePublicRoom(sender) {
        this._leavePublicRoom(sender, true);
        this._send(sender, { type: 'public-room-left' });
    }

    _onRegenerateRoomSecret(sender, message) {
        const oldRoomSecret = message.roomSecret;
        const newRoomSecret = randomizer.getRandomString(256);

        // notify all other peers
        for (const peerId in this._rooms[oldRoomSecret]) {
            const peer = this._rooms[oldRoomSecret][peerId];
            this._send(peer, {
                type: 'room-secret-regenerated',
                oldRoomSecret: oldRoomSecret,
                newRoomSecret: newRoomSecret,
            });
            peer.removeRoomSecret(oldRoomSecret);
        }
        delete this._rooms[oldRoomSecret];
    }

    _createPairKey(creator, roomSecret) {
        let pairKey;
        do {
            // get randomInt until keyRoom not occupied
            pairKey = crypto.randomInt(1000000, 1999999).toString().substring(1); // include numbers with leading 0s
        } while (pairKey in this._roomSecrets)

        this._roomSecrets[pairKey] = {
            roomSecret: roomSecret,
            creator: creator
        }

        return pairKey;
    }

    _removePairKey(pairKey) {
        if (pairKey in this._roomSecrets) {
            this._roomSecrets[pairKey].creator.pairKey = null
            delete this._roomSecrets[pairKey];
        }
    }

    /**
     * Shared IP-room id: the server's LAN address. Every peer that connects to
     * this server (the host's own WebView over loopback, or LAN clients from
     * their own IPs) joins the SAME room, so they discover each other and
     * derive the same encryption key. Using each peer's source IP instead made
     * the host's room 127.0.0.1 and a client's room its own IP -> keys never
     * matched and host<-client transfers could not be received/saved.
     */
    _ipRoomId() {
        return this._conf.lanIp || '127.0.0.1';
    }

    _joinIpRoom(peer) {
        this._joinRoom(peer, 'ip', this._ipRoomId());
    }

    _joinSecretRoom(peer, roomSecret) {
        this._joinRoom(peer, 'secret', roomSecret);

        // add secret to peer
        peer.addRoomSecret(roomSecret);
    }

    _joinPublicRoom(peer, publicRoomId) {
        // prevent joining of 2 public rooms simultaneously
        this._leavePublicRoom(peer);

        this._joinRoom(peer, 'public-id', publicRoomId);

        peer.publicRoomId = publicRoomId;
    }

    _joinRoom(peer, roomType, roomId) {
        // roomType: 'ip', 'secret' or 'public-id'
        if (this._rooms[roomId] && this._rooms[roomId][peer.id]) {
            // ensures that otherPeers never receive `peer-left` after `peer-joined` on reconnect.
            this._leaveRoom(peer, roomType, roomId);
        }

        // if room doesn't exist, create it
        if (!this._rooms[roomId]) {
            this._rooms[roomId] = {};
        }

        this._notifyPeers(peer, roomType, roomId);

        // add peer to room
        this._rooms[roomId][peer.id] = peer;

        dlog('JOIN peer=' + peer.id + ' room=' + roomType + ':' + roomId
            + ' totalPeersInRoom=' + Object.keys(this._rooms[roomId]).length);
    }


    _leaveIpRoom(peer, disconnect = false) {
        this._leaveRoom(peer, 'ip', this._ipRoomId(), disconnect);
    }

    _leaveSecretRoom(peer, roomSecret, disconnect = false) {
        this._leaveRoom(peer, 'secret', roomSecret, disconnect)

        //remove secret from peer
        peer.removeRoomSecret(roomSecret);
    }

    _leavePublicRoom(peer, disconnect = false) {
        if (!peer.publicRoomId) return;

        this._leaveRoom(peer, 'public-id', peer.publicRoomId, disconnect);

        peer.publicRoomId = null;
    }

    _leaveRoom(peer, roomType, roomId, disconnect = false) {
        if (!this._rooms[roomId] || !this._rooms[roomId][peer.id]) return;

        // remove peer from room
        delete this._rooms[roomId][peer.id];

        // delete room if empty and abort
        if (!Object.keys(this._rooms[roomId]).length) {
            delete this._rooms[roomId];
            return;
        }

        // notify all other peers that remain in room that peer left
        for (const otherPeerId in this._rooms[roomId]) {
            const otherPeer = this._rooms[roomId][otherPeerId];

            let msg = {
                type: 'peer-left',
                peerId: peer.id,
                roomType: roomType,
                roomId: roomId,
                disconnect: disconnect
            };

            this._send(otherPeer, msg);
        }
    }

    _notifyPeers(peer, roomType, roomId) {
        if (!this._rooms[roomId]) return;

        // notify all other peers that peer joined
        for (const otherPeerId in this._rooms[roomId]) {
            if (otherPeerId === peer.id) continue;
            const otherPeer = this._rooms[roomId][otherPeerId];

            let msg = {
                type: 'peer-joined',
                peer: peer.getInfo(),
                roomType: roomType,
                roomId: roomId
            };

            this._send(otherPeer, msg);
        }

        // notify peer about peers already in the room
        const otherPeers = [];
        for (const otherPeerId in this._rooms[roomId]) {
            if (otherPeerId === peer.id) continue;
            otherPeers.push(this._rooms[roomId][otherPeerId].getInfo());
        }

        let msg = {
            type: 'peers',
            peers: otherPeers,
            roomType: roomType,
            roomId: roomId
        };

        this._send(peer, msg);
    }

    _joinSecretRooms(peer, roomSecrets) {
        for (let i=0; i<roomSecrets.length; i++) {
            this._joinSecretRoom(peer, roomSecrets[i])
        }
    }

    _leaveAllSecretRooms(peer, disconnect = false) {
        // Iterate over a snapshot: _leaveSecretRoom splices the live array via
        // peer.removeRoomSecret(), which would otherwise shift elements and
        // cause every other secret room to be skipped (peers in those rooms
        // never received `peer-left` on disconnect).
        const roomSecrets = [...peer.roomSecrets];
        for (let i=0; i<roomSecrets.length; i++) {
            this._leaveSecretRoom(peer, roomSecrets[i], disconnect);
        }
    }

    _send(peer, message) {
        if (!peer) return;
        // this._wss is a WebSocketServer, which has no readyState/OPEN (those
        // live on WebSocket). Both were undefined, so the old guard
        // `undefined !== undefined` was always false and never short-circuited.
        // Guard against the recipient's socket being closed instead.
        if (peer.socket.readyState !== peer.socket.OPEN) return;
        message = JSON.stringify(message);
        peer.socket.send(message);
    }

    _keepAlive(peer) {
        this._cancelKeepAlive(peer);
        let timeout = 1000;

        if (!this._keepAliveTimers[peer.id]) {
            this._keepAliveTimers[peer.id] = {
                timer: 0,
                lastBeat: Date.now()
            };
        }

        // Reap a peer only after ~10 minutes of silence. Keepalive is now a
        // PROTOCOL-LEVEL ping (socket.ping() -> the browser auto-answers with a
        // pong in its network stack, no page JS involved), and the budget is
        // deliberately generous so a healthy-but-idle connection survives even
        // when the whole WebView is frozen - e.g. the host phone hidden behind
        // the system file picker while the user browses files for minutes. A
        // genuinely dead device (app killed, off WiFi) is still reaped here;
        // sockets that actually closed are already removed instantly by the
        // 'close' handler, so this slow budget costs nothing.
        if (Date.now() - this._keepAliveTimers[peer.id].lastBeat > 600 * timeout) {
            this._disconnect(peer);
            return;
        }

        if (peer.socket.readyState === peer.socket.OPEN) {
            peer.socket.ping();
        }

        this._keepAliveTimers[peer.id].timer = setTimeout(() => this._keepAlive(peer), timeout);
    }

    _cancelKeepAlive(peer) {
        if (this._keepAliveTimers[peer.id]?.timer) {
            clearTimeout(this._keepAliveTimers[peer.id].timer);
        }
    }

    _setKeepAliveTimerToNow(peer) {
        if (this._keepAliveTimers[peer.id]?.lastBeat) {
            this._keepAliveTimers[peer.id].lastBeat = Date.now();
        }
    }
}

