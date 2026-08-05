import type { Query, MassModification, EntryChange, ListChange, Modification, Condition, GroupCountQuery, AggregateQuery, GroupAggregateQuery, Aggregate, SortPart, DataClassPath, DataClassPathPartial, QueryPartial, DeepPartial, Fetcher } from '@lightningkite/lightning-server-simplified'

export interface CollectionUpdates<T, T1> {
	updates: Array<T>
	remove: Array<T1>
	overload: boolean
	condition: Condition<T> | null | undefined
}

export interface Mask<T> {
	pairs: Array<Pair<Condition<T>, Modification<T>>>
}

export interface ModelPermissions<T> {
	create: Condition<T>
	read: Condition<T>
	readMask: Mask<T>
	update: Condition<T>
	updateRestrictions: UpdateRestrictions
	delete: Condition<T>
	maxQueryTimeMs: number
}

export interface Pair<T, T1> {
	first: T
	second: T1
}

export interface TestInput {
	id: number
	name: string
}

export interface TestModel {
	_id: Uuid
	name: string
}

export interface UpdateRestrictions {
}

export type Uuid = string  // kotlin.uuid.Uuid

