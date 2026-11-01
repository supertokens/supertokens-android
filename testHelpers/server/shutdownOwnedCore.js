require("./coreRuntimeIdentity").shutdownOwnedCore(process.argv[2]).then(result => {
    if (!result.stopped && result.reason !== "NO_OWNED_CORE" && result.reason !== "NOT_OWNED") process.exitCode = 1;
}).catch(error => { console.error("Owned Core cleanup failed: " + error.message); process.exitCode = 1; });
