import type { Query, MassModification, EntryChange, ListChange, Modification, Condition, GroupCountQuery, AggregateQuery, GroupAggregateQuery, Aggregate, SortPart, DataClassPath, DataClassPathPartial, QueryPartial, DeepPartial, Fetcher } from '@lightningkite/lightning-server-simplified'


export interface CollectionUpdates<T, T1> {
	updates: Array<T>
	remove: Array<T1>
	overload: boolean
	condition: Condition<T> | null | undefined
}

export interface Mask<T> {
	pairs: Array<Pair>
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

export interface Pair {
}


export interface TestInput {
	id: number
	name: string
}

export interface TestModel {
	_id: Uuid
	name: string
}

export interface UpdateRestrictions<T> {
	fields: Array<UpdateRestrictionsPart<T>>
}

export interface UpdateRestrictionsPart<T> {
	path: DataClassPathPartial<T>
	limitedIf: Condition<T>
	limitedTo: Condition<T>
}

export type Uuid = string  // kotlin.uuid.Uuid




export interface Api {
	index(): Promise<number>
	improperSDKFunctionName(): Promise<number>
	inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>

	readonly predefinedEndpoints: {
		preDefinedEndpoint(input: number): Promise<number>
	}
	readonly module: {
		testSdkEndpoint(first: string, input: TestInput): Promise<string>
		inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
		inlinedEndpoint2(id: Uuid, category: Uuid): Promise<number>
		list(input: Query<TestModel>): Promise<Array<TestModel>>
		insert(input: TestModel): Promise<TestModel>
		permissions(): Promise<ModelPermissions<TestModel>>
		queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
		groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		query(input: Query<TestModel>): Promise<Array<TestModel>>
		count(input: Condition<TestModel>): Promise<number>
		groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
		bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
		bulkModify(input: MassModification<TestModel>): Promise<number>
		groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		bulkDelete(input: Condition<TestModel>): Promise<number>
		aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
		detail(id: Uuid): Promise<TestModel>
		upsert(id: Uuid, input: TestModel): Promise<TestModel>
		replace(id: Uuid, input: TestModel): Promise<TestModel>
		modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
		delete(id: Uuid): Promise<void>
		simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
		modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

		readonly default: {
			testSdkEndpoint(second: string, input: TestInput): Promise<string>
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			insert(input: TestModel): Promise<TestModel>
			permissions(): Promise<ModelPermissions<TestModel>>
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			count(input: Condition<TestModel>): Promise<number>
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkModify(input: MassModification<TestModel>): Promise<number>
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			bulkDelete(input: Condition<TestModel>): Promise<number>
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			detail(id: Uuid): Promise<TestModel>
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			delete(id: Uuid): Promise<void>
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

			readonly notInlined: {
				inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
			}
		}
		readonly default2: {
			testSdkEndpoint(second: string, input: TestInput): Promise<string>
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			insert(input: TestModel): Promise<TestModel>
			permissions(): Promise<ModelPermissions<TestModel>>
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			count(input: Condition<TestModel>): Promise<number>
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkModify(input: MassModification<TestModel>): Promise<number>
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			bulkDelete(input: Condition<TestModel>): Promise<number>
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			detail(id: Uuid): Promise<TestModel>
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			delete(id: Uuid): Promise<void>
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

			readonly notInlined: {
				inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
			}
		}
	}
	readonly custom: {
		testSdkEndpoint(second: string, input: TestInput): Promise<string>
		list(input: Query<TestModel>): Promise<Array<TestModel>>
		insert(input: TestModel): Promise<TestModel>
		permissions(): Promise<ModelPermissions<TestModel>>
		queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
		groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		query(input: Query<TestModel>): Promise<Array<TestModel>>
		count(input: Condition<TestModel>): Promise<number>
		groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
		bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
		bulkModify(input: MassModification<TestModel>): Promise<number>
		groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		bulkDelete(input: Condition<TestModel>): Promise<number>
		aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
		detail(id: Uuid): Promise<TestModel>
		upsert(id: Uuid, input: TestModel): Promise<TestModel>
		replace(id: Uuid, input: TestModel): Promise<TestModel>
		modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
		delete(id: Uuid): Promise<void>
		simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
		modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

		readonly notInlined: {
			inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
		}
	}
	readonly other: {
		testSdkEndpoint(third: string, input: TestInput): Promise<string>
		inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>

		readonly rest: {
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			insert(input: TestModel): Promise<TestModel>
			permissions(): Promise<ModelPermissions<TestModel>>
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			count(input: Condition<TestModel>): Promise<number>
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkModify(input: MassModification<TestModel>): Promise<number>
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			bulkDelete(input: Condition<TestModel>): Promise<number>
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			detail(id: Uuid): Promise<TestModel>
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			delete(id: Uuid): Promise<void>
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>
		}
		readonly rest2: {
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			insert(input: TestModel): Promise<TestModel>
			permissions(): Promise<ModelPermissions<TestModel>>
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			count(input: Condition<TestModel>): Promise<number>
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			bulkModify(input: MassModification<TestModel>): Promise<number>
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			bulkDelete(input: Condition<TestModel>): Promise<number>
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			detail(id: Uuid): Promise<TestModel>
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			delete(id: Uuid): Promise<void>
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>
		}
	}
}



export class LiveApi implements Api {
	public constructor(public fetcher: Fetcher) {}

