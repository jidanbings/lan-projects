class PersistentStorage {
    constructor() {
        if (!('indexedDB' in window)) {
            PersistentStorage.logBrowserNotCapable();
            return;
        }
        const DBOpenRequest = window.indexedDB.open('lan_projects_store', 5);
        DBOpenRequest.onerror = e => {
            PersistentStorage.logBrowserNotCapable();
            console.log('Error initializing database: ');
            console.log(e)
        };
        DBOpenRequest.onsuccess = _ => {
            console.log('Database initialised.');
        };
        DBOpenRequest.onupgradeneeded = async e => {
            const db = e.target.result;
            const txn = e.target.transaction;

            db.onerror = e => console.log('Error loading database: ' + e);

            console.log(`Upgrading IndexedDB database from version ${e.oldVersion} to version ${e.newVersion}`);

            if (e.oldVersion === 0) {
                // initiate v1
                db.createObjectStore('keyval');
                let roomSecretsObjectStore1 = db.createObjectStore('room_secrets', {autoIncrement: true});
                roomSecretsObjectStore1.createIndex('secret', 'secret', { unique: true });
            }
            if (e.oldVersion <= 1) {
                // migrate to v2
                db.createObjectStore('share_target_files');
            }
            if (e.oldVersion <= 2) {
                // migrate to v3
                db.deleteObjectStore('share_target_files');
                db.createObjectStore('share_target_files', {autoIncrement: true});
            }
            if (e.oldVersion <= 3) {
                // migrate to v4
                let roomSecretsObjectStore4 = txn.objectStore('room_secrets');
                roomSecretsObjectStore4.createIndex('display_name', 'display_name');
                roomSecretsObjectStore4.createIndex('auto_accept', 'auto_accept');
            }
            if (e.oldVersion <= 4) {
                // migrate to v5
                const editedDisplayNameOld = await PersistentStorage.get('editedDisplayName');
                if (editedDisplayNameOld) {
                    await PersistentStorage.set('edited_display_name', editedDisplayNameOld);
                    await PersistentStorage.delete('editedDisplayName');
                }
            }
        }
    }

    static logBrowserNotCapable() {
        console.log("This browser does not support IndexedDB. Paired devices will be gone after the browser is closed.");
    }

    static set(key, value) {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = e => {
                const db = e.target.result;
                const transaction = db.transaction('keyval', 'readwrite');
                const objectStore = transaction.objectStore('keyval');
                const objectStoreRequest = objectStore.put(value, key);
                objectStoreRequest.onsuccess = _ => {
                    console.log(`Request successful. Added key-pair: ${key} - ${value}`);
                    resolve(value);
                };
            }
            DBOpenRequest.onerror = e => {
                reject(e);
            }
        })
    }

    static get(key) {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = e => {
                const db = e.target.result;
                const transaction = db.transaction('keyval', 'readonly');
                const objectStore = transaction.objectStore('keyval');
                const objectStoreRequest = objectStore.get(key);
                objectStoreRequest.onsuccess = _ => {
                    console.log(`Request successful. Retrieved key-pair: ${key} - ${objectStoreRequest.result}`);
                    resolve(objectStoreRequest.result);
                }
            }
            DBOpenRequest.onerror = e => {
                reject(e);
            }
        });
    }

    static delete(key) {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = e => {
                const db = e.target.result;
                const transaction = db.transaction('keyval', 'readwrite');
                const objectStore = transaction.objectStore('keyval');
                const objectStoreRequest = objectStore.delete(key);
                objectStoreRequest.onsuccess = _ => {
                    console.log(`Request successful. Deleted key: ${key}`);
                    resolve();
                };
            }
            DBOpenRequest.onerror = e => {
                reject(e);
            }
        })
    }

    /**
     * @param roomSecret 房间路由 id R（64 位十六进制，由 S 派生）
     * @param pairSecret 配对密钥 S（26 字符 base32，只在本地存储，服务器见不到）
     * @param pairMethod 配对方式：'qr'（扫码）/ 'code'（手输配对码）/ 'click'（设备列表点选）
     */
    static addRoomSecret(roomSecret, pairSecret, displayName, deviceName, pairMethod = null) {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = e => {
                const db = e.target.result;
                const transaction = db.transaction('room_secrets', 'readwrite');
                const objectStore = transaction.objectStore('room_secrets');
                const objectStoreRequest = objectStore.add({
                    'secret': roomSecret,
                    'pair_secret': pairSecret,
                    'display_name': displayName,
                    'device_name': deviceName,
                    'auto_accept': false,
                    'alias': null,
                    'pair_method': pairMethod,
                    'last_connected': null
                });
                objectStoreRequest.onsuccess = e => {
                    console.log(`Request successful. RoomSecret added: ${e.target.result}`);
                    resolve();
                }
            }
            DBOpenRequest.onerror = e => {
                reject(e);
            }
        })
    }

    static async getAllRoomSecrets() {
        try {
            const roomSecrets = await this.getAllRoomSecretEntries();
            let secrets = [];
            for (let i = 0; i < roomSecrets.length; i++) {
                secrets.push(roomSecrets[i].secret);
            }
            console.log(`Request successful. Retrieved ${secrets.length} room_secrets`);
            return(secrets);
        } catch (e) {
            this.logBrowserNotCapable();
            return [];
        }
    }

    static getAllRoomSecretEntries() {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = (e) => {
                const db = e.target.result;
                const transaction = db.transaction('room_secrets', 'readonly');
                const objectStore = transaction.objectStore('room_secrets');
                const objectStoreRequest = objectStore.getAll();
                objectStoreRequest.onsuccess = e => {
                    resolve(e.target.result);
                }
            }
            DBOpenRequest.onerror = (e) => {
                reject(e);
            }
        });
    }

    static getRoomSecretEntry(roomSecret) {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = e => {
                const db = e.target.result;
                const transaction = db.transaction('room_secrets', 'readonly');
                const objectStore = transaction.objectStore('room_secrets');
                const objectStoreRequestKey = objectStore.index("secret").getKey(roomSecret);
                objectStoreRequestKey.onsuccess = e => {
                    const key = e.target.result;
                    if (!key) {
                        console.log(`Nothing to retrieve. Entry for room_secret not existing: ${roomSecret}`);
                        resolve();
                        return;
                    }
                    const objectStoreRequestRetrieval = objectStore.get(key);
                    objectStoreRequestRetrieval.onsuccess = e => {
                        console.log(`Request successful. Retrieved entry for room_secret: ${key}`);
                        resolve({
                            "entry": e.target.result,
                            "key": key
                        });
                    }
                    objectStoreRequestRetrieval.onerror = (e) => {
                        reject(e);
                    }
                };
            }
            DBOpenRequest.onerror = (e) => {
                reject(e);
            }
        });
    }

    static deleteRoomSecret(roomSecret) {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = (e) => {
                const db = e.target.result;
                const transaction = db.transaction('room_secrets', 'readwrite');
                const objectStore = transaction.objectStore('room_secrets');
                const objectStoreRequestKey = objectStore.index("secret").getKey(roomSecret);
                objectStoreRequestKey.onsuccess = e => {
                    if (!e.target.result) {
                        console.log(`Nothing to delete. room_secret not existing: ${roomSecret}`);
                        resolve();
                        return;
                    }
                    const key = e.target.result;
                    const objectStoreRequestDeletion = objectStore.delete(key);
                    objectStoreRequestDeletion.onsuccess = _ => {
                        console.log(`Request successful. Deleted room_secret: ${key}`);
                        resolve(roomSecret);
                    }
                    objectStoreRequestDeletion.onerror = e => {
                        reject(e);
                    }
                };
            }
            DBOpenRequest.onerror = e => {
                reject(e);
            }
        })
    }

    static clearRoomSecrets() {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = (e) => {
                const db = e.target.result;
                const transaction = db.transaction('room_secrets', 'readwrite');
                const objectStore = transaction.objectStore('room_secrets');
                const objectStoreRequest = objectStore.clear();
                objectStoreRequest.onsuccess = _ => {
                    console.log('Request successful. All room_secrets cleared');
                    resolve();
                };
            }
            DBOpenRequest.onerror = e => {
                reject(e);
            }
        })
    }

    static updateRoomSecretNames(roomSecret, displayName, deviceName) {
        return this.patchRoomSecret(roomSecret, {'display_name': displayName, 'device_name': deviceName});
    }

    static updateRoomSecretAutoAccept(roomSecret, autoAccept) {
        return this.patchRoomSecret(roomSecret, {'auto_accept': autoAccept});
    }

    /** 本机给这台设备起的别名（只存在本机，不会发给对方）。 */
    static updateRoomSecretAlias(roomSecret, alias) {
        return this.patchRoomSecret(roomSecret, {'alias': alias || null});
    }

    /** 记录最近一次成功建立会话的时间，供设备列表显示"上次连接"。 */
    static updateRoomSecretLastConnected(roomSecret, timestamp) {
        return this.patchRoomSecret(roomSecret, {'last_connected': timestamp});
    }

    /**
     * 局部更新一条已配对设备记录：只覆盖 patch 里出现的字段，其余原样保留。
     *
     * 旧写法是一长串位置参数（updatedRoomSecret、updatedDisplayName…），字段一多
     * 就容易错位，而错位会把 pair_secret 写坏、让配对静默失效，所以改成按字段更新。
     * 老记录没有后来新增的字段（alias / pair_method / last_connected），读取方按
     * "缺省即未知"处理，因此不需要 IndexedDB 版本迁移。
     */
    static patchRoomSecret(roomSecret, patch) {
        return new Promise((resolve, reject) => {
            const DBOpenRequest = window.indexedDB.open('lan_projects_store');
            DBOpenRequest.onsuccess = e => {
                const db = e.target.result;
                this.getRoomSecretEntry(roomSecret)
                    .then(roomSecretEntry => {
                        if (!roomSecretEntry) {
                            resolve(false);
                            return;
                        }
                        const transaction = db.transaction('room_secrets', 'readwrite');
                        const objectStore = transaction.objectStore('room_secrets');
                        // Do not use `updatedRoomSecret ?? roomSecretEntry.entry.secret` to ensure compatibility with older browsers
                        const entry = roomSecretEntry.entry;
                        const updatedRoomSecretEntry = {
                            'secret': entry.secret,
                            'pair_secret': entry.pair_secret,
                            'display_name': entry.display_name,
                            'device_name': entry.device_name,
                            'auto_accept': entry.auto_accept,
                            'alias': entry.alias !== undefined ? entry.alias : null,
                            'pair_method': entry.pair_method !== undefined ? entry.pair_method : null,
                            'last_connected': entry.last_connected !== undefined ? entry.last_connected : null
                        };
                        Object.keys(patch).forEach(key => {
                            if (patch[key] !== undefined) updatedRoomSecretEntry[key] = patch[key];
                        });

                        const objectStoreRequestUpdate = objectStore.put(updatedRoomSecretEntry, roomSecretEntry.key);

                        objectStoreRequestUpdate.onsuccess = e => {
                            console.log(`Request successful. Updated room_secret: ${roomSecretEntry.key}`);
                            resolve({
                                "entry": updatedRoomSecretEntry,
                                "key": roomSecretEntry.key
                            });
                        }

                        objectStoreRequestUpdate.onerror = (e) => {
                            reject(e);
                        }
                    })
                    .catch(e => reject(e));
            };

            DBOpenRequest.onerror = e => reject(e);
        })
    }
}