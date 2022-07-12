import {
    Aggregate,
    AggregateQuery,
    apiCall,
    Condition,
    EntryChange,
    GroupAggregateQuery,
    GroupCountQuery,
    MassModification,
    Modification,
    Path,
    Query
} from 'index'

export interface TestModel {
    _id: string
    timestamp: string
    name: string
    number: number
    content: string
    file: string | null | undefined
}
export interface User {
    _id: string
    email: string
}



export interface Api {
    readonly auth: {
        emailLoginLink(input: string): Promise<void>
        refreshToken(userToken: string): Promise<string>
        getSelf(userToken: string): Promise<User>
    }
    readonly testModel: {
        query(userToken: string, input: Query<TestModel>, files?: Record<Path<Query<TestModel>>, File>): Promise<Array<TestModel>>
        detail(userToken: string, id: string): Promise<TestModel>
        insertBulk(userToken: string, input: Array<TestModel>, files?: Record<Path<Array<TestModel>>, File>): Promise<Array<TestModel>>
        insert(userToken: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel>
        upsert(userToken: string, id: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel>
        bulkReplace(userToken: string, input: Array<TestModel>, files?: Record<Path<Array<TestModel>>, File>): Promise<Array<TestModel>>
        replace(userToken: string, id: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel>
        bulkModify(userToken: string, input: MassModification<TestModel>, files?: Record<Path<MassModification<TestModel>>, File>): Promise<number>
        modify(userToken: string, id: string, input: Modification<TestModel>, files?: Record<Path<Modification<TestModel>>, File>): Promise<EntryChange<TestModel>>
        modifyWithDiff(userToken: string, id: string, input: Modification<TestModel>, files?: Record<Path<Modification<TestModel>>, File>): Promise<TestModel>
        bulkDelete(userToken: string, input: Condition<TestModel>, files?: Record<Path<Condition<TestModel>>, File>): Promise<number>
        delete(userToken: string, id: string): Promise<void>
        count(userToken: string, input: Condition<TestModel>, files?: Record<Path<Condition<TestModel>>, File>): Promise<number>
        groupCount(userToken: string, input: GroupCountQuery<TestModel>, files?: Record<Path<GroupCountQuery<TestModel>>, File>): Promise<Record<string, number>>
        aggregate(userToken: string, input: AggregateQuery<TestModel>, files?: Record<Path<AggregateQuery<TestModel>>, File>): Promise<number | null | undefined>
        groupAggregate(userToken: string, input: GroupAggregateQuery<TestModel>, files?: Record<Path<GroupAggregateQuery<TestModel>>, File>): Promise<Record<string, number | null | undefined>>
    }
    getTestPrimitive(userToken: string): Promise<string>
}



export class UserSession {
    constructor(public api: Api, public userToken: string) {}
    getTestPrimitive(): Promise<string> { return this.api.getTestPrimitive(this.userToken) }
    readonly auth = {
        api: this.api,
        userToken: this.userToken,
        refreshToken(): Promise<string> { return this.api.auth.refreshToken(this.userToken) },
        getSelf(): Promise<User> { return this.api.auth.getSelf(this.userToken) },
        emailLoginLink(input: string): Promise<void> { return this.api.auth.emailLoginLink(input) },
    }
    readonly testModel = {
        api: this.api,
        userToken: this.userToken,
        query(input: Query<TestModel>, files?: Record<Path<Query<TestModel>>, File>): Promise<Array<TestModel>> { return this.api.testModel.query(this.userToken, input, files) },
        detail(id: string): Promise<TestModel> { return this.api.testModel.detail(this.userToken, id) },
        insertBulk(input: Array<TestModel>, files?: Record<Path<Array<TestModel>>, File>): Promise<Array<TestModel>> { return this.api.testModel.insertBulk(this.userToken, input, files) },
        insert(input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel> { return this.api.testModel.insert(this.userToken, input, files) },
        upsert(id: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel> { return this.api.testModel.upsert(this.userToken, id, input, files) },
        bulkReplace(input: Array<TestModel>, files?: Record<Path<Array<TestModel>>, File>): Promise<Array<TestModel>> { return this.api.testModel.bulkReplace(this.userToken, input, files) },
        replace(id: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel> { return this.api.testModel.replace(this.userToken, id, input, files) },
        bulkModify(input: MassModification<TestModel>, files?: Record<Path<MassModification<TestModel>>, File>): Promise<number> { return this.api.testModel.bulkModify(this.userToken, input, files) },
        modify(id: string, input: Modification<TestModel>, files?: Record<Path<Modification<TestModel>>, File>): Promise<EntryChange<TestModel>> { return this.api.testModel.modify(this.userToken, id, input, files) },
        modifyWithDiff(id: string, input: Modification<TestModel>, files?: Record<Path<Modification<TestModel>>, File>): Promise<TestModel> { return this.api.testModel.modifyWithDiff(this.userToken, id, input, files) },
        bulkDelete(input: Condition<TestModel>, files?: Record<Path<Condition<TestModel>>, File>): Promise<number> { return this.api.testModel.bulkDelete(this.userToken, input, files) },
        delete(id: string): Promise<void> { return this.api.testModel.delete(this.userToken, id) },
        count(input: Condition<TestModel>, files?: Record<Path<Condition<TestModel>>, File>): Promise<number> { return this.api.testModel.count(this.userToken, input, files) },
        groupCount(input: GroupCountQuery<TestModel>, files?: Record<Path<GroupCountQuery<TestModel>>, File>): Promise<Record<string, number>> { return this.api.testModel.groupCount(this.userToken, input, files) },
        aggregate(input: AggregateQuery<TestModel>, files?: Record<Path<AggregateQuery<TestModel>>, File>): Promise<number | null | undefined> { return this.api.testModel.aggregate(this.userToken, input, files) },
        groupAggregate(input: GroupAggregateQuery<TestModel>, files?: Record<Path<GroupAggregateQuery<TestModel>>, File>): Promise<Record<string, number | null | undefined>> { return this.api.testModel.groupAggregate(this.userToken, input, files) },
    }
}


export class LiveApi implements Api {
    public constructor(public httpUrl: String, public socketUrl: String = httpUrl) {}
    readonly auth = {
        httpUrl: this.httpUrl,
        socketUrl: this.socketUrl,
        emailLoginLink(input: string): Promise<void> {
            return apiCall(`${this.httpUrl}/auth/login-email`, input, {
                method: "POST",
            },             ).then(x => undefined)
        },
        refreshToken(userToken: string): Promise<string> {
            return apiCall(`${this.httpUrl}/auth/refresh-token`, undefined, {
                method: "GET",
                headers: { "Authorization": `Bearer ${userToken}` },
            },             ).then(x => x.json())
        },
        getSelf(userToken: string): Promise<User> {
            return apiCall(`${this.httpUrl}/auth/self`, undefined, {
                method: "GET",
                headers: { "Authorization": `Bearer ${userToken}` },
            },             ).then(x => x.json())
        },
    }
    readonly testModel = {
        httpUrl: this.httpUrl,
        socketUrl: this.socketUrl,
        query(userToken: string, input: Query<TestModel>, files?: Record<Path<Query<TestModel>>, File>): Promise<Array<TestModel>> {
            return apiCall(`${this.httpUrl}/test-model/rest/query`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        detail(userToken: string, id: string): Promise<TestModel> {
            return apiCall(`${this.httpUrl}/test-model/rest/${id}`, undefined, {
                method: "GET",
                headers: { "Authorization": `Bearer ${userToken}` },
            },             ).then(x => x.json())
        },
        insertBulk(userToken: string, input: Array<TestModel>, files?: Record<Path<Array<TestModel>>, File>): Promise<Array<TestModel>> {
            return apiCall(`${this.httpUrl}/test-model/rest/bulk`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        insert(userToken: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel> {
            return apiCall(`${this.httpUrl}/test-model/rest`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        upsert(userToken: string, id: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel> {
            return apiCall(`${this.httpUrl}/test-model/rest/${id}`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        bulkReplace(userToken: string, input: Array<TestModel>, files?: Record<Path<Array<TestModel>>, File>): Promise<Array<TestModel>> {
            return apiCall(`${this.httpUrl}/test-model/rest`, input, {
                    method: "PUT",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        replace(userToken: string, id: string, input: TestModel, files?: Record<Path<TestModel>, File>): Promise<TestModel> {
            return apiCall(`${this.httpUrl}/test-model/rest/${id}`, input, {
                    method: "PUT",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        bulkModify(userToken: string, input: MassModification<TestModel>, files?: Record<Path<MassModification<TestModel>>, File>): Promise<number> {
            return apiCall(`${this.httpUrl}/test-model/rest/bulk`, input, {
                    method: "PATCH",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        modify(userToken: string, id: string, input: Modification<TestModel>, files?: Record<Path<Modification<TestModel>>, File>): Promise<EntryChange<TestModel>> {
            return apiCall(`${this.httpUrl}/test-model/rest/${id}/delta`, input, {
                    method: "PATCH",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        modifyWithDiff(userToken: string, id: string, input: Modification<TestModel>, files?: Record<Path<Modification<TestModel>>, File>): Promise<TestModel> {
            return apiCall(`${this.httpUrl}/test-model/rest/${id}`, input, {
                    method: "PATCH",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        bulkDelete(userToken: string, input: Condition<TestModel>, files?: Record<Path<Condition<TestModel>>, File>): Promise<number> {
            return apiCall(`${this.httpUrl}/test-model/rest/bulk-delete`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        delete(userToken: string, id: string): Promise<void> {
            return apiCall(`${this.httpUrl}/test-model/rest/${id}`, undefined, {
                method: "DELETE",
                headers: { "Authorization": `Bearer ${userToken}` },
            },             ).then(x => undefined)
        },
        count(userToken: string, input: Condition<TestModel>, files?: Record<Path<Condition<TestModel>>, File>): Promise<number> {
            return apiCall(`${this.httpUrl}/test-model/rest/count`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        groupCount(userToken: string, input: GroupCountQuery<TestModel>, files?: Record<Path<GroupCountQuery<TestModel>>, File>): Promise<Record<string, number>> {
            return apiCall(`${this.httpUrl}/test-model/rest/group-count`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        aggregate(userToken: string, input: AggregateQuery<TestModel>, files?: Record<Path<AggregateQuery<TestModel>>, File>): Promise<number | null | undefined> {
            return apiCall(`${this.httpUrl}/test-model/rest/aggregate`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
        groupAggregate(userToken: string, input: GroupAggregateQuery<TestModel>, files?: Record<Path<GroupAggregateQuery<TestModel>>, File>): Promise<Record<string, number | null | undefined>> {
            return apiCall(`${this.httpUrl}/test-model/rest/group-aggregate`, input, {
                    method: "POST",
                    headers: { "Authorization": `Bearer ${userToken}` },
                },             files
            ).then(x => x.json())
        },
    }
    getTestPrimitive(userToken: string): Promise<string> {
        return apiCall(`${this.httpUrl}/test-primitive`, undefined, {
            method: "GET",
            headers: { "Authorization": `Bearer ${userToken}` },
        },         ).then(x => x.json())
    }
}

