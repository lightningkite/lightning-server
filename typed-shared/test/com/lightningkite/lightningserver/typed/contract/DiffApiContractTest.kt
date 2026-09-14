package com.lightningkite.lightningserver.typed.contract

import com.lightningkite.lightningserver.auth.RequiredScope
import com.lightningkite.lightningserver.typed.LightningServerKSchema
import com.lightningkite.lightningserver.typed.LightningServerKSchemaEndpoint
import com.lightningkite.services.database.VirtualEnum
import com.lightningkite.services.database.VirtualEnumOption
import com.lightningkite.services.database.VirtualField
import com.lightningkite.services.database.VirtualSealed
import com.lightningkite.services.database.VirtualSealedOption
import com.lightningkite.services.database.VirtualStruct
import com.lightningkite.services.database.VirtualTypeReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-taxonomy-code unit tests for [diffApiContract] using small golden [LightningServerKSchema] pairs built directly
 * from the kschema / `Virtual*` model types (no parallel DTO).
 *
 * Each test isolates a single change so the expected [ApiChangeCode] is unambiguous.
 */
class DiffApiContractTest {

    private fun ref(name: String, nullable: Boolean = false, args: List<VirtualTypeReference> = listOf()) =
        VirtualTypeReference(name, args, isNullable = nullable)

    /** A field whose `required` (computed in the diff) is controlled by [required] via optionality. */
    private fun field(name: String, type: VirtualTypeReference, required: Boolean) =
        VirtualField(index = 0, name = name, type = type, optional = !required, annotations = listOf())

    private fun struct(serialName: String, vararg fields: VirtualField) =
        VirtualStruct(serialName, annotations = listOf(), fields = fields.toList(), parameters = listOf())

    private fun enum(serialName: String, vararg options: String) =
        VirtualEnum(serialName, annotations = listOf(), options = options.mapIndexed { i, n ->
            VirtualEnumOption(n, annotations = listOf(), index = i)
        })

    private fun sealed(serialName: String, vararg subtypes: String) =
        VirtualSealed(serialName, annotations = listOf(), options = subtypes.mapIndexed { i, n ->
            VirtualSealedOption(name = n, secondaryNames = setOf(), type = ref(n), index = i)
        })

    private fun endpoint(
        path: String,
        method: String = "POST",
        scopes: Set<RequiredScope> = setOf(RequiredScope.root),
        input: VirtualTypeReference = ref("kotlin.Unit"),
        output: VirtualTypeReference = ref("kotlin.Unit"),
    ) = LightningServerKSchemaEndpoint(
        description = "",
        summary = "",
        method = method,
        path = path,
        scopes = scopes,
        routes = mapOf(),
        input = input,
        output = output,
        belongsToInterface = null,
    )

    private fun schema(
        endpoints: List<LightningServerKSchemaEndpoint> = listOf(),
        structs: List<VirtualStruct> = listOf(),
        enums: List<VirtualEnum> = listOf(),
        sealeds: List<VirtualSealed> = listOf(),
    ) = LightningServerKSchema(
        baseUrl = "",
        baseWsUrl = "",
        structures = structs.associateBy { it.serialName },
        sealedStructures = sealeds.associateBy { it.serialName },
        enums = enums.associateBy { it.serialName },
        endpoints = endpoints,
        interfaces = listOf(),
    )

    private fun ApiDiffReport.codes() = changes.map { it.code }.toSet()

