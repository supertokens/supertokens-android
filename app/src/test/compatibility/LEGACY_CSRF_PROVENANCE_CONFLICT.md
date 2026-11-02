# Cookie-to-header anti-CSRF provenance boundary

The published 0.5.4 oracle retains an existing anti-CSRF token when a 200
response supplies a complete header tuple without an anti-CSRF header. In the
cross-product's COOKIE and EXPOSED_COOKIE rows, the old and new front-token
bytes are identical and identify the same user, but contain no session handle.
That does not establish that the new access/refresh credentials belong to the
old session: another session for the same user can have the same front token.

The candidate intentionally treats a complete header tuple arriving for an
opaque cookie session as a replacement. `replaceForResponseLocked` takes only
the response tuple, rather than borrowing credentials from the displaced owner.
Retaining the old anti-CSRF token in that path would combine credentials from
potentially different sessions. Treating it as a promotion based on user ID or
front-token equality would also preserve an owner without proof of session
continuity. Neither is safe under the ownership and token-provenance contract.
An explicit stable session identity or independently established binding would
be needed before narrowing this difference safely.

Approved decision: keep the ownership fence and treat this as an intentional
0.5.4 compatibility difference. The cross-product checks the published oracle's
retained token and the candidate's absent token separately, in storage and on
the next request; all other fields remain compared to the oracle. The exception
is limited to 200 responses with prior COOKIE or EXPOSED_COOKIE and COMPLETE or
DUPLICATE_ACCESS shapes, across both configurations and transports.
