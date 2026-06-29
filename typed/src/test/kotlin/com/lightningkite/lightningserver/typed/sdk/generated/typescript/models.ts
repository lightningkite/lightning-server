import type { Query, MassModification, EntryChange, ListChange, Modification, Condition, GroupCountQuery, AggregateQuery, GroupAggregateQuery, Aggregate, SortPart, DataClassPath, DataClassPathPartial, QueryPartial, DeepPartial, Fetcher, Brand } from '@lightningkite/lightning-server-simplified'

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
	updateRestrictions: UpdateRestrictions<T>
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
	_id: TestModel.ID
	name: string
	statusInfo: TestModel.TestStatusInfo
}

export namespace TestModel {
	export type ID = Brand<Uuid, "TestModel.ID">

	export enum Status {
		Active = "Active",
		Inactive = "Inactive",
		Pending = "Pending",
	}

	export interface TestStatusInfo {
		status: TestModel.Status
		updatedAt: number
	}
}

export interface UpdateRestrictions<T> {
	mode: UpdateRestrictions.Mode
	fields: Array<UpdateRestrictions.Part<T>>
}

export namespace UpdateRestrictions {
	export enum Mode {
		Blacklist = "Blacklist",
		Whitelist = "Whitelist",
	}

	export interface Part<T> {
		property: DataClassPathPartial<T>
		requires: Condition<T>
		limitedTo: Condition<T>
	}
}

export type Uuid = string  // kotlin.uuid.Uuid

