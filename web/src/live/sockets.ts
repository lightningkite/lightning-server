// Package: com.lightningkite.lightningdb.live
// Generated by Khrysalis - this file will be overwritten.
import { MultiplexMessage } from '../db/MultiplexMessage'
import { ReifiedType, runOrNull, xCharSequenceIsBlank, xMutableMapGetOrPut, xStringSubstringBefore } from '@lightningkite/khrysalis-runtime'
import { HttpClient, JSON2, WebSocketFrame, WebSocketInterface, doOnSubscribe, isNonNull } from '@lightningkite/rxjs-plus'
import { NEVER, Observable, SubscriptionLike, filter, interval, merge, of, map as rMap } from 'rxjs'
import { delay, distinctUntilChanged, map, filter as oFilter, publishReplay, refCount, retryWhen, switchMap, take, tap, timeout } from 'rxjs/operators'
import { v4 as randomUuidV4 } from 'uuid'

//! Declares com.lightningkite.lightningdb.live.sharedSocketShouldBeActive
export let _sharedSocketShouldBeActive: Observable<boolean> = of(true);
export function getSharedSocketShouldBeActive(): Observable<boolean> { return _sharedSocketShouldBeActive; }
export function setSharedSocketShouldBeActive(value: Observable<boolean>) { _sharedSocketShouldBeActive = value; }
let retryTime = 1000;
let lastRetry = 0;

//! Declares com.lightningkite.lightningdb.live._overrideWebSocketProvider
export let __overrideWebSocketProvider: (((url: string) => Observable<WebSocketInterface>) | null) = null;
export function get_overrideWebSocketProvider(): (((url: string) => Observable<WebSocketInterface>) | null) { return __overrideWebSocketProvider; }
export function set_overrideWebSocketProvider(value: (((url: string) => Observable<WebSocketInterface>) | null)) { __overrideWebSocketProvider = value; }
const sharedSocketCache = new Map<string, Observable<WebSocketInterface>>();
//! Declares com.lightningkite.lightningdb.live.sharedSocket
export function sharedSocket(url: string): Observable<WebSocketInterface> {
    return xMutableMapGetOrPut<string, Observable<WebSocketInterface>>(sharedSocketCache, url, (): Observable<WebSocketInterface> => (getSharedSocketShouldBeActive()
            .pipe(distinctUntilChanged())
            .pipe(switchMap((it: boolean): Observable<WebSocketInterface> => {
            const shortUrl = xStringSubstringBefore(url, '?', undefined);
            return ((): Observable<WebSocketInterface> => {
                if ((!it)) { return NEVER } else {
                    console.log(`Creating socket to ${url}`);
                    return (runOrNull(get_overrideWebSocketProvider(), _ => _(url)) ?? HttpClient.INSTANCE.webSocket(url))
                        .pipe(switchMap((it: WebSocketInterface): Observable<WebSocketInterface> => {
                        lastRetry = Date.now();
                        //                            println("Connection to $shortUrl established, starting pings")
                        // Only have this observable until it fails
                        
                        const pingMessages: Observable<WebSocketInterface> = interval(30000)
                            .pipe(map((_0: number): void => {
                            //                                        println("Sending ping to $url")
                            return it.write.next({ text: " ", binary: null });
                        })).pipe(switchMap((it: void): Observable<WebSocketInterface> => (NEVER)));
                        
                        const timeoutAfterSeconds: Observable<WebSocketInterface> = it.read
                            .pipe(tap((it: WebSocketFrame): void => {
                            //                                    println("Got message from $shortUrl: ${it}")
                            if (Date.now() > lastRetry + 60000) {
                                retryTime = 1000;
                            }
                        }))
                            .pipe(timeout(40000))
                            .pipe(switchMap((it: WebSocketFrame): Observable<WebSocketInterface> => (NEVER)));
                        
                        return merge(of(it), pingMessages, timeoutAfterSeconds);
                    }))
                        .pipe(tap(undefined, (it: any): void => {
                        console.log(`Socket to ${shortUrl} FAILED with ${it}`);
                    }))
                        .pipe(retryWhen( (it: Observable<any>): Observable<any> => {
                        const temp = retryTime;
                        retryTime = temp * 2;
                        return it.pipe(delay(temp));
                    }))
                        .pipe(tap({ unsubscribe: (): void => {
                        console.log(`Disconnecting socket to ${shortUrl}`);
                    } }));
                }
            })()
        }))
            .pipe(publishReplay(1))
        .pipe(refCount())));
}

