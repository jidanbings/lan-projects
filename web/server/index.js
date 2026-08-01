import os from "os";

import LanProjectsServer from "./server.js";
import LanProjectsWsServer from "./ws-server.js";

// Global error handlers
process.on('SIGINT', () => {
    console.info("SIGINT Received, exiting...");
    process.exit(0);
});
process.on('SIGTERM', () => {
    console.info("SIGTERM Received, exiting...");
    process.exit(0);
});
process.on('uncaughtException', (error, origin) => {
    console.log('----- Uncaught exception -----');
    console.log(error);
    console.log('----- Exception origin -----');
    console.log(origin);
});
process.on('unhandledRejection', (reason, promise) => {
    console.log('----- Unhandled Rejection at -----');
    console.log(promise);
    console.log('----- Reason -----');
    console.log(reason);
});

// Configuration
function parseEnvBool(key) {
    const val = process.env[key];
    return val === "true" || val === true;
}

function resolveRateLimit() {
    if (process.argv.includes('--rate-limit') || parseEnvBool('RATE_LIMIT')) {
        return 5;
    }
    const envRateLimit = parseInt(process.env.RATE_LIMIT);
    return isNaN(envRateLimit) ? false : envRateLimit;
}

// Get LAN IP for the shared IP room and the startup banner.
function getLanIp() {
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
        for (const iface of interfaces[name] || []) {
            if (iface.family === 'IPv4' && !iface.internal) {
                return iface.address;
            }
        }
    }
    return null;
}

const conf = {
    debugMode:  parseEnvBool('DEBUG_MODE'),
    port:       process.env.PORT || 3000,
    wsFallback: process.argv.includes('--include-ws-fallback') || parseEnvBool('WS_FALLBACK'),
    ipv6Localize: parseInt(process.env.IPV6_LOCALIZE) || false,
    rateLimit:  resolveRateLimit(),
    autoStart:  process.argv.includes('--auto-restart'),
    // Shared IP room: every peer that connects to THIS server joins the same
    // room (the server's LAN address). The host's WebView connects over
    // loopback (source 127.0.0.1) while LAN clients connect from their own IP;
    // using each peer's source IP as the room made host and clients land in
    // DIFFERENT rooms -> different encryption keys -> host<-client transfers
    // could not be decrypted/saved. A single shared room fixes that.
    lanIp: getLanIp(),
};

// Debug logging
if (conf.debugMode) {
    console.log("DEBUG_MODE is active.");
    console.debug("\n----DEBUG ENVIRONMENT VARIABLES----");
    console.debug(JSON.stringify(conf, null, 4));
}

// Start
const lanProjectsServer = new LanProjectsServer(conf);
new LanProjectsWsServer(lanProjectsServer.server, conf);

console.log('\nlan-projects is running on port', conf.port);

if (conf.lanIp) {
    console.log(`局域网访问: http://${conf.lanIp}:${conf.port}`);
} else {
    console.log('未检测到局域网 IP，请检查网络连接');
}
