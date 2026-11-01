# M6 ranks 13/14: retained cookies and application context

New JVM tests use current production unchanged.

`RetainedCookieAdoptionTest` verifies persistent-cookie-store reload after the
modeled asynchronous apply has completed, cookie retention after clear/reconfigure,
late-response rejection, and unchanged ABSENT state after a fresh response without
session headers. A fresh current-owner in-scope response with front-token metadata
can adopt the cookie-authenticated user under a new owner/adoption ID and yields the
tag's accepted-snapshot receipt. It need not be a specially named login endpoint.

This rule comes from `SESSION_OWNERSHIP.md` (request ownership and accepted-response
receipts), `COMPATIBILITY_IMPLEMENTATION.md` (ambient jars outside fencing), and
`SessionState.applyOrdinaryResponse` (cookie candidate admission under current owner
and configuration). Local cookies alone do not resurrect state; there is **no**
existing promise that clear/reconfigure purges a host jar or prevents a new response
from authenticating its retained cookies. A stronger rule would require a policy
decision, not a new assertion or SDK behavior in this coverage patch.

The no-CookieHandler case preserves URLConnection's existing exact pre-wire
IllegalAccessException and retained snapshot. Cookie apply completion is explicitly
modeled before restart; this does not establish crash-before-flush durability.

`ContextRetentionTest` initializes separately with a mocked Activity and a generic
mock Context, each returning a distinct application Context. The SDK weak reference
must point to that application, not the supplied object. Repeated build with a
different Activity and invalid URL is a complete no-op: no context interaction,
no preferences commit, same snapshot and original application reference.

Staged isolated result: five new tests plus 34 related cookie/configuration/boundary
tests pass (39 total). No device, real Activity lifecycle, Gradle or backend mutation
was executed. The tests preserve the current weak-reference policy; they do not add
a strong-retention or GC-lifetime guarantee.
