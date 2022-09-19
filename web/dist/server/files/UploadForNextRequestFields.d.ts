import { PropChain } from '../../db/dsl';
import { UploadForNextRequest } from './UploadEarlyEndpointModels';
import { Instant } from '@js-joda/core';
export declare function prepareUploadForNextRequestFields(): void;
export declare function xPropChain_idGet<K>(this_: PropChain<K, UploadForNextRequest>): PropChain<K, string>;
export declare function xPropChainFileGet<K>(this_: PropChain<K, UploadForNextRequest>): PropChain<K, string>;
export declare function xPropChainExpiresGet<K>(this_: PropChain<K, UploadForNextRequest>): PropChain<K, Instant>;
