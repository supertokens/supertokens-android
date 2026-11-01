const { spawn } = require('child_process');
// The group leader must outlive the command and its workers, including after TERM.
process.on('SIGTERM', () => {});
setInterval(() => {}, 1000);
process.on('message', message => {
    if (message.type === 'exit') process.exit(0);
    if (message.type !== 'start') return;
    const child = spawn(message.command[0], message.command.slice(1), { env: process.env, stdio: 'inherit' });
    child.once('spawn', () => process.send({ type: 'started', pid: child.pid }));
    child.once('error', error => {
        process.send({ type: 'started', pid: null });
        process.send({ type: 'done', code: 1, error: error.message });
    });
    child.once('exit', (code, signal) => process.send({ type: 'done', code: signal ? 128 : code }));
});
process.send({ type: 'ready' });
