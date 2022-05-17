package com.lightningkite.ktorbatteries.demo

import com.lightningkite.rx.okhttp.*
import kotlin.String
import com.lightningkite.ktordb.Query
import com.lightningkite.ktorbatteries.demo.TestModel
import kotlin.collections.List
import java.util.UUID
import com.lightningkite.ktordb.MassModification
import kotlin.Int
import com.lightningkite.ktordb.Modification
import com.lightningkite.ktordb.EntryChange
import com.lightningkite.ktordb.Condition
import com.lightningkite.ktordb.ListChange
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