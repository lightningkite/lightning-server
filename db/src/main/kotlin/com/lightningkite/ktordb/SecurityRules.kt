package com.lightningkite.ktordb

/*
Per-field security rules

rewrite: (Model, T) -> T
mask: (Model, T) -> T
modify: (Model, TModification) -> (TModification)
queryableRequires: TCondition


 */

@Deprecated("Prefer using the new ModelPermissions instead.")
interface SecurityRules<Model> {
    suspend fun sortAllowed(filter: SortPart<Model>): Condition<Model>

    suspend fun read(filter: Condition<Model>): Condition<Model>
    suspend fun edit(filter: Condition<Model>, modification: Modification<Model>): Pair<Condition<Model>, Modification<Model>> = read(filter) to modification
    suspend fun delete(filter: Condition<Model>): Condition<Model> = edit(filter, Modification.Chain(listOf())).first

    suspend fun mask(model: Model): Model = model
    suspend fun create(model: Model): Model = model
    suspend fun replace(model: Model): Pair<Condition<Model>, Model>
    suspend fun maxQueryTimeMs(): Long = 10_000L

    open class AllowAll<Model>: SecurityRules<Model> {
        override suspend fun sortAllowed(filter: SortPart<Model>): Condition<Model> = Condition.Always()

        override suspend fun read(filter: Condition<Model>): Condition<Model> = filter
        override suspend fun edit(filter: Condition<Model>, modification: Modification<Model>): Pair<Condition<Model>, Modification<Model>> = filter to modification
        override suspend fun delete(filter: Condition<Model>): Condition<Model> = filter

        override suspend fun mask(model: Model): Model = model
        override suspend fun create(model: Model): Model = model
        override suspend fun replace(model: Model): Pair<Condition<Model>, Model> = Condition.Always<Model>() to model
        override suspend fun maxQueryTimeMs(): Long = 100_000L
    }
}

//abstract class SimpleSecurityRules<Model> {
//    abstract suspend fun read
//}

@Deprecated("Prefer using new ModelPermissions instead")
fun <Model: Any> FieldCollection<Model>.secure(
    rules: SecurityRules<Model>
) = SecuredFieldCollection<Model>(this, rules)
