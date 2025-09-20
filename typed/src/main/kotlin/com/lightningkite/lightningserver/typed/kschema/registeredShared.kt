package com.lightningkite.lightningserver.typed.kschema

import com.lightningkite.services.database.SerializationRegistry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.NothingSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

@OptIn(ExperimentalSerializationApi::class)
public fun SerializationRegistry.registerLightningKiteCommonSerializers() {
    register(com.lightningkite.GeoCoordinate.serializer())
    register(com.lightningkite.TrimmedString.serializer())
    register(com.lightningkite.CaselessString.serializer())
    register(com.lightningkite.TrimmedCaselessString.serializer())
    register(com.lightningkite.EmailAddress.serializer())
    register(com.lightningkite.PhoneNumber.serializer())
    register(Uuid.serializer())
    register(com.lightningkite.ZonedDateTime.serializer())
    register(com.lightningkite.OffsetDateTime.serializer())
    register(com.lightningkite.services.database.Aggregate.serializer())
    register(com.lightningkite.services.database.CollectionChanges.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.CollectionChanges.serializer(it[0]) }
    register(com.lightningkite.services.database.CollectionUpdates.serializer(NothingSerializer(), NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.CollectionUpdates.serializer(it[0], it[1]) }
    register(com.lightningkite.services.database.Condition.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.Never.serializer())
    register(com.lightningkite.services.database.Condition.Always.serializer())
    register(com.lightningkite.services.database.Condition.And.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.And.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.Or.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Or.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.Not.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Not.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.Equal.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Equal.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.NotEqual.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.NotEqual.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.Inside.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Inside.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.NotInside.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.NotInside.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.GreaterThan.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.GreaterThan.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.LessThan.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.LessThan.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.GreaterThanOrEqual.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.GreaterThanOrEqual.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.LessThanOrEqual.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.LessThanOrEqual.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.StringContains.serializer())
    register(com.lightningkite.services.database.Condition.RawStringContains.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.RawStringContains.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.GeoDistance.serializer())
    register(com.lightningkite.services.database.Condition.FullTextSearch.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.FullTextSearch.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.RegexMatches.serializer())
    register(com.lightningkite.services.database.Condition.IntBitsClear.serializer())
    register(com.lightningkite.services.database.Condition.IntBitsSet.serializer())
    register(com.lightningkite.services.database.Condition.IntBitsAnyClear.serializer())
    register(com.lightningkite.services.database.Condition.IntBitsAnySet.serializer())
    register(com.lightningkite.services.database.Condition.ListAllElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.ListAllElements.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.ListAnyElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.ListAnyElements.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.ListSizesEquals.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.ListSizesEquals.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.SetAllElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.SetAllElements.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.SetAnyElements.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.SetAnyElements.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.SetSizesEquals.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.SetSizesEquals.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.Exists.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.Exists.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.OnKey.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.OnKey.serializer(it[0]) }
    register(com.lightningkite.services.database.Condition.IfNotNull.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Condition.IfNotNull.serializer(it[0]) }
    register(com.lightningkite.services.database.EntryChange.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.EntryChange.serializer(it[0]) }
    register(com.lightningkite.services.database.GroupCountQuery.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.GroupCountQuery.serializer(it[0]) }
    register(com.lightningkite.services.database.AggregateQuery.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.AggregateQuery.serializer(it[0]) }
    register(com.lightningkite.services.database.GroupAggregateQuery.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.GroupAggregateQuery.serializer(it[0]) }
    register(com.lightningkite.services.database.ListChange.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.ListChange.serializer(it[0]) }
    register(com.lightningkite.services.database.Mask.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Mask.serializer(it[0]) }
    register(com.lightningkite.services.database.MassModification.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.MassModification.serializer(it[0]) }
    register(com.lightningkite.services.database.ModelPermissions.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.ModelPermissions.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.Nothing.serializer())
    register(com.lightningkite.services.database.Modification.Chain.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Chain.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.IfNotNull.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.IfNotNull.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.Assign.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Assign.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.CoerceAtMost.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.CoerceAtMost.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.CoerceAtLeast.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.CoerceAtLeast.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.Increment.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Increment.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.Multiply.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Multiply.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.AppendString.serializer())
    register(com.lightningkite.services.database.Modification.AppendRawString.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.AppendRawString.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.ListAppend.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListAppend.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.ListRemove.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListRemove.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.ListRemoveInstances.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListRemoveInstances.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.ListDropFirst.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListDropFirst.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.ListDropLast.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListDropLast.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.ListPerElement.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ListPerElement.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.SetAppend.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetAppend.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.SetRemove.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetRemove.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.SetRemoveInstances.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetRemoveInstances.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.SetDropFirst.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetDropFirst.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.SetDropLast.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetDropLast.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.SetPerElement.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.SetPerElement.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.Combine.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.Combine.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.ModifyByKey.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.ModifyByKey.serializer(it[0]) }
    register(com.lightningkite.services.database.Modification.RemoveKeys.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Modification.RemoveKeys.serializer(it[0]) }
    register(com.lightningkite.services.database.Query.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Query.serializer(it[0]) }
    register(com.lightningkite.services.database.QueryPartial.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.QueryPartial.serializer(it[0]) }
    register(com.lightningkite.services.database.SortPart.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.SortPart.serializer(it[0]) }
    register(com.lightningkite.services.database.UpdateRestrictionsPart.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.UpdateRestrictionsPart.serializer(it[0]) }
    register(com.lightningkite.services.database.UpdateRestrictions.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.UpdateRestrictions.serializer(it[0]) }
    register(com.lightningkite.lightningserver.LSError.serializer())
    register(com.lightningkite.services.database.DataClassPathPartial.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.DataClassPathPartial.serializer(it[0]) }
    register(com.lightningkite.services.database.Partial.serializer(NothingSerializer()).descriptor.serialName) { com.lightningkite.services.database.Partial.serializer(it[0]) }
    register(com.lightningkite.services.database.VirtualAlias.serializer())
    register(com.lightningkite.services.database.VirtualTypeParameter.serializer())
    register(com.lightningkite.services.database.VirtualStruct.serializer())
    register(com.lightningkite.services.database.VirtualEnum.serializer())
    register(com.lightningkite.services.database.VirtualEnumOption.serializer())
    register(com.lightningkite.services.database.VirtualField.serializer())
    register(com.lightningkite.services.database.VirtualTypeReference.serializer())
    register(com.lightningkite.services.database.SerializableAnnotation.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.NullValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.BooleanValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.ByteValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.ShortValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.IntValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.LongValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.FloatValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.DoubleValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.CharValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.StringValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.ClassValue.serializer())
    register(com.lightningkite.services.database.SerializableAnnotationValue.ArrayValue.serializer())
    register(com.lightningkite.Length.serializer())
    register(com.lightningkite.Area.serializer())
    register(com.lightningkite.Volume.serializer())
    register(com.lightningkite.Mass.serializer())
    register(com.lightningkite.Speed.serializer())
    register(com.lightningkite.Acceleration.serializer())
    register(com.lightningkite.Force.serializer())
    register(com.lightningkite.Pressure.serializer())
    register(com.lightningkite.Energy.serializer())
    register(com.lightningkite.Power.serializer())
    register(com.lightningkite.Temperature.serializer())
    register(com.lightningkite.RelativeTemperature.serializer())
    register(com.lightningkite.services.data.ValidationIssue.serializer())
    register(com.lightningkite.services.data.ValidationIssuePart.serializer())
}
