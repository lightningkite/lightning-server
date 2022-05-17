package com.lightningkite.ktorbatteries.demo

import com.lightningkite.rx.okhttp.*
import kotlin.String
import com.lightningkite.ktorbatteries.demo.TestModel
import com.lightningkite.ktordb.*
import kotlin.collections.List
import java.util.UUID
import kotlin.Int
import com.lightningkite.rx.kotlin
import com.lightningkite.rx.okhttp.*
import com.lightningkite.ktordb.live.*
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

interface Api {
    val auth: AuthApi
    val testModel: TestModelApi
    fun getTestPrimitive(): Single<String>
    interface AuthApi {
        fun emailLoginLink(input: String): Single<Unit>
        fun refreshToken(userToken: String): Single<String>
    }
    interface TestModelApi {
        fun list(userToken: String, input: Query<TestModel>): Single<List<TestModel>>
        fun query(userToken: String, input: Query<TestModel>): Single<List<TestModel>>
        fun detail(userToken: String, id: UUID): Single<TestModel>
        fun insertBulk(userToken: String, input: List<TestModel>): Single<List<TestModel>>
        fun insert(userToken: String, input: TestModel): Single<TestModel>
        fun upsert(userToken: String, id: UUID, input: TestModel): Single<TestModel>
        fun bulkReplace(userToken: String, input: List<TestModel>): Single<List<TestModel>>
        fun replace(userToken: String, id: UUID, input: TestModel): Single<TestModel>
        fun bulkModify(userToken: String, input: MassModification<TestModel>): Single<Int>
        fun modify(userToken: String, id: UUID, input: Modification<TestModel>): Single<EntryChange<TestModel>>
        fun modifyWithDiff(userToken: String, id: UUID, input: Modification<TestModel>): Single<TestModel>
        fun bulkDelete(userToken: String, input: Condition<TestModel>): Single<Int>
        fun delete(userToken: String, id: UUID): Single<Unit>
        fun watchTestModel(userToken: String): Observable<WebSocketIsh<Query<TestModel>, ListChange<TestModel>>>
    }
}