//! Declares com.lightningkite.lightningdb.live.WebSocketIsh
export class WebSocketIsh<IN extends any, OUT> {
    public constructor(public readonly messages: Observable<IN>, public readonly send: ((a: OUT) => void)) {
    }
}

//! Declares com.lightningkite.lightningdb.live.multiplexedSocket
export function multiplexedSocketReified<IN extends any, OUT extends any>(IN: Array<any>, OUT: Array<any>, url: string, path: string, queryParams: Map<string, Array<string>> = new Map([])): Observable<WebSocketIsh<IN, OUT>> {
    return multiplexedSocket<IN, OUT>(url, path, queryParams, IN, OUT);
}

//! Declares com.lightningkite.lightningdb.live.multiplexedSocket
export function multiplexedSocket<IN extends any, OUT extends any>(url: string, path: string, queryParams: Map<string, Array<string>> = new Map([]), inType: ReifiedType, outType: ReifiedType): Observable<WebSocketIsh<IN, OUT>> {
    return multiplexedSocketRaw(url, path, queryParams)
        .pipe(map((it: WebSocketIsh<string, string>): WebSocketIsh<IN, OUT> => (new WebSocketIsh<IN, OUT>(it.messages.pipe(rMap((it: string): (IN | null) => (JSON2.parse<IN>(it, inType))), filter(isNonNull)), (m: OUT): void => {
        it.send(JSON.stringify(m));
    }))));
}
//! Declares com.lightningkite.lightningdb.live.multiplexedSocketRaw
export function multiplexedSocketRaw(url: string, path: string, queryParams: Map<string, Array<string>> = new Map([])): Observable<WebSocketIsh<string, string>> {
    const shortUrl = xStringSubstringBefore(url, '?', undefined);
    const channel = randomUuidV4();
    let lastSocket: (WebSocketInterface | null) = null;
    return sharedSocket(url)
        .pipe(switchMap((it: WebSocketInterface): Observable<WebSocketIsh<string, string>> => {
        console.log(`Setting up socket to ${shortUrl} with ${path}`);
        lastSocket = it;
        const multiplexedIn = it.read.pipe(rMap((it: WebSocketFrame): (MultiplexMessage | null) => {
            const text = it.text
            if(text === null) { return null }
            if (xCharSequenceIsBlank(text)) { return null }
            return JSON2.parse<MultiplexMessage>(text, [MultiplexMessage]);
        }), filter(isNonNull));
        return multiplexedIn
            .pipe(oFilter((it: MultiplexMessage): boolean => (it.channel === channel && it.start)))
            .pipe(take(1))
            .pipe(map((_0: MultiplexMessage): WebSocketIsh<string, string> => {
            console.log(`Connected to channel ${channel}`);
            return new WebSocketIsh<string, string>(multiplexedIn.pipe(rMap((it: MultiplexMessage): (string | null) => (it.channel === channel ? it.data : null)), filter(isNonNull)), (message: string): void => {
                console.log(`Sending ${message} to ${it}`);
                it.write.next({ text: JSON.stringify(new MultiplexMessage(channel, undefined, undefined, undefined, undefined, message, undefined)), binary: null });
            });
        }))
            .pipe(doOnSubscribe((_0: SubscriptionLike): void => {
            it.write.next({ text: JSON.stringify(new MultiplexMessage(channel, path, queryParams, true, undefined, undefined, undefined)), binary: null });
        }));
    }))
        .pipe(tap({ unsubscribe: (): void => {
        console.log(`Disconnecting channel on socket to ${shortUrl} with ${path}`);
        const temp64 = (lastSocket?.write ?? null);
        if (temp64 !== null && temp64 !== undefined) {
            temp64.next({ text: JSON.stringify(new MultiplexMessage(channel, path, undefined, undefined, true, undefined, undefined)), binary: null })
        };
    } }));
}
