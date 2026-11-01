const createProxy = require("./qualificationProxy");

const config = createProxy.configuration(process.env);
if (!config.enabled || !config.target) throw new Error("Requires TEST_MODE=testing, CORE_PROTOCOL_TESTS=1 and QUALIFICATION_PROXY_TARGET");
const port = Number(process.env.QUALIFICATION_PROXY_PORT || 8081);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid qualification proxy port");
if (Number(new URL(config.target).port || 80) === port) throw new Error("Proxy cannot target its own listener");
const proxy = createProxy(config);
proxy.server.listen(port, "127.0.0.1", () => console.log("Qualification proxy listening on loopback port " + port));
for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, () => {
    proxy.close().then(() => { process.exitCode = 0; });
});
