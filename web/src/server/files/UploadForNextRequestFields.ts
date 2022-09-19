// Package: com.lightningkite.lightningserver.files
// Generated by Khrysalis - this file will be overwritten.
import { PropChain } from '../../db/dsl'
import { UploadForNextRequest } from './UploadEarlyEndpointModels'
import { Instant } from '@js-joda/core'

//! Declares com.lightningkite.lightningserver.files.prepareUploadForNextRequestFields
export function prepareUploadForNextRequestFields(): void {
    ;
    ;
    ;
}
//! Declares com.lightningkite.lightningserver.files._id>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningserver.files._id.K, com.lightningkite.lightningserver.files.UploadForNextRequest
export function xPropChain_idGet<K>(this_: PropChain<K, UploadForNextRequest>): PropChain<K, string> { return this_.get<string>("_id"); }

//! Declares com.lightningkite.lightningserver.files.file>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningserver.files.file.K, com.lightningkite.lightningserver.files.UploadForNextRequest
export function xPropChainFileGet<K>(this_: PropChain<K, UploadForNextRequest>): PropChain<K, string> { return this_.get<string>("file"); }

//! Declares com.lightningkite.lightningserver.files.expires>com.lightningkite.lightningdb.PropChaincom.lightningkite.lightningserver.files.expires.K, com.lightningkite.lightningserver.files.UploadForNextRequest
export function xPropChainExpiresGet<K>(this_: PropChain<K, UploadForNextRequest>): PropChain<K, Instant> { return this_.get<Instant>("expires"); }

