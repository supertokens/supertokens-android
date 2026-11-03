const express = require("express");
const SuperTokens = require("supertokens-node");
const Session = require("supertokens-node/recipe/session");
const { middleware, errorHandler } = require("supertokens-node/framework/express");
const { verifySession } = require("supertokens-node/recipe/session/framework/express");

const port = Number(process.env.NODE_PORT || 18082);
if (!process.env.CORE_URI) throw new Error("CORE_URI must point to the isolated test Core");
SuperTokens.init({
    appInfo: { appName: "Android cookie regression", apiDomain: `http://localhost:${port}`, websiteDomain: "http://localhost:3000" },
    supertokens: { connectionURI: process.env.CORE_URI },
    recipeList: [Session.init({ antiCsrf: "VIA_TOKEN", cookieSecure: false, cookieSameSite: "lax", getTokenTransferMethod: () => "cookie" })]
});
const app = express();
app.use(express.json());
const observations = [];
app.use((req, res, next) => {
    if (["/login", "/protected", "/auth/session/refresh", "/auth/signout"].includes(req.path)) {
        const cookies = req.headers.cookie || "";
        const item = { path: req.path, accessCookie: /(?:^|;\s*)sAccessToken=/.test(cookies),
            refreshCookie: /(?:^|;\s*)sRefreshToken=/.test(cookies), csrf: !!req.headers["anti-csrf"] };
        res.on("finish", () => { item.status = res.statusCode; observations.push(item); });
    }
    next();
});
app.use(middleware());
app.post("/login", async (req, res, next) => {
    try {
        await Session.createNewSession(req, res, "public", SuperTokens.convertToRecipeUserId(`android-cookie-${Date.now()}`));
        res.json({ status: "OK" });
    } catch (error) { next(error); }
});
app.get("/protected", verifySession(), (req, res) => res.json({ status: "OK" }));
app.get("/observations", (req, res) => res.json(observations));
app.post("/reset-observations", (req, res) => { observations.length = 0; res.json({ status: "OK" }); });
app.use(errorHandler());
app.listen(port, "0.0.0.0");
