const https = require('https');
const { execFileSync } = require('child_process');
const fs = require('fs');

const requirements = {
    'unit.yml': ['JVM and API compatibility'],
    'device.yml': ['Restart API 21', 'Restart API 34', 'Old runtime API 17', 'Old runtime API 19'],
    'release-compatibility.yml': ['Published upgrade and R8 API 21', 'Published upgrade and R8 API 34'],
    'nightly-compatibility.yml': ['Nightly OkHttp312', 'Nightly OkHttp410', 'Nightly OkHttp412', 'Nightly OkHttp5', 'Nightly Stress20'],
};

function positiveInteger(value) {
    return Number.isSafeInteger(value) && value > 0;
}

function validateRun(run) {
    if (!run || !positiveInteger(run.id) || !positiveInteger(run.run_attempt) ||
        !/^[a-f0-9]{40}$/.test(run.head_sha || '') ||
        typeof run.head_repository?.full_name !== 'string' || !run.head_repository.full_name ||
        typeof run.head_branch !== 'string' || !run.head_branch ||
        typeof run.path !== 'string' || !run.path || typeof run.event !== 'string' || !run.event ||
        !['queued', 'in_progress', 'completed', 'waiting', 'requested', 'pending'].includes(run.status) ||
        !(run.conclusion === null || typeof run.conclusion === 'string')) throw new Error('Malformed workflow run');
}

function verifyRun(run, jobs, expected) {
    validateRun(run);
    if (!Array.isArray(jobs) || !requirements[expected.workflow]) throw new Error('Invalid jobs/required workflow');
    if (run.head_sha !== expected.sha || run.event !== 'push' ||
        run.head_repository?.full_name !== expected.repository ||
        run.path !== '.github/workflows/' + expected.workflow ||
        run.head_branch !== expected.ref) throw new Error('Wrong workflow/repository/ref/revision/event');
    if (run.status !== 'completed') return false;
    if (run.conclusion !== 'success') throw new Error('Workflow did not succeed: ' + expected.workflow);
    for (const name of requirements[expected.workflow]) {
        const matching = jobs.filter(job => job.name === name);
        if (matching.length !== 1) throw new Error('Missing or duplicate required job: ' + name);
        const job = matching[0];
        if (!positiveInteger(job.id) || !positiveInteger(job.run_id) || !positiveInteger(job.run_attempt) ||
            typeof job.head_sha !== 'string' || typeof job.status !== 'string' ||
            typeof job.conclusion !== 'string') throw new Error('Malformed required job: ' + name);
        if (job.head_sha !== expected.sha || job.run_id !== run.id || job.run_attempt !== run.run_attempt ||
            job.status !== 'completed' || job.conclusion !== 'success') {
            throw new Error('Required job is stale, skipped, pending or failed: ' + name);
        }
    }
    return true;
}

function api(path, token) {
    return new Promise((resolve, reject) => {
        const request = https.get({ hostname: 'api.github.com', path, headers: {
            Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'android-release-compatibility',
        } }, response => {
            let body = '';
            response.on('data', chunk => {
                body += chunk;
                if (body.length > 8 * 1024 * 1024) request.destroy(new Error('API response too large'));
            });
            response.on('error', reject);
            response.on('end', () => {
                if (response.statusCode !== 200) return reject(new Error('GitHub API HTTP ' + response.statusCode));
                try { resolve(JSON.parse(body)); } catch (error) { reject(error); }
            });
        });
        // Absolute deadline includes a peer that keeps sending bytes without finishing.
        const deadline = setTimeout(() => request.destroy(new Error('GitHub API deadline')), 20000);
        request.on('close', () => clearTimeout(deadline));
        request.on('error', reject);
    });
}

async function list(path, key, token, get = api) {
    const result = [];
    for (let page = 1; page <= 20; page++) {
        const response = await get(path + (path.includes('?') ? '&' : '?') + 'per_page=100&page=' + page, token);
        if (!Array.isArray(response[key]) || !Number.isInteger(response.total_count)) throw new Error('Invalid API page');
        result.push(...response[key]);
        if (result.length === response.total_count) return result;
        if (!response[key].length || result.length > response.total_count) throw new Error('Inconsistent API pagination');
    }
    throw new Error('API pagination limit exceeded');
}

async function inspect(expected, token, get = api) {
    const base = '/repos/' + expected.repository + '/actions';
    const runs = await list(base + '/workflows/' + expected.workflow + '/runs?event=push&head_sha=' + expected.sha, 'workflow_runs', token, get);
    runs.forEach(validateRun);
    const eligible = runs.filter(run => run.head_branch === expected.ref);
    if (!eligible.length) return null;
    const newest = eligible.sort((a, b) => b.id - a.id)[0];
    // Fetch the current attempt explicitly. An older green attempt must not mask a rerun.
    const run = await get(base + '/runs/' + newest.id, token);
    validateRun(run);
    if (run.id !== newest.id) throw new Error('Run detail identity mismatch');
    const jobs = await list(base + '/runs/' + run.id + '/attempts/' + run.run_attempt + '/jobs', 'jobs', token, get);
    return verifyRun(run, jobs, expected) ? { workflow: expected.workflow, run: run.id, attempt: run.run_attempt, sha: run.head_sha, jobs: jobs.map(job => ({ name: job.name, id: job.id, conclusion: job.conclusion })) } : null;
}

async function poll(check, { now = Date.now, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)), timeout = 100 * 60 * 1000 } = {}) {
    const deadline = now() + timeout;
    while (now() < deadline) {
        const results = await check();
        if (now() >= deadline) break;
        if (results.length === Object.keys(requirements).length && results.every(Boolean)) return results;
        console.log('Required push run missing or pending; publication remains blocked');
        await sleep(Math.min(30000, deadline - now()));
    }
    throw new Error('Timed out awaiting required compatibility jobs');
}

async function main() {
    const env = process.env;
    const sha = env.CIRCLE_SHA1;
    const repository = env.CIRCLE_PROJECT_USERNAME + '/' + env.CIRCLE_PROJECT_REPONAME;
    const ref = env.CIRCLE_TAG || env.CIRCLE_BRANCH;
    if (!/^[a-f0-9]{40}$/.test(sha || '') || repository !== 'supertokens/supertokens-android' ||
        !/^(dev-v\d+(\.\d+)*|v\d+(\.\d+)*|\d+\.\d+|test-cicd\/.+)$/.test(ref || '') ||
        !env.GITHUB_ACTIONS_READ_TOKEN || !env.CIRCLE_WORKFLOW_ID) throw new Error('Missing or invalid trusted CircleCI identity/token');
    if (execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() !== sha) throw new Error('Checkout differs from pipeline revision');
    const results = await poll(async () => {
        const results = [];
        for (const workflow of Object.keys(requirements)) results.push(await inspect({ sha, repository, ref, workflow }, env.GITHUB_ACTIONS_READ_TOKEN));
        return results;
    });
    fs.writeFileSync('required-compatibility.json', JSON.stringify({ sha, ref, repository, circleWorkflow: env.CIRCLE_WORKFLOW_ID, results }, null, 2));
    console.log('Exact-revision required compatibility jobs passed');
}

module.exports = { requirements, verifyRun, inspect, list, poll };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
