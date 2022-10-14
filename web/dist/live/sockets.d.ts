import { ReifiedType } from '@lightningkite/khrysalis-runtime';
import { WebSocketInterface } from '@lightningkite/rxjs-plus';
import { Observable } from 'rxjs';
export declare let __overrideWebSocketProvider: (((url: string) => Observable<WebSocketInterface>) | null);
export declare function get_overrideWebSocketProvider(): (((url: string) => Observable<WebSocketInterface>) | null);
export declare function set_overrideWebSocketProvider(value: (((url: string) => Observable<WebSocketInterface>) | null)): void;
export declare function sharedSocket(url: string): Observable<WebSocketInterface>;
export declare class WebSocketIsh<IN extends any, OUT> {
    readonly messages: Observable<IN>;
    readonly send: ((a: OUT) => void);
    constructor(messages: Observable<IN>, send: ((a: OUT) => void));
}
export declare function multiplexedSocketReified<IN extends any, OUT extends any>(IN: Array<any>, OUT: Array<any>, url: string, path: string, queryParams?: Map<string, Array<string>>): Observable<WebSocketIsh<IN, OUT>>;
export declare function multiplexedSocket<IN extends any, OUT extends any>(url: string, path: string, queryParams: Map<string, string[]> | undefined, inType: ReifiedType, outType: ReifiedType): Observable<WebSocketIsh<IN, OUT>>;
export declare function multiplexedSocketRaw(url: string, path: string, queryParams?: Map<string, Array<string>>): Observable<WebSocketIsh<string, string>>;
