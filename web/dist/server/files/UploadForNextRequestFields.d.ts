import { KeyPath } from '../../db/dsl';
import { UploadForNextRequest } from './UploadEarlyEndpointModels';
import { Instant } from '@js-joda/core';
export declare function prepareUploadForNextRequestFields(): void;
export declare function xKeyPath_idGet<K>(this_: KeyPath<K, UploadForNextRequest>): KeyPath<K, string>;
export declare function xKeyPathFileGet<K>(this_: KeyPath<K, UploadForNextRequest>): KeyPath<K, string>;
export declare function xKeyPathExpiresGet<K>(this_: KeyPath<K, UploadForNextRequest>): KeyPath<K, Instant>;
