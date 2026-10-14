// pm2 process file: `pm2 start ecosystem.config.cjs` (Node 20.6+ for --env-file).
module.exports = {
  apps: [{
    name: 'ss-bot',
    script: 'src/index.js',
    node_args: '--env-file=.env',
    cwd: __dirname,
    autorestart: true,
    max_restarts: 50,
    restart_delay: 5000,
  }],
};
