const qualification = require("./accountLinkingQualification");

try {
    const args = process.argv.slice(2);
    if (args.length && (args.length !== 1 || args[0] !== '--capability')) throw new Error('Expected no arguments or --capability');
    const check = args.length ? qualification.requireQualified : qualification.requireExcluded;
    const result = check(qualification.identity(), qualification.readEvidence());
    const label = result.refreshUserIdChange === true ? 'SUPPORTED CAPABILITY; R7 FIXTURE UNIMPLEMENTED' : 'EXCLUDED';
    console.log(label + " with exact-pair/runtime evidence: " + result.reason + ". Not an R7 pass.");
} catch (error) {
    console.error("Account-linking qualification gate failed: " + error.message);
    process.exitCode = 1;
}