class LiveApi(val httpUrl: String, val socketUrl: String = httpUrl): Api {
    override val auth: LiveAuthApi = LiveAuthApi(httpUrl = httpUrl, socketUrl = socketUrl)
    override val testModel: LiveTestModelApi = LiveTestModelApi(httpUrl = httpUrl, socketUrl = socketUrl)
    override fun getTestPrimitive(): Single<String> = HttpClient.call(
        url = "$httpUrl/test-primitive",
        method = HttpClient.GET,
    ).readJson()
    class LiveAuthApi(val httpUrl: String, val socketUrl: String = httpUrl): Api.AuthApi {
        override fun emailLoginLink(input: String): Single<Unit> = HttpClient.call(
            url = "$httpUrl/auth/login-email",
            method = HttpClient.POST,
            body = input.toJsonRequestBody()
        ).discard()
        override fun refreshToken(userToken: String): Single<String> = HttpClient.call(
            url = "$httpUrl/auth/refresh-token",
            method = HttpClient.GET,
        ).readJson()
    }
    class LiveTestModelApi(val httpUrl: String, val socketUrl: String = httpUrl): Api.TestModelApi {
        override fun list(userToken: String, input: Query<TestModel>): Single<List<TestModel>> = HttpClient.call(
            url = "$httpUrl/test-model/rest",
            method = HttpClient.GET,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun query(userToken: String, input: Query<TestModel>): Single<List<TestModel>> = HttpClient.call(
            url = "$httpUrl/test-model/rest/query",
            method = HttpClient.POST,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun detail(userToken: String, id: UUID): Single<TestModel> = HttpClient.call(
            url = "$httpUrl/test-model/rest/$id",
            method = HttpClient.GET,
        ).readJson()
        override fun insertBulk(userToken: String, input: List<TestModel>): Single<List<TestModel>> = HttpClient.call(
            url = "$httpUrl/test-model/rest/bulk",
            method = HttpClient.POST,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun insert(userToken: String, input: TestModel): Single<TestModel> = HttpClient.call(
            url = "$httpUrl/test-model/rest",
            method = HttpClient.POST,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun upsert(userToken: String, id: UUID, input: TestModel): Single<TestModel> = HttpClient.call(
            url = "$httpUrl/test-model/rest/$id",
            method = HttpClient.POST,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun bulkReplace(userToken: String, input: List<TestModel>): Single<List<TestModel>> = HttpClient.call(
            url = "$httpUrl/test-model/rest",
            method = HttpClient.PUT,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun replace(userToken: String, id: UUID, input: TestModel): Single<TestModel> = HttpClient.call(
            url = "$httpUrl/test-model/rest/$id",
            method = HttpClient.PUT,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun bulkModify(userToken: String, input: MassModification<TestModel>): Single<Int> = HttpClient.call(
            url = "$httpUrl/test-model/rest/bulk",
            method = HttpClient.PATCH,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun modify(userToken: String, id: UUID, input: Modification<TestModel>): Single<EntryChange<TestModel>> = HttpClient.call(
            url = "$httpUrl/test-model/rest/$id/delta",
            method = HttpClient.PATCH,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun modifyWithDiff(userToken: String, id: UUID, input: Modification<TestModel>): Single<TestModel> = HttpClient.call(
            url = "$httpUrl/test-model/rest/$id",
            method = HttpClient.PATCH,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun bulkDelete(userToken: String, input: Condition<TestModel>): Single<Int> = HttpClient.call(
            url = "$httpUrl/test-model/rest/bulk-delete",
            method = HttpClient.POST,
            body = input.toJsonRequestBody()
        ).readJson()
        override fun delete(userToken: String, id: UUID): Single<Unit> = HttpClient.call(
            url = "$httpUrl/test-model/rest/$id",
            method = HttpClient.DELETE,
        ).discard()
        override fun watchTestModel(userToken: String): Observable<WebSocketIsh<Query<TestModel>, ListChange<TestModel>>> = multiplexedSocket(url = "$httpUrl/multiplex", path = "/test-model/rest")
    }
}

abstract class UserSession(val api: Api, val userToken: String) {
    val auth: UserSessionAuthApi = UserSessionAuthApi(api.auth, userToken)
    val testModel: UserSessionTestModelApi = UserSessionTestModelApi(api.testModel, userToken)
    fun getTestPrimitive(): Single<String> = api.getTestPrimitive()
    data class UserSessionAuthApi(val api: Api.AuthApi, val userToken: String) {
        fun refreshToken(): Single<String> = api.refreshToken(userToken)
        fun emailLoginLink(input: String): Single<Unit> = api.emailLoginLink(input)
    }
    data class UserSessionTestModelApi(val api: Api.TestModelApi, val userToken: String) {
        fun query(input: Query<TestModel>): Single<List<TestModel>> = api.query(userToken, input)
        fun detail(id: UUID): Single<TestModel> = api.detail(userToken, id)
        fun insertBulk(input: List<TestModel>): Single<List<TestModel>> = api.insertBulk(userToken, input)
        fun insert(input: TestModel): Single<TestModel> = api.insert(userToken, input)
        fun upsert(id: UUID, input: TestModel): Single<TestModel> = api.upsert(userToken, id, input)
        fun bulkReplace(input: List<TestModel>): Single<List<TestModel>> = api.bulkReplace(userToken, input)
        fun replace(id: UUID, input: TestModel): Single<TestModel> = api.replace(userToken, id, input)
        fun bulkModify(input: MassModification<TestModel>): Single<Int> = api.bulkModify(userToken, input)
        fun modify(id: UUID, input: Modification<TestModel>): Single<EntryChange<TestModel>> = api.modify(userToken, id, input)
        fun modifyWithDiff(id: UUID, input: Modification<TestModel>): Single<TestModel> = api.modifyWithDiff(userToken, id, input)
        fun bulkDelete(input: Condition<TestModel>): Single<Int> = api.bulkDelete(userToken, input)
        fun delete(id: UUID): Single<Unit> = api.delete(userToken, id)
        fun watchTestModel(): Observable<WebSocketIsh<Query<TestModel>, ListChange<TestModel>>> = api.watchTestModel(userToken)
    }
}
