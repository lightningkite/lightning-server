@file:MustUseReturnValues

package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.postRawChanges

/**
 * Returns this table wrapped so that every query against it is recorded in [log] before it runs.
 *
 * Every read and write is recorded, including the privileged ones a server makes internally. A query whose
 * record cannot be written does not run.
 *
 * The model must be registered in the [AuditRegistry] passed to [log]. The registry registers the
 * [AuditedModel]s used by the server's endpoints when pre-deploy tasks run, so a model that no endpoint uses
 * has no entry, and every query against it throws [IllegalStateException] without running.
 */
context(runtime: ServerRuntime)
public fun <T : AuditedModel<ID>, ID : AuditedModel.Id<*, ID>> Table<T>.logQueries(log: AuditQueryLog): Table<T> =
    AuditQueryLogTable(this, log, runtime)

/**
 * Returns this table wrapped so that every change it makes to an [Audited] field is recorded in [log].
 *
 * Each change is recorded after it is made, through [AuditMutationLog.logAuditedFieldChanges]. The
 * `…IgnoringResult` and `…IgnoringOld` calls run as their effect-returning equivalents so the changed rows can
 * be recorded.
 *
 * The model must be registered in the [AuditRegistry] passed to [log]. The registry registers the
 * [AuditedModel]s used by the server's endpoints when pre-deploy tasks run, so a model that no endpoint uses
 * has no entry. Changes to an unregistered model throw [IllegalStateException] *after* the change has been
 * made, so the change stands but the caller sees a failure.
 */
context(serverRuntime: ServerRuntime)
public fun <T : AuditedModel<ID>, ID : AuditedModel.Id<*, ID>> Table<T>.logAuditedFieldChanges(log: AuditMutationLog): Table<T> =
    postRawChanges { changes -> log.logAuditedFieldChanges(serializer, changes) }
