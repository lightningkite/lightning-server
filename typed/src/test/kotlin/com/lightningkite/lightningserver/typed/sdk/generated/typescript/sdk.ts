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
	/**
	 * Index
	 * 
	 * **Auth Requirements:** No Requirements
	 * */
	index(): Promise<number>
	/**
	 * Action
	 * 
	 * Does something really really cool...
	 * 
	 * **Auth Requirements:** User *or* No Requirements
	 * */
	improperSDKFunctionName(): Promise<number>
	/**
	 * Inlined Endpoint
	 * 
	 * This endpoint is sometimes inlined, sometimes not.
	 * 
	 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with root access and an additional requirement)
	 * */
	inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>

	readonly predefinedEndpoints: {
		/**
		 * Pre-Defined Endpoint
		 * 
		 * This is an endpoint included through a pre-build definition
		 * 
		 * **Auth Requirements:** User with scope pre:defined *or* User with scope foo *or* IsSuperUser (User with root access and an additional requirement)
		 * */
		preDefinedEndpoint(input: number): Promise<number>
	}
	readonly module: {
		/**
		 * Test Endpoint
		 * 
		 * This is a test endpoint for the sdk
		 * 
		 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with root access and an additional requirement)
		 * */
		testSdkEndpoint(first: string, input: TestInput): Promise<string>
		/**
		 * Inlined Endpoint
		 * 
		 * This endpoint is sometimes inlined, sometimes not.
		 * 
		 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with root access and an additional requirement)
		 * */
		inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
		/**
		 * Inlined Endpoint
		 * 
		 * This endpoint is sometimes inlined, sometimes not.
		 * 
		 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with root access and an additional requirement)
		 * */
		inlinedEndpoint2(id: Uuid, category: Uuid): Promise<number>
		/**
		 * List
		 * 
		 * Gets a list of TestModels.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		list(input: Query<TestModel>): Promise<Array<TestModel>>
		/**
		 * Insert
		 * 
		 * Creates a new TestModel
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		insert(input: TestModel): Promise<TestModel>
		/**
		 * Permissions
		 * 
		 * Returns the user's permissions for this collection.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		permissions(): Promise<ModelPermissions<TestModel>>
		/**
		 * QueryPartial
		 * 
		 * Gets parts of TestModels that match the given query.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
		/**
		 * Group Aggregate 2
		 * 
		 * Aggregates a property of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		/**
		 * Group Aggregate
		 * 
		 * Aggregates a property of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		/**
		 * Query
		 * 
		 * Gets a list of TestModels that match the given query.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		query(input: Query<TestModel>): Promise<Array<TestModel>>
		/**
		 * Count
		 * 
		 * Gets the total number of TestModels matching the given condition.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		count(input: Condition<TestModel>): Promise<number>
		/**
		 * Group Count
		 * 
		 * Gets the total number of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		/**
		 * Insert Bulk
		 * 
		 * Creates multiple TestModels at the same time.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
		/**
		 * Bulk Replace
		 * 
		 * Modifies many TestModels at the same time by ID.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
		/**
		 * Bulk Modify
		 * 
		 * Modifies many TestModels at the same time. Returns the number of changed items.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		bulkModify(input: MassModification<TestModel>): Promise<number>
		/**
		 * Group Count 2
		 * 
		 * Gets the total number of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		/**
		 * Bulk Delete
		 * 
		 * Deletes all matching TestModels, returning the number of deleted items.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		bulkDelete(input: Condition<TestModel>): Promise<number>
		/**
		 * Aggregate
		 * 
		 * Aggregates a property of TestModels matching the given condition.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
		/**
		 * Detail
		 * 
		 * Gets the TestModel for the provided id.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		detail(id: Uuid): Promise<TestModel>
		/**
		 * Upsert
		 * 
		 * Creates or updates a TestModel
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		upsert(id: Uuid, input: TestModel): Promise<TestModel>
		/**
		 * Replace
		 * 
		 * Replaces a single TestModel by ID.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		replace(id: Uuid, input: TestModel): Promise<TestModel>
		/**
		 * Modify
		 * 
		 * Modifies a TestModel by ID, returning the new value.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
		/**
		 * Delete
		 * 
		 * Deletes a TestModel by id.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		delete(id: Uuid): Promise<void>
		/**
		 * Simplified Modify
		 * 
		 * Modifies a TestModel by ID, returning the new value.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
		/**
		 * Modify with Diff
		 * 
		 * Modifies a TestModel by ID, returning both the previous value and new value.
		 * 
		 * **Auth Requirements:** No Requirements
		 * */
		modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

		readonly default: {
			/**
			 * Test Endpoint
			 * 
			 * This is a test endpoint for the sdk
			 * 
			 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with root access and an additional requirement)
			 * */
			testSdkEndpoint(second: string, input: TestInput): Promise<string>
			/**
			 * List
			 * 
			 * Gets a list of TestModels.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Insert
			 * 
			 * Creates a new TestModel
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			insert(input: TestModel): Promise<TestModel>
			/**
			 * Permissions
			 * 
			 * Returns the user's permissions for this collection.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			permissions(): Promise<ModelPermissions<TestModel>>
			/**
			 * QueryPartial
			 * 
			 * Gets parts of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			/**
			 * Group Aggregate 2
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Group Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Query
			 * 
			 * Gets a list of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Count
			 * 
			 * Gets the total number of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			count(input: Condition<TestModel>): Promise<number>
			/**
			 * Group Count
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Insert Bulk
			 * 
			 * Creates multiple TestModels at the same time.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Replace
			 * 
			 * Modifies many TestModels at the same time by ID.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Modify
			 * 
			 * Modifies many TestModels at the same time. Returns the number of changed items.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			bulkModify(input: MassModification<TestModel>): Promise<number>
			/**
			 * Group Count 2
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Bulk Delete
			 * 
			 * Deletes all matching TestModels, returning the number of deleted items.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			bulkDelete(input: Condition<TestModel>): Promise<number>
			/**
			 * Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			/**
			 * Detail
			 * 
			 * Gets the TestModel for the provided id.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			detail(id: Uuid): Promise<TestModel>
			/**
			 * Upsert
			 * 
			 * Creates or updates a TestModel
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Replace
			 * 
			 * Replaces a single TestModel by ID.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			/**
			 * Delete
			 * 
			 * Deletes a TestModel by id.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			delete(id: Uuid): Promise<void>
			/**
			 * Simplified Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			/**
			 * Modify with Diff
			 * 
			 * Modifies a TestModel by ID, returning both the previous value and new value.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

			readonly notInlined: {
				/**
				 * Inlined Endpoint
				 * 
				 * This endpoint is sometimes inlined, sometimes not.
				 * 
				 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with root access and an additional requirement)
				 * */
				inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
			}
		}
		readonly default2: {
			/**
			 * Test Endpoint
			 * 
			 * This is a test endpoint for the sdk
			 * 
			 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with root access and an additional requirement)
			 * */
			testSdkEndpoint(second: string, input: TestInput): Promise<string>
			/**
			 * List
			 * 
			 * Gets a list of TestModels.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Insert
			 * 
			 * Creates a new TestModel
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			insert(input: TestModel): Promise<TestModel>
			/**
			 * Permissions
			 * 
			 * Returns the user's permissions for this collection.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			permissions(): Promise<ModelPermissions<TestModel>>
			/**
			 * QueryPartial
			 * 
			 * Gets parts of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			/**
			 * Group Aggregate 2
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Group Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Query
			 * 
			 * Gets a list of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Count
			 * 
			 * Gets the total number of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			count(input: Condition<TestModel>): Promise<number>
			/**
			 * Group Count
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Insert Bulk
			 * 
			 * Creates multiple TestModels at the same time.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Replace
			 * 
			 * Modifies many TestModels at the same time by ID.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Modify
			 * 
			 * Modifies many TestModels at the same time. Returns the number of changed items.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			bulkModify(input: MassModification<TestModel>): Promise<number>
			/**
			 * Group Count 2
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Bulk Delete
			 * 
			 * Deletes all matching TestModels, returning the number of deleted items.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			bulkDelete(input: Condition<TestModel>): Promise<number>
			/**
			 * Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			/**
			 * Detail
			 * 
			 * Gets the TestModel for the provided id.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			detail(id: Uuid): Promise<TestModel>
			/**
			 * Upsert
			 * 
			 * Creates or updates a TestModel
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Replace
			 * 
			 * Replaces a single TestModel by ID.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			/**
			 * Delete
			 * 
			 * Deletes a TestModel by id.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			delete(id: Uuid): Promise<void>
			/**
			 * Simplified Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			/**
			 * Modify with Diff
			 * 
			 * Modifies a TestModel by ID, returning both the previous value and new value.
			 * 
			 * **Auth Requirements:** Authenticated
			 * */
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

			readonly notInlined: {
				/**
				 * Inlined Endpoint
				 * 
				 * This endpoint is sometimes inlined, sometimes not.
				 * 
				 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with root access and an additional requirement)
				 * */
				inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
			}
		}
	}
	readonly custom: {
		/**
		 * Test Endpoint
		 * 
		 * This is a test endpoint for the sdk
		 * 
		 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with root access and an additional requirement)
		 * */
		testSdkEndpoint(second: string, input: TestInput): Promise<string>
		/**
		 * List
		 * 
		 * Gets a list of TestModels.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		list(input: Query<TestModel>): Promise<Array<TestModel>>
		/**
		 * Insert
		 * 
		 * Creates a new TestModel
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		insert(input: TestModel): Promise<TestModel>
		/**
		 * Permissions
		 * 
		 * Returns the user's permissions for this collection.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		permissions(): Promise<ModelPermissions<TestModel>>
		/**
		 * QueryPartial
		 * 
		 * Gets parts of TestModels that match the given query.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
		/**
		 * Group Aggregate 2
		 * 
		 * Aggregates a property of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		/**
		 * Group Aggregate
		 * 
		 * Aggregates a property of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
		/**
		 * Query
		 * 
		 * Gets a list of TestModels that match the given query.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		query(input: Query<TestModel>): Promise<Array<TestModel>>
		/**
		 * Count
		 * 
		 * Gets the total number of TestModels matching the given condition.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		count(input: Condition<TestModel>): Promise<number>
		/**
		 * Group Count
		 * 
		 * Gets the total number of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		/**
		 * Insert Bulk
		 * 
		 * Creates multiple TestModels at the same time.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
		/**
		 * Bulk Replace
		 * 
		 * Modifies many TestModels at the same time by ID.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
		/**
		 * Bulk Modify
		 * 
		 * Modifies many TestModels at the same time. Returns the number of changed items.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		bulkModify(input: MassModification<TestModel>): Promise<number>
		/**
		 * Group Count 2
		 * 
		 * Gets the total number of TestModels matching the given condition divided by group.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
		/**
		 * Bulk Delete
		 * 
		 * Deletes all matching TestModels, returning the number of deleted items.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		bulkDelete(input: Condition<TestModel>): Promise<number>
		/**
		 * Aggregate
		 * 
		 * Aggregates a property of TestModels matching the given condition.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
		/**
		 * Detail
		 * 
		 * Gets the TestModel for the provided id.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		detail(id: Uuid): Promise<TestModel>
		/**
		 * Upsert
		 * 
		 * Creates or updates a TestModel
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		upsert(id: Uuid, input: TestModel): Promise<TestModel>
		/**
		 * Replace
		 * 
		 * Replaces a single TestModel by ID.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		replace(id: Uuid, input: TestModel): Promise<TestModel>
		/**
		 * Modify
		 * 
		 * Modifies a TestModel by ID, returning the new value.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
		/**
		 * Delete
		 * 
		 * Deletes a TestModel by id.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		delete(id: Uuid): Promise<void>
		/**
		 * Simplified Modify
		 * 
		 * Modifies a TestModel by ID, returning the new value.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
		/**
		 * Modify with Diff
		 * 
		 * Modifies a TestModel by ID, returning both the previous value and new value.
		 * 
		 * **Auth Requirements:** Authenticated
		 * */
		modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>

		readonly notInlined: {
			/**
			 * Inlined Endpoint
			 * 
			 * This endpoint is sometimes inlined, sometimes not.
			 * 
			 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with root access and an additional requirement)
			 * */
			inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>
		}
	}
	readonly other: {
		/**
		 * Test Endpoint
		 * 
		 * This is a test endpoint for the sdk
		 * 
		 * **Auth Requirements:** Authenticated with scopes [[sdk:test, sdk:other]] and max age of 8h *or* IsSuperUser (User with root access and an additional requirement)
		 * */
		testSdkEndpoint(third: string, input: TestInput): Promise<string>
		/**
		 * Inlined Endpoint
		 * 
		 * This endpoint is sometimes inlined, sometimes not.
		 * 
		 * **Auth Requirements:** IsAdmin *or* IsSuperUser (User with root access and an additional requirement)
		 * */
		inlinedEndpoint(id: Uuid, category: Uuid): Promise<number>

		readonly rest: {
			/**
			 * List
			 * 
			 * Gets a list of TestModels.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Insert
			 * 
			 * Creates a new TestModel
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			insert(input: TestModel): Promise<TestModel>
			/**
			 * Permissions
			 * 
			 * Returns the user's permissions for this collection.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			permissions(): Promise<ModelPermissions<TestModel>>
			/**
			 * QueryPartial
			 * 
			 * Gets parts of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			/**
			 * Group Aggregate 2
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Group Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Query
			 * 
			 * Gets a list of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Count
			 * 
			 * Gets the total number of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			count(input: Condition<TestModel>): Promise<number>
			/**
			 * Group Count
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Insert Bulk
			 * 
			 * Creates multiple TestModels at the same time.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Replace
			 * 
			 * Modifies many TestModels at the same time by ID.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Modify
			 * 
			 * Modifies many TestModels at the same time. Returns the number of changed items.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			bulkModify(input: MassModification<TestModel>): Promise<number>
			/**
			 * Group Count 2
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Bulk Delete
			 * 
			 * Deletes all matching TestModels, returning the number of deleted items.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			bulkDelete(input: Condition<TestModel>): Promise<number>
			/**
			 * Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			/**
			 * Detail
			 * 
			 * Gets the TestModel for the provided id.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			detail(id: Uuid): Promise<TestModel>
			/**
			 * Upsert
			 * 
			 * Creates or updates a TestModel
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Replace
			 * 
			 * Replaces a single TestModel by ID.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			/**
			 * Delete
			 * 
			 * Deletes a TestModel by id.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			delete(id: Uuid): Promise<void>
			/**
			 * Simplified Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			/**
			 * Modify with Diff
			 * 
			 * Modifies a TestModel by ID, returning both the previous value and new value.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>
		}
		readonly rest2: {
			/**
			 * List
			 * 
			 * Gets a list of TestModels.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			list(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Insert
			 * 
			 * Creates a new TestModel
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			insert(input: TestModel): Promise<TestModel>
			/**
			 * Permissions
			 * 
			 * Returns the user's permissions for this collection.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			permissions(): Promise<ModelPermissions<TestModel>>
			/**
			 * QueryPartial
			 * 
			 * Gets parts of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			queryPartial(input: QueryPartial<TestModel>): Promise<Array<Partial<TestModel>>>
			/**
			 * Group Aggregate 2
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupAggregate2(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Group Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupAggregate(input: GroupAggregateQuery<TestModel>): Promise<Record<string, number | null | undefined>>
			/**
			 * Query
			 * 
			 * Gets a list of TestModels that match the given query.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			query(input: Query<TestModel>): Promise<Array<TestModel>>
			/**
			 * Count
			 * 
			 * Gets the total number of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			count(input: Condition<TestModel>): Promise<number>
			/**
			 * Group Count
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupCount(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Insert Bulk
			 * 
			 * Creates multiple TestModels at the same time.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			insertBulk(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Replace
			 * 
			 * Modifies many TestModels at the same time by ID.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			bulkReplace(input: Array<TestModel>): Promise<Array<TestModel>>
			/**
			 * Bulk Modify
			 * 
			 * Modifies many TestModels at the same time. Returns the number of changed items.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			bulkModify(input: MassModification<TestModel>): Promise<number>
			/**
			 * Group Count 2
			 * 
			 * Gets the total number of TestModels matching the given condition divided by group.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			groupCount2(input: GroupCountQuery<TestModel>): Promise<Record<string, number>>
			/**
			 * Bulk Delete
			 * 
			 * Deletes all matching TestModels, returning the number of deleted items.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			bulkDelete(input: Condition<TestModel>): Promise<number>
			/**
			 * Aggregate
			 * 
			 * Aggregates a property of TestModels matching the given condition.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			aggregate(input: AggregateQuery<TestModel>): Promise<number | null | undefined>
			/**
			 * Detail
			 * 
			 * Gets the TestModel for the provided id.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			detail(id: Uuid): Promise<TestModel>
			/**
			 * Upsert
			 * 
			 * Creates or updates a TestModel
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			upsert(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Replace
			 * 
			 * Replaces a single TestModel by ID.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			replace(id: Uuid, input: TestModel): Promise<TestModel>
			/**
			 * Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			modify(id: Uuid, input: Modification<TestModel>): Promise<TestModel>
			/**
			 * Delete
			 * 
			 * Deletes a TestModel by id.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			delete(id: Uuid): Promise<void>
			/**
			 * Simplified Modify
			 * 
			 * Modifies a TestModel by ID, returning the new value.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			simplifiedModify(id: Uuid, input: Partial<TestModel>): Promise<TestModel>
			/**
			 * Modify with Diff
			 * 
			 * Modifies a TestModel by ID, returning both the previous value and new value.
			 * 
			 * **Auth Requirements:** No Requirements
			 * */
			modifyWithDiff(id: Uuid, input: Modification<TestModel>): Promise<EntryChange<TestModel>>
		}
	}
}



