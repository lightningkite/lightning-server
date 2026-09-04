# Audit system: what could go wrong

A register of known problems in the audit implementation as of 2026-09, written by the person who
built it. Ordered by how much damage each could do. Companion to `audit-logging.md`, which says what
the system is *meant* to do; this says where the built thing falls short of that.

Two things are worth knowing before reading. First, several of the designs below were decided **while
implementing**, not specified in advance — those are marked. Second, nothing here has run in
production or under concurrent load; the evidence is unit and integration tests only.

An independent adversarial review has since run over all four implementation commits. It found one
silent bypass and several data gaps, all now fixed (`494f65eb`), and it independently confirmed the
two things I most wanted confirmed: `Table` coverage has no holes (21 of 23 members overridden, the
three others touch no data), and nothing in either repository unwraps `.wraps`. Where its findings
changed the picture below, the sections say so.

---

## 1. Fail-closed on reads can take the whole server down — including at boot

**Severity: highest. This is the one to think about before deploying.**

The data access log fails closed: a query against an audited model whose record cannot be written
does not run. That is the correct rule for a compliance log, and it is the same rule the disclosure
log follows — but the blast radius is much larger here.

The disclosure log only fails requests that actually disclose. The data access log sits at the
database layer and therefore covers **privileged internal reads too**: startup tasks, schedule ticks,
one service reading another's model. So an audit-database outage does not degrade the server, it
stops it — and if the outage is present at boot, a startup task that reads an audited model fails and
the server may not come up at all.

