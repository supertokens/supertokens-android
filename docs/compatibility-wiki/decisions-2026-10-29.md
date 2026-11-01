# Decision register — 2026-10-29

[Index](index.md) · [Execution](execution-2026-10-29.md) · [Baseline](baseline-2026-10-29.md)

## Accepted source decisions

**2026-10-30 user-approved qualification: preserve legacy; strict opt-in.** F2 bounds SDK flights/dispatches, not caller OkHttp wire retries/follow-ups. Owned refresh requires >=3.14 and at most one exchange per execution; modern no-arg one-shot behavior is an intentional improvement qualifying absolute D5 parity. Migration is optional; a legacy-led flight retains its policy. Clean independent review `ses_e74c4a14dffegj30z0L805FmtL`; [decision, actual docs and proof](status-2026-10-30.md#approved-contract-and-independent-review). Historical owned branches assert constructor rejection, not owned execution. No SDK production change; 36/36 hashes match.

D1–D6 remain defined in [the compatibility plan](../../COMPATIBILITY_PLAN.md#decisions-agreed). The later user choices below explicitly qualify exact legacy getter parity for migration. F1–F5 and R1–R17 remain defined in the source plan.

The delegation approves `docs/compatibility-wiki` as the coordination location and limits this agent to foundation documentation. Source plans stay linked and unedited. These are coordination decisions recorded 2026-10-29.

## User-approved migration choices

Approved **2026-10-29**. Authority: the user's choice relayed explicitly in this documentation conversation, in the message beginning “Ingest user-approved decisions dated2026-10-29”. No separate originating conversation ID was supplied. These are user decisions, not deductions from passing characterization tests.

- **Missing legacy marker:** recover readable credentials and synthesize an update marker. This intentionally departs from exact legacy getter parity; the published getter observation is retained as historical evidence, not the required recovery outcome.
- **Ambiguous existing schema 1:** when credentials are readable but metadata contradicts them and no fingerprint exists, perform one-time recovery of readable data rather than fail closed. This accepts that corruption and older-SDK rollback edits cannot be distinguished from that evidence.
- **Explicit installation:** strict explicit `installSession` validation remains strict.

These choices resolve the prior missing-marker recommendation and ambiguous schema-1 policy question; they do not establish implementation completion, broader pre-init getter parity, or device persistence. [Oracle findings](baseline-2026-10-29.md#oracle-characterization-and-current-slice-reports) inform the implementation; [ownership](execution-2026-10-29.md#active-ownership) assigns import/rollback work.

Implementation update **2026-10-29**: choices implemented by `ses_e78448ed1ffesylJ70UuHlkgEn`; safeguards reviewed resolved by `ses_e780ce08fffeKOueWD9wXFdTXc`. Later [runtime integration](api-runtime-gate-2026-10-29.md#applied-runtime-integration) reports 233 JVM/36 oracle green after reviewed rebaselines, superseding the historical assertion conflicts. Device persistence remains unverified.

## User-approved response expiry policy

Approved **2026-10-29**. Authority: this conversation's user message beginning “Wiki ingest dated user decision2026-10-29”; no separate originating conversation ID supplied.

Preserve legacy response acceptance: **no expiry rejection based on device time or server `Date`**. Device time drives bounded proactive refresh. Explicit `installSession` validation remains strict. This supersedes the earlier unresolved `Date` policy and the source plan's proposed server-time rejection; source documents remain unedited. M2 implementation/verification is separate from this approval.

## User-approved legacy stale outcomes

Approved **2026-10-29** in the user's actual question answers, relayed explicitly by this conversation's message beginning “IngestUSERdecisions2026-10-29”. No originating session ID supplied. These are user choices, distinct from prior coordinator-only guidance.

- Legacy `attemptRefreshingSession` encountering stale ownership returns **false**.
- Legacy sign-out stops without mutating session B or dispatching/retrying new work. Already received HTTP/body errors remain preserved.
- Secure payload getter maps stale ownership to existing **`IOException("Could not refresh session")`**: no `StaleSessionException` leakage, no expired A payload, no borrowed B payload.
- Authorize the **precise 10 legacy strict-sign-out test rebaselines** discussed in those answers. Tagged requests and explicit new APIs remain strict. This is not blanket permission to relax ownership assertions or allowlist stale exceptions; exact test names were not supplied in this wiki handoff.

Implementation and staged evidence: [boundary diagnosis](upgrade-qualification-2026-10-29.md#current-verification-and-staged-risks). Approval does not establish integrated completion.

## Open acceptance questions

Implementation clarification **2026-10-29**: M2's initial passing run missed an immediate fast-path strict-validation bypass. Review `ses_e77f04778ffegEhXTcwkhqP8df` led to the correction now applied/tested in [M3 integration](m2-m3-handoff-2026-10-29.md#m3-integration-and-m4-staging), source `ses_e7816e0ebffeJVAbgd0O8MO2iD`. This preserves existing strict-path policy, not a new relaxation.

Source: [implementation companion, details to settle](../../COMPATIBILITY_IMPLEMENTATION.md#details-to-settle-before-coding-their-batch), inspected 2026-10-29. The recommendations there are proposals, not new accepted D decisions.

1. **Expiry validation — resolved later on 2026-10-29:** the [user-approved response expiry policy](#user-approved-response-expiry-policy) supersedes the earlier unresolved server-`Date` question. Implementation tests must establish accepted expired responses and bounded proactive refresh while preserving strict explicit installation.
2. **Sign-out — before batch 6:** characterize unknown status, missing status, empty/HTML body and GENERAL_ERROR, with and without removal headers. Done when both exception and committed post-state expectations have oracle evidence.
3. **Pre-init getter scope — before 7A:** include access token and unexpired secure payload alongside the listed getters; characterize malformed/expired inputs. Done when the supported subset and uncovered behaviour are explicit.
   Update 2026-10-29: harness session `ses_e78448ed1ffesylJ70UuHlkgEn` reports published pre-init `getUserId` requires the update marker alongside front token; see [result provenance](baseline-2026-10-29.md#agent-reported-harness-result). Further characterization remains open. Do not generalize this requirement to other getters or treat it as approval of an import policy.
4. **Refresh event completion — implemented/reviewed, 2026-10-29:** [corrected R10 evidence](m4-m5-upgrade-2026-10-29.md#r10-corrected-integration) covers public followers completing independently of the blocked leader handler, without duplicate events or inheriting its exception. This is the deadlock-safe implementation choice, not an oracle-derived concurrent-follower fact. Legacy caller-specific wrapping/suppression follows the oracle. Earlier blanket no-wrapping guidance was a **coordinator error, not a user decision**. Atomic commit-before-callback remains required under D6, intentionally differing from published partial writes on handler failure.
5. **Oldest runtime — before release gates:** decide how to verify the declared API 17 floor. API 19/21 success alone is insufficient evidence. Done with API 17 runtime evidence or an explicitly accepted verification gap, without silently changing the supported minimum.
6. **Published runtime/dependency floor — applied preservation, release gaps remain, 2026-10-29:** [runtime integration](api-runtime-gate-2026-10-29.md#applied-runtime-integration) reports Java 7 release classes, green JVM/oracle/API gates and seven fresh-artifact historical OkHttp probes. No raised legacy floor adopted. Review `ses_e780086eeffeABhsUZF5dYVJKg`, API17 execution, historical consumer builds and R8 remain pending; lint's JNA warning and scoped JVM probes do not establish complete device/runtime certification.
7. **Schema-1 migration without evidence — resolved later on 2026-10-29:** previously unresolved and import classification deferred. The [user-approved migration choices](#user-approved-migration-choices) supersede that status for readable credentials with contradictory metadata and no fingerprint. [Design constraints](migration-findings-2026-10-29.md) still apply; approval of recovery does not prove the cause of contradictory data.

## Recording resolutions

**Current resolution, 2026-10-29:** [integrated healthy-replacement fix](upgrade-qualification-2026-10-29.md#latest-integrated-legacy-and-healthy-recovery-fix), clean review `ses_e77b97b01ffeYLuHmzLT6BWBs1`, closes prior recovery-stress blockers with green integrated gates and unchanged allowlists. Atomic UNAVAILABLE/pending/same-transient-owner flag projects only legacy attempt-refresh/sign-out to false/no-op. Strict resolution/tagged behavior, genuine recovery errors, exact own-persistence exception identity, callback provenance and ten approved rebaseline guards remain. This is not a recovery-success waiver. Earlier open-blocker statements below are historical. M7 docs/version application is now underway, not completed; unsupported old-generation overload documentation remains preserved.

**Latest resolution, 2026-10-29:** callback-masking P1 fixed and review clean (`ses_e7797a1d8ffeBvIxpxDUwRX0Ea`), superseding the open finding below. The **ten precise approved legacy rebaselines and strict/tagged guards remain intact**. Callback-created exceptions retain identity; no stale-owner guard leakage is reported in the stage. [Combined recovery stress remains a blocker](upgrade-qualification-2026-10-29.md#current-verification-and-staged-risks), not an approved exception or allowlist. Focused modeled-crash/live-lost-response success does not waive actual device-death coverage or convert unsupported R7 into success.

**Latest status, 2026-10-29:** [R15/XML integration](upgrade-qualification-2026-10-29.md#current-verification-and-staged-risks) and both clean merged reviews close staged lock findings, superseding earlier pending status below. User-approved stale outcomes remain unchanged: the separate legacy-stale patch is unapplied after review found callback-thrown `StaleSessionException` masked during absent sign-out. Nonthrowing admission/dispatch separation is under repair; no callback suppression waiver is implied. M7 0.6.0 patch stays TEMP/unapplied; preserve unsupported old-generation overload documentation rather than folding it away.

**2026-10-29 scope reminder:** audited [20/20 API34 upgrade stages](upgrade-qualification-2026-10-29.md) qualify recorded completed-write scenarios/current pair, not future R15. R15 findings and legacy-stale stress require fixes/diagnosis, not policy exceptions or allowlisting. [New StrictMode red/green 3/3](m4-m5-upgrade-2026-10-29.md#valid-replacement-strictmode-proof) is valid scoped evidence; prior 2/2 remains invalid history.

**Performance evidence correction, 2026-10-29:** [old 2/2 invalidation](m4-m5-upgrade-2026-10-29.md#strictmode-performance-evidence-invalidated) remains permanent. The later barrier/bounded-wait monitor, real disk control and actual 3/3 rerun satisfy the replacement-proof requirement for the tested API34 startup cases. No performance waiver or R15 certification is implied.

Status clarification, **2026-10-29**: the [reviewed narrow R1 completion](baseline-2026-10-29.md#cookie-recovery-fix-and-re-review) and [fixture P2 resolution](baseline-2026-10-29.md#fixture-p2-resolution) are scoped implementation/review reports from uncommitted working trees. Policy approval comes separately from the user choices above; these reports establish neither Core parity for FakeBackend nor device persistence.

The coordinator records the approving party, date, affected D/R/F identifiers, oracle artifact/checksum, exact test and result, repository/commit plus working-tree changes, and the chosen policy. Link new dated evidence from this page. Reopen a question when evidence contradicts its assumption; fixture behaviour alone cannot resolve published behaviour.