	index: () => this.fetcher(`/`, "GET", undefined),
	improperSDKFunctionName: () => this.fetcher(`/`, "POST", undefined),
	inlinedEndpoint: (id, category) => this.fetcher(`/inline/action/${id}/${category}`, "POST", undefined),

	readonly predefinedEndpoints: Api["predefinedEndpoints"] = {
		preDefinedEndpoint: (input) => this.fetcher(`/predefined/foo`, "POST", input),
	}
	readonly module: Api["module"] = {
		testSdkEndpoint: (first, input) => this.fetcher(`/m1/endpoint/${first}`, "POST", input),
		inlinedEndpoint: (id, category) => this.fetcher(`/m1/inline/again/action/${id}/${category}`, "POST", undefined),
		inlinedEndpoint2: (id, category) => this.fetcher(`/m1/inline/action/${id}/${category}`, "POST", undefined),
		list: (input) => this.fetcher(`/m1/rest`, "GET", input),
		insert: (input) => this.fetcher(`/m1/rest`, "POST", input),
		permissions: () => this.fetcher(`/m1/rest/_permissions_`, "GET", undefined),
		queryPartial: (input) => this.fetcher(`/m1/rest/query-partial`, "POST", input),
		groupAggregate2: (input) => this.fetcher(`/m1/rest/group-aggregate-2`, "POST", input),
		groupAggregate: (input) => this.fetcher(`/m1/rest/group-aggregate`, "POST", input),
		query: (input) => this.fetcher(`/m1/rest/query`, "POST", input),
		count: (input) => this.fetcher(`/m1/rest/count`, "POST", input),
		groupCount: (input) => this.fetcher(`/m1/rest/group-count`, "POST", input),
		insertBulk: (input) => this.fetcher(`/m1/rest/bulk`, "POST", input),
		bulkReplace: (input) => this.fetcher(`/m1/rest/bulk`, "PUT", input),
		bulkModify: (input) => this.fetcher(`/m1/rest/bulk`, "PATCH", input),
		groupCount2: (input) => this.fetcher(`/m1/rest/group-count-2`, "POST", input),
		bulkDelete: (input) => this.fetcher(`/m1/rest/bulk-delete`, "POST", input),
		aggregate: (input) => this.fetcher(`/m1/rest/aggregate`, "POST", input),
		detail: (id) => this.fetcher(`/m1/rest/${id}`, "GET", undefined),
		upsert: (id, input) => this.fetcher(`/m1/rest/${id}`, "POST", input),
		replace: (id, input) => this.fetcher(`/m1/rest/${id}`, "PUT", input),
		modify: (id, input) => this.fetcher(`/m1/rest/${id}`, "PATCH", input),
		delete: (id) => this.fetcher(`/m1/rest/${id}`, "DELETE", undefined),
		simplifiedModify: (id, input) => this.fetcher(`/m1/rest/${id}/simplified`, "PATCH", input),
		modifyWithDiff: (id, input) => this.fetcher(`/m1/rest/${id}/delta`, "PATCH", input),

		readonly default: Api["module"]["default"] = {
			testSdkEndpoint: (second, input) => this.fetcher(`/m1/second/endpoint/${second}`, "POST", input),
			list: (input) => this.fetcher(`/m1/second/rest`, "GET", input),
			insert: (input) => this.fetcher(`/m1/second/rest`, "POST", input),
			permissions: () => this.fetcher(`/m1/second/rest/_permissions_`, "GET", undefined),
			queryPartial: (input) => this.fetcher(`/m1/second/rest/query-partial`, "POST", input),
			groupAggregate2: (input) => this.fetcher(`/m1/second/rest/group-aggregate-2`, "POST", input),
			groupAggregate: (input) => this.fetcher(`/m1/second/rest/group-aggregate`, "POST", input),
			query: (input) => this.fetcher(`/m1/second/rest/query`, "POST", input),
			count: (input) => this.fetcher(`/m1/second/rest/count`, "POST", input),
			groupCount: (input) => this.fetcher(`/m1/second/rest/group-count`, "POST", input),
			insertBulk: (input) => this.fetcher(`/m1/second/rest/bulk`, "POST", input),
			bulkReplace: (input) => this.fetcher(`/m1/second/rest/bulk`, "PUT", input),
			bulkModify: (input) => this.fetcher(`/m1/second/rest/bulk`, "PATCH", input),
			groupCount2: (input) => this.fetcher(`/m1/second/rest/group-count-2`, "POST", input),
			bulkDelete: (input) => this.fetcher(`/m1/second/rest/bulk-delete`, "POST", input),
			aggregate: (input) => this.fetcher(`/m1/second/rest/aggregate`, "POST", input),
			detail: (id) => this.fetcher(`/m1/second/rest/${id}`, "GET", undefined),
			upsert: (id, input) => this.fetcher(`/m1/second/rest/${id}`, "POST", input),
			replace: (id, input) => this.fetcher(`/m1/second/rest/${id}`, "PUT", input),
			modify: (id, input) => this.fetcher(`/m1/second/rest/${id}`, "PATCH", input),
			delete: (id) => this.fetcher(`/m1/second/rest/${id}`, "DELETE", undefined),
			simplifiedModify: (id, input) => this.fetcher(`/m1/second/rest/${id}/simplified`, "PATCH", input),
			modifyWithDiff: (id, input) => this.fetcher(`/m1/second/rest/${id}/delta`, "PATCH", input),

			readonly notInlined: Api["module"]["default"]["notInlined"] = {
				inlinedEndpoint: (id, category) => this.fetcher(`/m1/second/noinline/action/${id}/${category}`, "POST", undefined),
			}
		}
		readonly default2: Api["module"]["default2"] = {
			testSdkEndpoint: (second, input) => this.fetcher(`/m1/duplicate/endpoint/${second}`, "POST", input),
			list: (input) => this.fetcher(`/m1/duplicate/rest`, "GET", input),
			insert: (input) => this.fetcher(`/m1/duplicate/rest`, "POST", input),
			permissions: () => this.fetcher(`/m1/duplicate/rest/_permissions_`, "GET", undefined),
			queryPartial: (input) => this.fetcher(`/m1/duplicate/rest/query-partial`, "POST", input),
			groupAggregate2: (input) => this.fetcher(`/m1/duplicate/rest/group-aggregate-2`, "POST", input),
			groupAggregate: (input) => this.fetcher(`/m1/duplicate/rest/group-aggregate`, "POST", input),
			query: (input) => this.fetcher(`/m1/duplicate/rest/query`, "POST", input),
			count: (input) => this.fetcher(`/m1/duplicate/rest/count`, "POST", input),
			groupCount: (input) => this.fetcher(`/m1/duplicate/rest/group-count`, "POST", input),
			insertBulk: (input) => this.fetcher(`/m1/duplicate/rest/bulk`, "POST", input),
			bulkReplace: (input) => this.fetcher(`/m1/duplicate/rest/bulk`, "PUT", input),
			bulkModify: (input) => this.fetcher(`/m1/duplicate/rest/bulk`, "PATCH", input),
			groupCount2: (input) => this.fetcher(`/m1/duplicate/rest/group-count-2`, "POST", input),
			bulkDelete: (input) => this.fetcher(`/m1/duplicate/rest/bulk-delete`, "POST", input),
			aggregate: (input) => this.fetcher(`/m1/duplicate/rest/aggregate`, "POST", input),
			detail: (id) => this.fetcher(`/m1/duplicate/rest/${id}`, "GET", undefined),
			upsert: (id, input) => this.fetcher(`/m1/duplicate/rest/${id}`, "POST", input),
			replace: (id, input) => this.fetcher(`/m1/duplicate/rest/${id}`, "PUT", input),
			modify: (id, input) => this.fetcher(`/m1/duplicate/rest/${id}`, "PATCH", input),
			delete: (id) => this.fetcher(`/m1/duplicate/rest/${id}`, "DELETE", undefined),
			simplifiedModify: (id, input) => this.fetcher(`/m1/duplicate/rest/${id}/simplified`, "PATCH", input),
			modifyWithDiff: (id, input) => this.fetcher(`/m1/duplicate/rest/${id}/delta`, "PATCH", input),

			readonly notInlined: Api["module"]["default2"]["notInlined"] = {
				inlinedEndpoint: (id, category) => this.fetcher(`/m1/duplicate/noinline/action/${id}/${category}`, "POST", undefined),
			}
		}
	}
	readonly custom: Api["custom"] = {
		testSdkEndpoint: (second, input) => this.fetcher(`/m2/endpoint/${second}`, "POST", input),
		list: (input) => this.fetcher(`/m2/rest`, "GET", input),
		insert: (input) => this.fetcher(`/m2/rest`, "POST", input),
		permissions: () => this.fetcher(`/m2/rest/_permissions_`, "GET", undefined),
		queryPartial: (input) => this.fetcher(`/m2/rest/query-partial`, "POST", input),
		groupAggregate2: (input) => this.fetcher(`/m2/rest/group-aggregate-2`, "POST", input),
		groupAggregate: (input) => this.fetcher(`/m2/rest/group-aggregate`, "POST", input),
		query: (input) => this.fetcher(`/m2/rest/query`, "POST", input),
		count: (input) => this.fetcher(`/m2/rest/count`, "POST", input),
		groupCount: (input) => this.fetcher(`/m2/rest/group-count`, "POST", input),
		insertBulk: (input) => this.fetcher(`/m2/rest/bulk`, "POST", input),
		bulkReplace: (input) => this.fetcher(`/m2/rest/bulk`, "PUT", input),
		bulkModify: (input) => this.fetcher(`/m2/rest/bulk`, "PATCH", input),
		groupCount2: (input) => this.fetcher(`/m2/rest/group-count-2`, "POST", input),
		bulkDelete: (input) => this.fetcher(`/m2/rest/bulk-delete`, "POST", input),
		aggregate: (input) => this.fetcher(`/m2/rest/aggregate`, "POST", input),
		detail: (id) => this.fetcher(`/m2/rest/${id}`, "GET", undefined),
		upsert: (id, input) => this.fetcher(`/m2/rest/${id}`, "POST", input),
		replace: (id, input) => this.fetcher(`/m2/rest/${id}`, "PUT", input),
		modify: (id, input) => this.fetcher(`/m2/rest/${id}`, "PATCH", input),
		delete: (id) => this.fetcher(`/m2/rest/${id}`, "DELETE", undefined),
		simplifiedModify: (id, input) => this.fetcher(`/m2/rest/${id}/simplified`, "PATCH", input),
		modifyWithDiff: (id, input) => this.fetcher(`/m2/rest/${id}/delta`, "PATCH", input),

		readonly notInlined: Api["custom"]["notInlined"] = {
			inlinedEndpoint: (id, category) => this.fetcher(`/m2/noinline/action/${id}/${category}`, "POST", undefined),
		}
	}
	readonly other: Api["other"] = {
		testSdkEndpoint: (third, input) => this.fetcher(`/third/endpoint/${third}`, "POST", input),
		inlinedEndpoint: (id, category) => this.fetcher(`/third/inline/action/${id}/${category}`, "POST", undefined),

		readonly rest: Api["other"]["rest"] = {
			list: (input) => this.fetcher(`/third/rest`, "GET", input),
			insert: (input) => this.fetcher(`/third/rest`, "POST", input),
			permissions: () => this.fetcher(`/third/rest/_permissions_`, "GET", undefined),
			queryPartial: (input) => this.fetcher(`/third/rest/query-partial`, "POST", input),
			groupAggregate2: (input) => this.fetcher(`/third/rest/group-aggregate-2`, "POST", input),
			groupAggregate: (input) => this.fetcher(`/third/rest/group-aggregate`, "POST", input),
			query: (input) => this.fetcher(`/third/rest/query`, "POST", input),
			count: (input) => this.fetcher(`/third/rest/count`, "POST", input),
			groupCount: (input) => this.fetcher(`/third/rest/group-count`, "POST", input),
			insertBulk: (input) => this.fetcher(`/third/rest/bulk`, "POST", input),
			bulkReplace: (input) => this.fetcher(`/third/rest/bulk`, "PUT", input),
			bulkModify: (input) => this.fetcher(`/third/rest/bulk`, "PATCH", input),
			groupCount2: (input) => this.fetcher(`/third/rest/group-count-2`, "POST", input),
			bulkDelete: (input) => this.fetcher(`/third/rest/bulk-delete`, "POST", input),
			aggregate: (input) => this.fetcher(`/third/rest/aggregate`, "POST", input),
			detail: (id) => this.fetcher(`/third/rest/${id}`, "GET", undefined),
			upsert: (id, input) => this.fetcher(`/third/rest/${id}`, "POST", input),
			replace: (id, input) => this.fetcher(`/third/rest/${id}`, "PUT", input),
			modify: (id, input) => this.fetcher(`/third/rest/${id}`, "PATCH", input),
			delete: (id) => this.fetcher(`/third/rest/${id}`, "DELETE", undefined),
			simplifiedModify: (id, input) => this.fetcher(`/third/rest/${id}/simplified`, "PATCH", input),
			modifyWithDiff: (id, input) => this.fetcher(`/third/rest/${id}/delta`, "PATCH", input),
		}
		readonly rest2: Api["other"]["rest2"] = {
			list: (input) => this.fetcher(`/third/rest2`, "GET", input),
			insert: (input) => this.fetcher(`/third/rest2`, "POST", input),
			permissions: () => this.fetcher(`/third/rest2/_permissions_`, "GET", undefined),
			queryPartial: (input) => this.fetcher(`/third/rest2/query-partial`, "POST", input),
			groupAggregate2: (input) => this.fetcher(`/third/rest2/group-aggregate-2`, "POST", input),
			groupAggregate: (input) => this.fetcher(`/third/rest2/group-aggregate`, "POST", input),
			query: (input) => this.fetcher(`/third/rest2/query`, "POST", input),
			count: (input) => this.fetcher(`/third/rest2/count`, "POST", input),
			groupCount: (input) => this.fetcher(`/third/rest2/group-count`, "POST", input),
			insertBulk: (input) => this.fetcher(`/third/rest2/bulk`, "POST", input),
			bulkReplace: (input) => this.fetcher(`/third/rest2/bulk`, "PUT", input),
			bulkModify: (input) => this.fetcher(`/third/rest2/bulk`, "PATCH", input),
			groupCount2: (input) => this.fetcher(`/third/rest2/group-count-2`, "POST", input),
			bulkDelete: (input) => this.fetcher(`/third/rest2/bulk-delete`, "POST", input),
			aggregate: (input) => this.fetcher(`/third/rest2/aggregate`, "POST", input),
			detail: (id) => this.fetcher(`/third/rest2/${id}`, "GET", undefined),
			upsert: (id, input) => this.fetcher(`/third/rest2/${id}`, "POST", input),
			replace: (id, input) => this.fetcher(`/third/rest2/${id}`, "PUT", input),
			modify: (id, input) => this.fetcher(`/third/rest2/${id}`, "PATCH", input),
			delete: (id) => this.fetcher(`/third/rest2/${id}`, "DELETE", undefined),
			simplifiedModify: (id, input) => this.fetcher(`/third/rest2/${id}/simplified`, "PATCH", input),
			modifyWithDiff: (id, input) => this.fetcher(`/third/rest2/${id}/delta`, "PATCH", input),
		}
	}
}
