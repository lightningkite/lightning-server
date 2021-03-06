// Package: com.lightningkite.lightningdb.live
// Generated by Khrysalis - this file will be overwritten.
import { MultiplexMessage } from '../db/MultiplexMessage'
import { ReifiedType, runOrNull, xMutableMapGetOrPut, xStringSubstringBefore } from '@lightningkite/khrysalis-runtime'
import { HttpClient, JSON2, WebSocketFrame, WebSocketInterface, isNonNull } from '@lightningkite/rxjs-plus'
import { NEVER, Observable, filter, interval, merge, of, map as rMap } from 'rxjs'
import { delay, finalize, map, publishReplay, refCount, retryWhen, switchMap, tap, timeout } from 'rxjs/operators'
import { v4 as randomUuidV4 } from 'uuid'

//! Declares com.lightningkite.lightningdb.live._overrideWebSocketProvider
export let __overrideWebSocketProvider: (((url: string) => Observable<WebSocketInterface>) | null) = null;
export function get_overrideWebSocketProvider(): (((url: string) => Observable<WebSocketInterface>) | null) { return __overrideWebSocketProvider; }
export function set_overrideWebSocketProvider(value: (((url: string) => Observable<WebSocketInterface>) | null)) { __overrideWebSocketProvider = value; }
const sharedSocketCache = new Map<string, Observable<WebSocketInterface>>();
//! Declares com.lightningkite.lightningdb.live.sharedSocket
export function sharedSocket(url: string): Observable<WebSocketInterface> {
    return xMutableMapGetOrPut<string, Observable<WebSocketInterface>>(sharedSocketCache, url, (): Observable<WebSocketInterface> => {
        const shortUrl = xStringSubstringBefore(url, '?', undefined);
        //        println("Creating socket to $url")
        return (runOrNull(get_overrideWebSocketProvider(), _ => _(url)) ?? HttpClient.INSTANCE.webSocket(url)).pipe(switchMap((it: WebSocketInterface): Observable<WebSocketInterface> => {
            //                println("Connection to $shortUrl established, starting pings")
            // Only have this observable until it fails
            
            const pingMessages: Observable<WebSocketInterface> = interval(5000).pipe(map((_0: number): void => {
                //                    println("Sending ping to $url")
                return it.write.next({ text: "", binary: null });
            })).pipe(switchMap((it: void): Observable<WebSocketInterface> => (NEVER)));
            
            const timeoutAfterSeconds: Observable<WebSocketInterface> = it.read.pipe(timeout(10000)).pipe(switchMap((it: WebSocketFrame): Observable<WebSocketInterface> => (NEVER)));
            
            return merge(of(it), pingMessages, timeoutAfterSeconds);
        })).pipe(tap(undefined, (it: any): void => {
            console.log(`Socket to ${shortUrl} FAILED with ${it}`);
        })).pipe(retryWhen( (it: Observable<any>): Observable<any> => (it.pipe(delay(1000))))).pipe(finalize((): void => {
            //                println("Disconnecting socket to $shortUrl")
            sharedSocketCache.delete(url);
        })).pipe(publishReplay(1)).pipe(refCount());
    });
}

//! Declares com.lightningkite.lightningdb.live.MultiplexedWebsocketPart
export class MultiplexedWebsocketPart {
    public constructor(public readonly messages: Observable<string>, public readonly send: ((a: string) => void)) {
    }
}
//! Declares com.lightningkite.lightningdb.live.WebSocketIsh
export class WebSocketIsh<IN extends any, OUT extends any> {
    public constructor(public readonly messages: Observable<IN>, public readonly send: ((a: OUT) => void)) {
    }
}

//! Declares com.lightningkite.lightningdb.live.multiplexedSocket
export function multiplexedSocketReified<IN extends any, OUT extends any>(IN: Array<any>, OUT: Array<any>, url: string, path: string, onSetup: ((a: WebSocketIsh<IN, OUT>) => void) = (it: WebSocketIsh<IN, OUT>): void => {}): Observable<WebSocketIsh<IN, OUT>> {
    return multiplexedSocket<IN, OUT>(url, path, IN, OUT, onSetup);
}

//! Declares com.lightningkite.lightningdb.live.multiplexedSocket
export function multiplexedSocket<IN extends any, OUT extends any>(url: string, path: string, inType: ReifiedType, outType: ReifiedType, onSetup: ((a: WebSocketIsh<IN, OUT>) => void) = (it: WebSocketIsh<IN, OUT>): void => {}): Observable<WebSocketIsh<IN, OUT>> {
    const shortUrl = xStringSubstringBefore(url, '?', undefined);
    const channel = randomUuidV4();
    let lastSocket: (WebSocketInterface | null) = null;
    return sharedSocket(url).pipe(map((it: WebSocketInterface): WebSocketIsh<IN, OUT> => {
        //            println("Setting up socket to $shortUrl with $path")
        lastSocket = it;
        it.write.next({ text: JSON.stringify(new MultiplexMessage(channel, path, true, undefined, undefined)), binary: null });
        const part = new MultiplexedWebsocketPart(it.read.pipe(rMap((it: WebSocketFrame): (string | null) => {
                        const text = it.text
                        if(text === null) { return null }
                        if (text === "") { return null }
                        const message: MultiplexMessage | null = JSON2.parse<(MultiplexMessage | null)>(text, [MultiplexMessage])
                        if(message === null) { return null }
                        return message.channel === channel ? message.data : null
            }), filter(isNonNull)), (message: string): void => {
                it.write.next({ text: JSON.stringify(new MultiplexMessage(channel, undefined, undefined, undefined, message)), binary: null });
        });
        const typedPart = new WebSocketIsh<IN, OUT>(part.messages.pipe(rMap((it: string): (IN | null) => (JSON2.parse<IN>(it, inType))), filter(isNonNull)), (m: OUT): void => {
            part.send(JSON.stringify(m));
        });
        onSetup(typedPart);
        return typedPart;
    })).pipe(finalize((): void => {
        //            println("Disconnecting channel on socket to $shortUrl with $path")
        const temp41 = (lastSocket?.write ?? null);
        if(temp41 !== null) {
            temp41.next({ text: JSON.stringify(new MultiplexMessage(channel, path, undefined, true, undefined)), binary: null })
        };
    }));
}
