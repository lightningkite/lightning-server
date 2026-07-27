import type { Query, MassModification, EntryChange, ListChange, Modification, Condition, GroupCountQuery, AggregateQuery, GroupAggregateQuery, Aggregate, SortPart, DataClassPath, DataClassPathPartial, QueryPartial, DeepPartial, Fetcher } from '@lightningkite/lightning-server-simplified'
import type { CollectionUpdates, Mask, ModelPermissions, Pair, TestInput, TestModel, UpdateRestrictions, Uuid } from './models.ts'

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