    @Test
    fun endpointRemovedIsBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a"), endpoint("/b")))
        val cur = schema(endpoints = listOf(endpoint("/a")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.ENDPOINT_REMOVED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun endpointAddedIsNonBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a")))
        val cur = schema(endpoints = listOf(endpoint("/a"), endpoint("/b")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.ENDPOINT_ADDED in report.codes())
        assertFalse(report.hasFailures())
    }

    @Test
    fun inputRequiredFieldAddedIsBreaking() {
        val baseStruct = struct("In", field("a", ref("kotlin.String"), required = true))
        val curStruct = struct("In", field("a", ref("kotlin.String"), required = true), field("b", ref("kotlin.String"), required = true))
        val base = schema(endpoints = listOf(endpoint("/a", input = ref("In"))), structs = listOf(baseStruct))
        val cur = schema(endpoints = listOf(endpoint("/a", input = ref("In"))), structs = listOf(curStruct))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.INPUT_REQUIRED_FIELD_ADDED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun inputOptionalFieldAddedIsNonBreaking() {
        val baseStruct = struct("In", field("a", ref("kotlin.String"), required = true))
        val curStruct = struct("In", field("a", ref("kotlin.String"), required = true), field("b", ref("kotlin.String"), required = false))
        val base = schema(endpoints = listOf(endpoint("/a", input = ref("In"))), structs = listOf(baseStruct))
        val cur = schema(endpoints = listOf(endpoint("/a", input = ref("In"))), structs = listOf(curStruct))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.INPUT_OPTIONAL_FIELD_ADDED in report.codes())
        assertFalse(report.hasFailures())
    }

    @Test
    fun inputFieldBecameRequiredIsBreaking() {
        val baseStruct = struct("In", field("a", ref("kotlin.String"), required = false))
        val curStruct = struct("In", field("a", ref("kotlin.String"), required = true))
        val base = schema(endpoints = listOf(endpoint("/a", input = ref("In"))), structs = listOf(baseStruct))
        val cur = schema(endpoints = listOf(endpoint("/a", input = ref("In"))), structs = listOf(curStruct))
        assertTrue(ApiChangeCode.INPUT_FIELD_BECAME_REQUIRED in diffApiContract(base, cur).codes())
    }

    @Test
    fun outputFieldRemovedIsBreaking() {
        val baseStruct = struct("Out", field("a", ref("kotlin.String"), required = true), field("b", ref("kotlin.String"), required = true))
        val curStruct = struct("Out", field("a", ref("kotlin.String"), required = true))
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(baseStruct))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(curStruct))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.OUTPUT_FIELD_REMOVED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun outputFieldAddedIsNonBreaking() {
        val baseStruct = struct("Out", field("a", ref("kotlin.String"), required = true))
        val curStruct = struct("Out", field("a", ref("kotlin.String"), required = true), field("b", ref("kotlin.String"), required = true))
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(baseStruct))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(curStruct))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.OUTPUT_FIELD_ADDED in report.codes())
        assertFalse(report.hasFailures())
    }

    @Test
    fun outputFieldBecameNullableIsBreaking() {
        val baseStruct = struct("Out", field("a", ref("kotlin.String", nullable = false), required = true))
        val curStruct = struct("Out", field("a", ref("kotlin.String", nullable = true), required = false))
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(baseStruct))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(curStruct))
        assertTrue(ApiChangeCode.OUTPUT_FIELD_BECAME_NULLABLE in diffApiContract(base, cur).codes())
    }

    @Test
    fun typeChangedIsBreaking() {
        val baseStruct = struct("Out", field("a", ref("kotlin.Int"), required = true))
        val curStruct = struct("Out", field("a", ref("kotlin.Long"), required = true))
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(baseStruct))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(curStruct))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.TYPE_CHANGED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun outputEnumWidenedIsBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "A", "B")))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "A", "B", "C")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.OUTPUT_ENUM_WIDENED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun outputEnumNarrowedIsNonBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "A", "B", "C")))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "A", "B")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.OUTPUT_ENUM_NARROWED in report.codes())
        assertFalse(report.hasFailures())
    }

    @Test
    fun inputEnumNarrowedIsBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a", input = ref("E"))), enums = listOf(enum("E", "A", "B", "C")))
        val cur = schema(endpoints = listOf(endpoint("/a", input = ref("E"))), enums = listOf(enum("E", "A", "B")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.INPUT_ENUM_NARROWED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun inputEnumWidenedIsNonBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a", input = ref("E"))), enums = listOf(enum("E", "A", "B")))
        val cur = schema(endpoints = listOf(endpoint("/a", input = ref("E"))), enums = listOf(enum("E", "A", "B", "C")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.INPUT_ENUM_WIDENED in report.codes())
        assertFalse(report.hasFailures())
    }

    @Test
    fun authTightenedIsBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a", scopes = setOf(RequiredScope.root))))
        val cur = schema(endpoints = listOf(endpoint("/a", scopes = setOf(RequiredScope.root, RequiredScope("admin")))))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.AUTH_TIGHTENED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun authLoosenedIsNonBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a", scopes = setOf(RequiredScope.root, RequiredScope("admin")))))
        val cur = schema(endpoints = listOf(endpoint("/a", scopes = setOf(RequiredScope.root))))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.AUTH_LOOSENED in report.codes())
        assertFalse(report.hasFailures())
    }

    @Test
    fun sealedSubtypeRemovedIsBreaking() {
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("S"))), sealeds = listOf(sealed("S", "A", "B")))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("S"))), sealeds = listOf(sealed("S", "A")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.SEALED_SUBTYPE_REMOVED in report.codes())
        assertTrue(report.hasFailures())
    }

    @Test
    fun typeRemovedIsBreaking() {
        val baseStruct = struct("Out", field("a", ref("kotlin.String"), required = true))
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("Out"))), structs = listOf(baseStruct))
        // Endpoint output retargeted so "Out" is gone but still nothing references it; it should be reported removed
        // because it was reachable in the baseline.
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("kotlin.Unit"))))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.TYPE_REMOVED in report.codes())
    }

    @Test
    fun allowlistSuppressesBreakingChange() {
        val base = schema(endpoints = listOf(endpoint("/a"), endpoint("/b")))
        val cur = schema(endpoints = listOf(endpoint("/a")))
        val allowlist = ApiAllowlist(listOf(ApiAllowlist.Entry(ApiChangeCode.ENDPOINT_REMOVED.code, "POST /b")))
        val report = diffApiContract(base, cur, allowlist)
        assertTrue(report.changes.single { it.code == ApiChangeCode.ENDPOINT_REMOVED }.suppressed)
        assertFalse(report.hasFailures())
    }

    @Test
    fun unchangedSchemaHasNoChanges() {
        val s = schema(
            endpoints = listOf(endpoint("/a", output = ref("Out"))),
            structs = listOf(struct("Out", field("a", ref("kotlin.String"), required = true))),
        )
        assertEquals(0, diffApiContract(s, s).changes.size)
    }

    @Test
    fun enumReorderedIsPotentiallyBreaking() {
        // Same option SET, different declaration order. Some serializers encode enums by ordinal, so this is flagged.
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "A", "B", "C")))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "C", "A", "B")))
        val report = diffApiContract(base, cur)
        assertTrue(ApiChangeCode.ENUM_REORDERED in report.codes(), "Reordering an enum's options should warn")
        // Not a hard failure by default, but it fails under strict mode (POTENTIALLY_BREAKING).
        assertFalse(report.hasFailures())
        assertTrue(report.hasFailures(strict = true))
    }

    @Test
    fun enumSameOrderIsNotReported() {
        val base = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "A", "B", "C")))
        val cur = schema(endpoints = listOf(endpoint("/a", output = ref("E"))), enums = listOf(enum("E", "A", "B", "C")))
        assertEquals(0, diffApiContract(base, cur).changes.size)
    }

    @Test
    fun diffIsOrderAndDocInsensitive() {
        // Two logically-identical schemas that differ only in collection ordering, base URLs, documentation, SDK
        // interface grouping, and declaration indices must diff to ZERO changes WITHOUT any normalization pass.
        val a = LightningServerKSchema(
            baseUrl = "https://a.example.com",
            baseWsUrl = "wss://a.example.com",
            structures = mapOf(
                "B" to struct("B", field("y", ref("kotlin.Int"), required = true), field("x", ref("kotlin.Int"), required = true)),
                "A" to struct("A", field("a", ref("kotlin.String"), required = true)),
            ),
            enums = mapOf("E" to enum("E", "A", "Z")),
            endpoints = listOf(
                endpoint("/z", method = "GET", output = ref("B")).copy(summary = "Z", description = "does z", docGroup = "g"),
                endpoint("/a", input = ref("A")).copy(summary = "A"),
            ),
            interfaces = listOf(),
        )
        val b = LightningServerKSchema(
            baseUrl = "https://b.example.com",
            baseWsUrl = "wss://b.example.com",
            structures = mapOf(
                // Same fields, different declaration order (and indices via the helper would differ if set).
                "A" to struct("A", field("a", ref("kotlin.String"), required = true)),
                "B" to struct("B", field("x", ref("kotlin.Int"), required = true), field("y", ref("kotlin.Int"), required = true)),
            ),
            // NOTE: same option order so ENUM_REORDERED does not fire; ordering of the enum MAP is irrelevant.
            enums = mapOf("E" to enum("E", "A", "Z")),
            endpoints = listOf(
                endpoint("/a", input = ref("A")),
                endpoint("/z", method = "GET", output = ref("B")),
            ),
            interfaces = listOf(),
        )
        assertEquals(
            0,
            diffApiContract(a, b).changes.size,
            "Schemas differing only in ordering/URLs/docs must diff to no changes without normalization",
        )
        // Symmetric: also zero the other direction.
        assertEquals(0, diffApiContract(b, a).changes.size)
    }
}
