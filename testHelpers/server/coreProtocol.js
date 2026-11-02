const fixtureOwners = require("./fixtureOwners");
const accountLinkingCapability = require("./accountLinkingCapability");
const linkingQualification = require("./accountLinkingQualification");
const fetch = require("node-fetch");
const SuperTokens = require("supertokens-node");
const Session = require("supertokens-node/recipe/session");
const { verifySession } = require("supertokens-node/recipe/session/framework/express");
const { version, cdiSupported } = require("supertokens-node/lib/build/version");

let ThirdParty;
let AccountLinking;
let Multitenancy;
try {
    Multitenancy = require("supertokens-node/recipe/multitenancy");
} catch (error) {
    if (error.code !== "MODULE_NOT_FOUND") throw error;
}
try {
    AccountLinking = require("supertokens-node/recipe/accountlinking");
} catch (error) {
    if (error.code !== "MODULE_NOT_FOUND") throw error;
}
try {
    ThirdParty = require("supertokens-node/recipe/thirdparty");
} catch (error) {
    if (error.code !== "MODULE_NOT_FOUND") throw error;
}

const enabled = process.env.CORE_PROTOCOL_TESTS === "1";
const sdkSupported = ThirdParty !== undefined &&
    typeof ThirdParty.manuallyCreateOrUpdateUser === "function" &&
    typeof SuperTokens.createUserIdMapping === "function" &&
    typeof SuperTokens.getUserIdMapping === "function" &&
    typeof SuperTokens.deleteUser === "function" &&
    typeof SuperTokens.deleteUserIdMapping === "function" &&
    typeof SuperTokens.listUsersByAccountInfo === "function" &&
    typeof SuperTokens.convertToRecipeUserId === "function";
const legacySdkSupported = ThirdParty !== undefined &&
    typeof ThirdParty.signInUp === 'function' &&
    typeof ThirdParty.getUserByThirdPartyInfo === 'function' &&
    typeof ThirdParty.Google === 'function' &&
    typeof SuperTokens.deleteUser === 'function';
const transitionalSdkSupported = version === '15.2.3' && ThirdParty !== undefined &&
    typeof ThirdParty.manuallyCreateOrUpdateUser === 'function' &&
    typeof ThirdParty.getUserByThirdPartyInfo === 'function' && typeof SuperTokens.deleteUser === 'function';
const tenantSdkSupported = Multitenancy !== undefined &&
    ["createOrUpdateTenant", "listAllTenants", "deleteTenant"].every(name => typeof Multitenancy[name] === "function");
const owners = fixtureOwners(SuperTokens, ThirdParty, Multitenancy, version);
const native = require('./nativeEvidence')({ owners, Session, snapshot: () => {
    const fs = require('fs');
    const path = require('path');
    const crypto = require('crypto');
    const root = process.env.INSTALL_PATH;
    const identity = linkingQualification.identity();
    if (!identity) throw new Error('Native runtime identity unavailable');
    const dependenciesSha256 = require('./ordinaryEnvironment').digestTree(path.join(root, 'backend', 'node_modules'));
    if (fs.readFileSync(path.join(root, 'dependencies.sha256'), 'utf8').trim() !== dependenciesSha256) {
        throw new Error('Native owned dependencies changed');
    }
    const coreManifestSha256 = require('./coreRuntimeIdentity').effectiveArtifacts(root).digest;
    if (coreManifestSha256 !== identity.effectiveClasspathSha256) throw new Error('Native Core artifacts changed during identity capture');
    return { identity, qualifiedIdentitySha256: crypto.createHash('sha256').update(JSON.stringify(identity)).digest('hex'),
        dependenciesSha256, coreManifestSha256 };
} });

function configure(config) {
    if (enabled && (sdkSupported || legacySdkSupported || transitionalSdkSupported)) {
        config.recipeList.push(sdkSupported || transitionalSdkSupported ? ThirdParty.init() : ThirdParty.init({
            signInAndUpFeature: { providers: [ThirdParty.Google({ clientId: 'fixture-only', clientSecret: 'fixture-only' })] }
        }));
    }
    return config;
}

async function capabilities() {
    let coreVersions = [];
    let coreReachable = false;
    try {
        const response = await fetch('http://127.0.0.1:' + process.env.ST_TEST_CORE_PORT + '/apiversion', { timeout: 3000 });
        if (response.ok) {
            coreVersions = (await response.json()).versions;
            coreReachable = Array.isArray(coreVersions);
        }
    } catch (_) {
        // Report unavailable Core separately from an unsupported SDK/Core pair.
    }
    const compatible = coreReachable && cdiSupported.some(value => coreVersions.includes(value));
    return {
        enabled,
        nodeVersion: version,
        sdkCdiVersions: cdiSupported,
        coreCdiVersions: coreReachable ? coreVersions : [],
        coreReachable,
        compatible,
        mappedGoogleSession: enabled && sdkSupported && compatible,
        legacyBaselineSession: enabled && (legacySdkSupported || transitionalSdkSupported) && compatible,
        nonPublicTenantSession: enabled && sdkSupported && tenantSdkSupported && compatible,
        accountLinking: accountLinkingCapability(enabled, compatible, AccountLinking, linkingQualification.identity(), linkingQualification.readEvidence()),
        qualificationProxy: false
    };
}

