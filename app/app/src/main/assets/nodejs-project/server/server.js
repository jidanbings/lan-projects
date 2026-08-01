import express from "express";
import RateLimit from "express-rate-limit";
import {fileURLToPath} from "url";
import path, {dirname} from "path";
import http from "http";

export default class LanProjectsServer {

    constructor(conf) {
        const app = express();

        if (conf.rateLimit) {
            const limiter = RateLimit({
                windowMs: 5 * 60 * 1000, // 5 minutes
                max: 1000, // Limit each IP to 1000 requests per `window` (here, per 5 minutes)
                message: 'Too many requests from this IP Address, please try again after 5 minutes.',
                standardHeaders: true, // Return rate limit info in the `RateLimit-*` headers
                legacyHeaders: false, // Disable the `X-RateLimit-*` headers
            })

            app.use(limiter);
            app.set('trust proxy', conf.rateLimit);

            if (!conf.debugMode) {
                console.log("Use DEBUG_MODE=true to find correct number for RATE_LIMIT.");
            }
        }

        const __filename = fileURLToPath(import.meta.url);
        const __dirname = dirname(__filename);

        const publicPathAbs = path.join(__dirname, '../public');
        app.use(express.static(publicPathAbs));

        // Serve index.html for the root path
        app.get('/', (req, res) => {
            res.sendFile(path.join(publicPathAbs, 'index.html'));
        });

        // Config endpoint
        app.get('/config', (req, res) => {
            res.send({
                signalingServer: false,
                buttons: {
                    donation_button: {},
                    twitter_button: {},
                    mastodon_button: {},
                    bluesky_button: {},
                    custom_button: {},
                    privacypolicy_button: {}
                }
            });
        });

        // All other routes redirect to root
        app.use((req, res) => {
            res.redirect(301, '/');
        });

        const hostname = conf.localhostOnly ? '127.0.0.1' : null;
        const server = http.createServer(app);

        server.listen(conf.port, hostname);

        server.on('error', (err) => {
            if (err.code === 'EADDRINUSE') {
                console.error(err);
                console.info("Error EADDRINUSE received, exiting process...");
                process.exit(1)
            }
        });

        this.server = server
    }
}