"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.multiplexedSocket = exports.multiplexedSocketReified = exports.WebSocketIsh = exports.MultiplexedWebsocketPart = exports.sharedSocket = exports.set_overrideWebSocketProvider = exports.get_overrideWebSocketProvider = exports.__overrideWebSocketProvider = void 0;
// Package: com.lightningkite.ktordb.live
// Generated by Khrysalis - this file will be overwritten.
const MultiplexMessage_1 = require("../../shared/MultiplexMessage");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const rxjs_plus_1 = require("@lightningkite/rxjs-plus");
const rxjs_1 = require("rxjs");
const operators_1 = require("rxjs/operators");
const uuid_1 = require("uuid");
//! Declares com.lightningkite.ktordb.live._overrideWebSocketProvider
exports.__overrideWebSocketProvider = null;
function get_overrideWebSocketProvider() { return exports.__overrideWebSocketProvider; }
exports.get_overrideWebSocketProvider = get_overrideWebSocketProvider;
function set_overrideWebSocketProvider(value) { exports.__overrideWebSocketProvider = value; }
exports.set_overrideWebSocketProvider = set_overrideWebSocketProvider;
const sharedSocketCache = new Map();
//! Declares com.lightningkite.ktordb.live.sharedSocket
function sharedSocket(url) {
    return (0, khrysalis_runtime_1.xMutableMapGetOrPut)(sharedSocketCache, url, () => {
        var _a;
        const shortUrl = (0, khrysalis_runtime_1.xStringSubstringBefore)(url, '?', undefined);
        //        println("Creating socket to $url")
        return ((_a = (0, khrysalis_runtime_1.runOrNull)(get_overrideWebSocketProvider(), _ => _(url))) !== null && _a !== void 0 ? _a : rxjs_plus_1.HttpClient.INSTANCE.webSocket(url)).pipe((0, operators_1.switchMap)((it) => {
            //                println("Connection to $shortUrl established, starting pings")
            // Only have this observable until it fails
            const pingMessages = (0, rxjs_1.interval)(5000).pipe((0, operators_1.map)((_0) => {
                //                    println("Sending ping to $url")
                return it.write.next({ text: "", binary: null });
            })).pipe((0, operators_1.switchMap)((it) => rxjs_1.NEVER));
            const timeoutAfterSeconds = it.read.pipe((0, operators_1.timeout)(10000)).pipe((0, operators_1.switchMap)((it) => rxjs_1.NEVER));
            return (0, rxjs_1.merge)((0, rxjs_1.of)(it), pingMessages, timeoutAfterSeconds);
        })).pipe((0, operators_1.tap)(undefined, (it) => {
            console.log(`Socket to ${shortUrl} FAILED with ${it}`);
        })).pipe((0, operators_1.retryWhen)((it) => it.pipe((0, operators_1.delay)(1000)))).pipe((0, operators_1.finalize)(() => {
            //                println("Disconnecting socket to $shortUrl")
            sharedSocketCache.delete(url);
        })).pipe((0, operators_1.publishReplay)(1)).pipe((0, operators_1.refCount)());
    });
}
exports.sharedSocket = sharedSocket;
//! Declares com.lightningkite.ktordb.live.MultiplexedWebsocketPart
class MultiplexedWebsocketPart {
    constructor(messages, send) {
        this.messages = messages;
        this.send = send;
    }
}
exports.MultiplexedWebsocketPart = MultiplexedWebsocketPart;
//! Declares com.lightningkite.ktordb.live.WebSocketIsh
class WebSocketIsh {
    constructor(messages, send) {
        this.messages = messages;
        this.send = send;
    }
}
exports.WebSocketIsh = WebSocketIsh;
//! Declares com.lightningkite.ktordb.live.multiplexedSocket
function multiplexedSocketReified(IN, OUT, url, path, onSetup = (it) => { }) {
    return multiplexedSocket(url, path, IN, OUT, onSetup);
}
exports.multiplexedSocketReified = multiplexedSocketReified;
//! Declares com.lightningkite.ktordb.live.multiplexedSocket
function multiplexedSocket(url, path, inType, outType, onSetup = (it) => { }) {
    const shortUrl = (0, khrysalis_runtime_1.xStringSubstringBefore)(url, '?', undefined);
    const channel = (0, uuid_1.v4)();
    let lastSocket = null;
    return sharedSocket(url).pipe((0, operators_1.map)((it) => {
        //            println("Setting up socket to $shortUrl with $path")
        lastSocket = it;
        it.write.next({ text: JSON.stringify(new MultiplexMessage_1.MultiplexMessage(channel, path, true, undefined, undefined)), binary: null });
        const part = new MultiplexedWebsocketPart(it.read.pipe((0, rxjs_1.map)((it) => {
            const text = it.text;
            if (text === null) {
                return null;
            }
            if (text === "") {
                return null;
            }
            const message = rxjs_plus_1.JSON2.parse(text, [MultiplexMessage_1.MultiplexMessage]);
            if (message === null) {
                return null;
            }
            return message.channel === channel ? message.data : null;
        }), (0, rxjs_1.filter)(rxjs_plus_1.isNonNull)), (message) => {
            it.write.next({ text: JSON.stringify(new MultiplexMessage_1.MultiplexMessage(channel, undefined, undefined, undefined, message)), binary: null });
        });
        const typedPart = new WebSocketIsh(part.messages.pipe((0, rxjs_1.map)((it) => rxjs_plus_1.JSON2.parse(it, inType)), (0, rxjs_1.filter)(rxjs_plus_1.isNonNull)).pipe((0, operators_1.tap)(value => console.log(`Message for ${channel}`, value))), (m) => {
            part.send(JSON.stringify(m));
        });
        onSetup(typedPart);
        return typedPart;
    })).pipe((0, operators_1.finalize)(() => {
        var _a;
        //            println("Disconnecting channel on socket to $shortUrl with $path")
        const temp41 = ((_a = lastSocket === null || lastSocket === void 0 ? void 0 : lastSocket.write) !== null && _a !== void 0 ? _a : null);
        if (temp41 !== null) {
            temp41.next({ text: JSON.stringify(new MultiplexMessage_1.MultiplexMessage(channel, path, undefined, true, undefined)), binary: null });
        }
        ;
    }));
}
exports.multiplexedSocket = multiplexedSocket;
//# sourceMappingURL=sockets.js.map