export class LiveApi implements Api {
	public constructor(public fetcher: Fetcher) {}

	index: Api["index"] = () => this.fetcher(`/`, "GET", undefined)
	improperSDKFunctionName: Api["improperSDKFunctionName"] = () => this.fetcher(`/`, "POST", undefined)
	inlinedEndpoint: Api["inlinedEndpoint"] = (id, category) => this.fetcher(`/inline/action/${id}/${category}`, "POST", undefined)

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

		default: {
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

			notInlined: {
				inlinedEndpoint: (id, category) => this.fetcher(`/m1/second/noinline/action/${id}/${category}`, "POST", undefined),
			},
		},
		default2: {
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

			notInlined: {
				inlinedEndpoint: (id, category) => this.fetcher(`/m1/duplicate/noinline/action/${id}/${category}`, "POST", undefined),
			},
		},
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

		notInlined: {
			inlinedEndpoint: (id, category) => this.fetcher(`/m2/noinline/action/${id}/${category}`, "POST", undefined),
		},
	}
	readonly other: Api["other"] = {
		testSdkEndpoint: (third, input) => this.fetcher(`/third/endpoint/${third}`, "POST", input),
		inlinedEndpoint: (id, category) => this.fetcher(`/third/inline/action/${id}/${category}`, "POST", undefined),

		rest: {
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
		},
		rest2: {
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
		},
	}
}