Nothing about this is accidental, and `audit-logging.md` 6.2 says it plainly. But the plan reasoned
about fail-closed in the context of *disclosure*, where the argument is airtight ("a disclosure that
cannot be recorded must not happen"). Extending the same rule to a privileged internal read is a
bigger claim, and it deserves an explicit decision rather than inheriting one.

**Options if that is unacceptable:** scope the decorator to user-facing tables only (weakens the
guarantee the layer exists for), or make failure policy configurable per model.

## 2. Integrity rests on the server behaving, and always did

A statement of what this system is, not a gap in it. **The only integrity mechanism is whether the
server does what it says it does.** Code running in the server can lie about what the server did, and nothing in the server can
prevent that.

What a hash chain *would* defend is narrower than it first sounds: an adversary who can write the
audit tables but cannot read the key. Since the server would compute the chain, the key lives with the
application's runtime secrets — so the defence exists only where database write and that secret are
genuinely separate principals, which is a deployment property nothing here can verify. And even then
it catches edits and middle deletions but not truncation, because nothing in process knows how long
the chain should have been.

The implementation actually available in process is worse than that narrow case: an unkeyed hash in
the database it protects, recomputable by exactly the adversary it is meant to catch, while putting a
second write on the path of every audited read. For the same threat, prevention beats detection — see
below — which is why chaining here is not merely out of scope but wrong.

So this is a deliberate scope decision, not an oversight: **that narrower guarantee is bought with
deployment controls, not code.** Restrict who can write the audit tables; make the store append-only
at the infrastructure level where that is available; keep the audit principal distinct from the
application's. None of it is enforced here, and #4 raises the stakes, since these tables now hold the
sensitive values they audit.

If those controls prove insufficient, the emergency total-log outside the server is the answer — and a
better one than a chain, provided it is *externally driven* and treats silence as an alarm. See
`audit-logging.md` section 10.

## 4. The audit log now stores the sensitive values it exists to audit

`DataAccessRecord.condition` holds the serialized query. A probe like `find(ssn eq "123-45-6789")`
therefore writes that SSN into the audit table in the clear.

This is inherent to recording conditions — the value *is* the evidence, and redacting it would defeat
the oracle detection the layer exists for. But it means the audit database becomes a second copy of
sensitive data, with different access controls, different retention, and no masking. Section 11.4
concluded that reads of the audit log need no special mechanism; that conclusion was reached before
conditions were stored, and is worth revisiting.

It also interacts with erasure (#6): shredding a subject's records does not shred a condition in a
data access row that happens to contain their identifier.

## 4b. Bypassing the audit log — mostly closed, one surface left

**Found by review, now largely fixed.** `ModelInfo.baseTable()` sat below the `log` decorator, so
anything holding a `ModelInfo` could read or mutate an audited model with no record — and
`media/.../processing.kt:128` was doing exactly that.

`baseTable()` now goes through the decorator: it means "without permissions", which is what callers
actually want, not "without a record". The genuine bypass moved to `dangerouslyDirectTable()`, which
requires opting in to `@UnauditedDatabaseAccess` (a `RequiresOptIn` **error**). The point is not to
forbid it — migrations legitimately need it — but to make every bypass greppable:
`grep -rn UnauditedDatabaseAccess` now answers "where is an audited model touched without a record",
which was previously unanswerable. The media call site is audited as a side effect.

**What remains open:** `DatabaseTableRegistration.invoke()` — i.e. calling `myTable()` on a registered
table — returns the undecorated table and is the *documented normal way* to use a table. It carries no
annotation and cannot reasonably carry one, since it is the primary API for every table in every app.
So the hardening covers `ModelInfo`, which is where audited models are conventionally reached, and not
the registration surface underneath it.

## 5. An audited model that no endpoint returns cannot be read at all

Model ids are assigned by scanning **endpoint serializers**, never tables — deliberately, because
scanning tables once made disclosure coverage look complete when it was not. The data access log keys
off the `@Audited` annotation and then resolves the id, which throws when there is none.

The consequence: an `@Audited` model that no endpoint's serializer can reach has no id, so **every
read of it fails**, including internal ones. A model that is only ever read internally — which is
exactly the case this layer was built to cover — cannot be data-access-logged until something makes it
reachable.

Failing loudly is right; the state that produces the failure is not. Resolving it means either a
second registration space for table-only models or an explicit opt-in list, and neither is decided.

## 5b. The typed `.test` helper did not enforce authorization (fixed)

An endpoint's `AuthRequirement` is asserted inside `request.access(auth)` on the real HTTP path
(`ApiHttpHandler.kt:115`). The typed test helper built `HttpAccess(request, auth)` directly, so the
requirement was never consulted and **every authorization assertion written through it was vacuous** —
it would have passed against an endpoint with no requirement at all.

Fixed: all eight overloads now go through an `assertAuth` helper that calls `auth.assert(presented)`,
the same check the real path makes. A rejected caller gets the `ForbiddenException` the endpoint
would really have produced.

It found something immediately. `FunnelEndpointsTest` built its server with `read =
AuthRequirement.IsAdmin` and never configured `AuthRequirement.isSuperUser`, which has no default —
so that endpoint rejected *every* caller, and six tests exercised it with a null auth and passed.
They now configure what `IsAdmin` means and supply an admin, and a new negative test asserts the
rejection. Confirmed by mutation: with the old helper the original tests pass 13/13, which is the
vacuity demonstrated rather than asserted.

**A structural reason such a test could not previously exist.** `Authentication<SUBJECT>` is
invariant (`Authentication.kt:125`) while library endpoints type their callers as `HasId<*>?`, so
passing a satisfying auth to an `IsAdmin`-guarded endpoint through the typed helper requires an
unchecked widening cast. There is already one precedent for that cast in the repo. Making
`Authentication` covariant would remove the need, but `PrincipalType` holds a `KSerializer<SUBJECT>`,
so it is not a one-line change. Not attempted.

A sweep of 70 rejection assertions and 19 non-null-auth call sites found no other authorization
assertion going through the helper — every other rejection test covers a domain failure raised inside
a handler body, which this change does not affect.

## 6. Erasure is a contract with nothing behind it

There is no `AuditSubjectKey` and no deploy guard demanding one. The design for both is recorded in
`audit-logging.md` 11.2 and still stands, but it is deliberately not implemented: nothing would read
the map, so its only effect would be to fail a deploy unless it was populated — charging every
deployment a declaration in exchange for nothing while pinning guesses about an unbuilt feature into
public API.

**Nothing about erasure works, and nothing prompts for it either.** No encryption is performed,
there is no shred operation, and no deploy fails for want of a subject key. The decision still cannot
be retrofitted — records written before a key exists stay unshreddable — so a deployment that may
face an erasure request has to decide early on its own. The mutation log makes this larger, not
smaller: its `old`/`new` columns put record contents somewhere erasure would also have to reach.

## 7. The auth event log does not fail closed, unlike everything else

Deliberate, and argued in 7.3.1: an auth event has already happened when it is reported, usually from
a path that is itself rejecting something, and throwing there would replace a clean "your login
failed" with an unrelated server error and lose the original reason.

The cost, stated plainly: **an attacker who can make the audit database unavailable can make
authentication events go unrecorded while authentication keeps working.** That is the opposite of the
guarantee the other two layers give. Whether that asymmetry is acceptable is a policy question, not a
technical one.

**Coverage is also partial.** Only rejected authentications currently report. Issuance, refresh,
termination, per-method proof results and masquerade are listed in 7.3 and are not yet raised at their
call sites — the reporter will record them the moment they are.

## 7b. Unauthenticated writes into a fail-closed store

Found by review. Any request carrying a token this server cannot parse used to produce an auth event
row, before any authentication resolved — so an unauthenticated attacker spraying nonsense
`Authorization` headers wrote one audit row per request. Because the disclosure and data access logs
are **fail-closed on the same database**, degrading that store does not merely lose audit records, it
takes the server down. That is amplification from "send garbage" to "outage".

Closed by not treating unparseable input as an authentication event: `TokenMalformed` and
`TokenTypeMismatch` no longer report. Neither names an account, which is what an auth event is about,
and the request log already records that the request happened.

**The general shape remains**, and is worth keeping in mind for any layer added later: anything that
writes to the audit store on an unauthenticated path is a lever on the availability of everything that
shares it. Rate limiting the auth event writer, or giving it a separate store from the fail-closed
layers, would both harden this further.

## 8. Volume, and no retention story

One data access row per query against an audited model, one disclosure row per record disclosed, one
chain entry per `sealThreshold` records. A single `find` returning ten thousand audited records writes
ten thousand disclosure rows plus one access row.

There is no retention, archival, or partitioning design anywhere in the plan or the implementation.
For an append-mostly table on a fail-closed write path, that is a capacity problem that becomes an
availability problem — see #1.

## 9. Smaller things, and latent hazards

- **`DataAccessLogTable` exposes the undecorated table through `wraps`.** Confirmed by review that
  nothing in either repository unwraps it, and that `Table` has no sub-interfaces or downcasts that
  would. Latent only — see 4b for the surface that is not latent.
- **`groupBy` is recorded via `DataClassPath.toString()`**, which is not a documented stable format.
  If it changes, historical rows become harder to interpret. The same applies to the `aggregate`
  column, which now holds `Aggregate.toString()` and search params.
- **Coverage of `Table` was verified by enumeration** — 21 of 23 members overridden, the two skipped
  (`fullCondition`, `mask`) return metadata rather than data. That check should be repeated whenever
  `Table` gains a member, and nothing enforces it. A test that fails when an un-overridden data
  method appears would be worth more than the manual check.
- **An unrecognised event type string is dropped after logging**, which is the price of the stringly
  typed seam between `core` and the audit module. A typo silently loses events.
- **`DisclosureRecord` still does not carry `executionId`**, so a disclosure on a long-lived socket is
  placed within the session rather than at a message. Its v7 id timestamps it to the millisecond,
  which in practice identifies the message, but that is inference rather than attribution.

## 9b. Engine shutdown: two gaps left open

Both found by review of the socket work, both deliberately not fixed here.

**A connection accepted mid-shutdown is never disconnected.** `openChannels.close()` runs before
`bossGroup.shutdownGracefully()`, so a connection arriving in between joins the group after it was
closed and is neither closed nor awaited. It would get `willConnect` and `didConnect` and never
`disconnect` — the same class of loss the rest of this work fixed. Closing it properly means holding
the server channel and closing it first, so acceptance stops before active channels do. That is a
real change to the shutdown sequence and was not worth making immediately after reworking it.

**An in-flight HTTP request is still truncated by shutdown** (`unexpected end of stream`). Confirmed
pre-existing: a probe fails identically before and after this branch's socket work, so the channel
group did not cause it. Websocket disconnects are now delivered; plain HTTP requests in flight are
not drained.

## 10. Breaking changes shipped alongside

Not defects, but they will surprise someone:

- `DELETE /auth/sessions/{id}` no longer works. Sessions are terminated, never deleted.
- `BackupCodeSecret` requires `createdAt`. Deliberately not defaulted: a model cannot read the
  engine's selected clock, and a wall-clock default would reintroduce the fabricated value the
  neighbouring change removed.
- `SessionManager` gained a constructor parameter (defaulted, so source-compatible).

---

## What I would do first

1. Decide #1 — whether fail-closed on privileged reads is acceptable, because it gates whether this
   can be turned on at all.
2. Treat #2 as a deployment requirement, not a code one: decide who can write the audit tables and
   whether the store is append-only at the infrastructure level. That is where the narrower
   tamper-resistance is bought, and nothing in this repository enforces it.
3. Revisit 11.4 in light of #2 and #4 — its "no special mechanism needed" resolution rested partly on
   a hash chain, which this system deliberately does not have.

#3 in the original list — "get this reviewed by someone who did not write it" — has now happened, and
found a silent bypass plus four data gaps that my own reading missed. The three defects I had already
found in the chain, it found independently. That is the strongest available argument for doing the
same to whatever is built next.