function install(app) {
    native.install(app);
    app.post("/login-tenant", async (req, res, next) => {
        try {
            const report = await capabilities();
            if (!report.nonPublicTenantSession) {
                return res.status(501).json({ status: "UNSUPPORTED_TEST_CAPABILITY", capabilities: report });
            }
            if (typeof req.body.email !== "string" || !req.body.email.includes("@")) {
                return res.status(400).json({ status: "INVALID_TEST_OWNER" });
            }
            const { owner, session } = await owners.create(req.body.email, (externalId, tenantId) =>
                Session.createNewSession(req, res, tenantId, SuperTokens.convertToRecipeUserId(externalId)), true);
            try {
                res.json({ fixtureId: owner.id, tenantId: owner.tenantId,
                    userId: session.getUserId(), handle: session.getHandle() });
            } catch (error) {
                try { await owners.cleanupById(owner.id); } catch (cleanupError) {
                    throw new AggregateError([error, cleanupError], "Fixture response failed; cleanup also failed and remains pending");
                }
                throw error;
            }
        } catch (error) { next(error); }
    });
    // Only exact-pair evidence permits an exclusion. Unknown pairs must qualify,
    // and a supported pair still needs a real fixture rather than a substitute login.
    app.post("/link-accounts", async (req, res, next) => {
        try {
            const report = await capabilities();
            const state = report.accountLinking.state;
            res.status(state === "unqualified" ? 503 : 501).json({
                status: ["backend_proven_unsupported", "pair_proven_unsupported"].includes(state) ? "UNSUPPORTED_TEST_CAPABILITY" :
                    state === "fixture_unimplemented" ? "FIXTURE_UNIMPLEMENTED" : "QUALIFICATION_REQUIRED",
                capability: "refreshUserIdChange", details: report.accountLinking
            });
        } catch (error) { next(error); }
    });
    app.get("/test/capabilities", async (req, res, next) => {
        try { res.json(await capabilities()); } catch (error) { next(error); }
    });

    app.post("/test/email-change-owner", async (req, res, next) => {
        try {
            const supported = await capabilities();
            if (!supported.mappedGoogleSession && (!supported.legacyBaselineSession || req.nativeEvidence)) {
                return res.status(501).json({ status: "UNSUPPORTED_TEST_CAPABILITY", capabilities: supported });
            }
            if (req.body.recipe !== "google" || typeof req.body.email !== "string" || !req.body.email.includes("@")) {
                return res.status(400).json({ status: "INVALID_TEST_OWNER" });
            }
            // Manual recipe creation uses real Core storage; it does not simulate an OAuth exchange.
            const { owner, session } = await native.provision(req, () => supported.mappedGoogleSession
                ? owners.create(req.body.email, async externalId =>
                    Session.createNewSession(req, res, "public", SuperTokens.convertToRecipeUserId(externalId)))
                : owners.createLegacy(req.body.email, async userId => transitionalSdkSupported
                    ? Session.createNewSession(req, res, 'public', userId) : Session.createNewSession(req, res, userId), transitionalSdkSupported));
            try {
                const tokens = session.getAllSessionTokensDangerously();
                res.json({ google: true, fixtureId: owner.id, userId: session.getUserId(), handle: session.getHandle(), refreshToken: tokens.refreshToken });
            } catch (error) {
                try { await owners.cleanupById(owner.id); } catch (cleanupError) {
                    throw new AggregateError([error, cleanupError], "Fixture response failed; cleanup also failed and remains pending");
                }
                throw error;
            }
        } catch (error) { next(error); }
    });

    app.get("/test/protected", (req, res, next) => verifySession()(req, res, next), (req, res) => {
        res.json({ userId: req.session.getUserId(), handle: req.session.getHandle() });
    });

    app.get('/test/payload-promotion', (req, res, next) => {
        if (!enabled || !sdkSupported) return res.status(501).json({ status: 'UNSUPPORTED_TEST_CAPABILITY' });
        return verifySession()(req, res, next);
    }, async (req, res, next) => {
        try {
            await req.session.mergeIntoAccessTokenPayload({ fixturePayloadProbe: true });
            res.json({ userId: req.session.getUserId(), handle: req.session.getHandle() });
        } catch (error) { next(error); }
    });

    app.post("/test/revoke-session", async (req, res, next) => {
        try {
            if (!enabled) return res.status(501).json({ status: "UNSUPPORTED_TEST_CAPABILITY" });
            if (typeof req.body.handle !== "string" || req.body.handle.length === 0) {
                return res.status(400).json({ status: "INVALID_SESSION_HANDLE" });
            }
            const revoked = await Session.revokeSession(req.body.handle);
            const ownerCleaned = await owners.cleanupByHandle(req.body.handle);
            res.json({ revoked, ownerCleaned });
        } catch (error) { next(error); }
    });

    app.post("/test/cleanup-owner", async (req, res, next) => {
        try {
            if (!enabled) return res.status(501).json({ status: "UNSUPPORTED_TEST_CAPABILITY" });
            if (req.nativeEvidence) return res.json(await native.cleanup(req));
            if (typeof req.body.fixtureId !== "string") return res.status(400).json({ status: "INVALID_FIXTURE_ID" });
            res.json({ cleaned: await owners.cleanupById(req.body.fixtureId) });
        } catch (error) { next(error); }
    });
    app.post("/test/owner-session-state", async (req, res, next) => {
        if (!req.nativeEvidence) return require("./ownerSessionState")(owners, Session, enabled)(req, res, next);
        try { res.json(await native.sessionState(req)); } catch (error) { next(error); }
    });
}

module.exports = { configure, install, cleanupOwners: owners.cleanupAll, native };
