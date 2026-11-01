const assert = require("assert");
const http = require("http");
const createLegacySession = require("./legacySession");

async function main() {
    let request;
    let status = 200;
    let responseBody = { status: "OK", accessToken: { token: "access", expiry: 123 }, refreshToken: { token: "refresh" } };
    const server = http.createServer((req, res) => {
        let body = "";
        req.on("data", chunk => { body += chunk; });
        req.on("end", () => {
            request = { method: req.method, path: req.url, headers: req.headers, body: JSON.parse(body) };
            res.writeHead(status, { "content-type": "application/json" });
            res.end(JSON.stringify(responseBody));
        });
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const url = `http://127.0.0.1:${server.address().port}`;
    try {
        const result = await createLegacySession(url, "user", { claim: true });
        assert.strictEqual(request.method, "POST");
        assert.strictEqual(request.path, "/recipe/session");
        assert.strictEqual(request.headers["cdi-version"], "2.18");
        assert.strictEqual(request.headers.rid, "session");
        assert.strictEqual(request.headers["content-type"], "application/json");
        assert.deepStrictEqual(request.body, { userId: "user", enableAntiCsrf: false, userDataInJWT: { claim: true }, userDataInDatabase: {} });
        assert.strictEqual(result.accessToken.token, "access");
        status = 500;
        await assert.rejects(createLegacySession(url, "user", {}), /HTTP 500/);
        status = 200;
        responseBody = { status: "ERROR" };
        await assert.rejects(createLegacySession(url, "user", {}), /invalid response/);
    } finally {
        await new Promise(resolve => server.close(resolve));
    }
}

main().catch(err => { console.error(err); process.exitCode = 1; });
