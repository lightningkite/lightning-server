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

## 2. The in-process chain is a local check, not tamper-evidence

**Scope corrected: the real total-log is a separate system outside Lightning Server**, and the
integrity guarantees belong to it. What is in this repository is a modest in-process chain that audit
writes fold into. Read the following as "known properties of a local check", not as a list of security
holes — the docs and plan now say the same.

It detects an entry altered or removed, which catches accident and corruption. It does **not** detect
truncation at the end of a chain, does **not** detect deletion of the records an entry covers
(`contentHash` is a fold with no membership recorded, so nobody can recompute it), and its `hash` is
unkeyed, so anyone who can write the table can recompute the chain and it verifies clean. Those are
properties only something outside the process can supply.

The one thing worth watching: nothing in the code or the tests will stop someone describing this as
tamper-evidence in a compliance conversation. It is not.

**Two defects were found in it after committing** — a distributed-locked seal that would have left
every instance but one unattested, and a state advance before the write that would have broken the
chain permanently on one transient database error. Both were caught by re-reading, not by a test.
Given the reduced scope this matters less than it would have, but it is the honest signal about how
much scrutiny hand-written chain code needs.

**Specifically unexamined:** concurrent folds under load (the mutex is believed correct, never
stress-tested), and `chainId` collisions — it is `serverId + bootMillis`, and `serverId` derives from
a MAC address, so containers sharing a host interface and starting in the same millisecond collide.

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

## 6. Erasure is a contract with nothing behind it

`AuditSubjectKey` now exists, and `requireSubjectKeys` fails a deploy when an audited model has no
subject. That guard is real and worth turning on from a first deploy, because the decision cannot be
retrofitted.

**But no encryption is performed.** Supplying a key wraps nothing, and there is no shred operation.
The guard preserves the *option* to implement crypto-shredding later against records written now; it
does not make erasure work. A deployment that reads `requireSubjectKeys = true` as "erasure is
handled" would be badly wrong.

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
- **The `requireSubjectKeys` guard is narrower than its message implies** — it checks only the models
  the endpoint scan can see, so a table-only or open-polymorphic audited model passes it and still
  produces unshreddable records. Now stated in its KDoc.
- **An unrecognised event type string is dropped after logging**, which is the price of the stringly
  typed seam between `core` and the audit module. A typo silently loses events.
- **`DisclosureRecord` still does not carry `executionId`**, so a disclosure on a long-lived socket is
  placed within the session rather than at a message. Its v7 id timestamps it to the millisecond,
  which in practice identifies the message, but that is inference rather than attribution.

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
2. Make sure nobody describes the in-process chain (#2) as tamper-evidence. The real total-log is a
   separate system and that is where the guarantee lives.
3. Revisit 11.4 in light of #4.

#3 in the original list — "get this reviewed by someone who did not write it" — has now happened, and
found a silent bypass plus four data gaps that my own reading missed. The three defects I had already
found in the chain, it found independently. That is the strongest available argument for doing the
same to whatever is built next.
