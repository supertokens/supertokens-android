const { spawn } = require('child_process');
let child;
function send(message) {
    if (!process.connected) return;
    process.send(message, error => {
        if (!error) return;
        if (!['EPIPE', 'ERR_IPC_CHANNEL_CLOSED', 'ERR_IPC_DISCONNECTED'].includes(error.code)) throw error;
        if (process.connected) process.disconnect();
        if (!child) process.exit(0);
    });
}
// The group leader must outlive the command and its workers, including after TERM.
process.on('SIGTERM', () => {});
setInterval(() => {}, 1000);
process.on('disconnect', () => { if (!child) process.exit(0); });
process.on('message', message => {
    if (message.type === 'exit') process.exit(0);
    if (message.type !== 'start' || !process.connected) return;
    child = spawn(message.command[0], message.command.slice(1), { env: process.env, stdio: 'inherit' });
    child.once('spawn', () => send({ type: 'started', pid: child.pid }));
    child.once('error', error => {
        send({ type: 'started', pid: null });
        send({ type: 'done', code: 1, error: error.message });
        child = undefined;
        if (!process.connected) process.exit(0);
    });
    child.once('exit', (code, signal) => {
        send({ type: 'done', code: signal ? 128 : code });
        child = undefined;
        if (!process.connected) process.exit(0);
    });
});
send({ type: 'ready' });
