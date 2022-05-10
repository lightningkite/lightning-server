"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.xObservableToListObservable = exports.LiveObserveModelApi = void 0;
const ListChange_1 = require("../../shared/ListChange");
const Query_1 = require("../../shared/Query");
const SortPart_1 = require("../../shared/SortPart");
const ObserveModelApi_1 = require("../ObserveModelApi");
const sockets_1 = require("./sockets");
const khrysalis_runtime_1 = require("@lightningkite/khrysalis-runtime");
const operators_1 = require("rxjs/operators");
//! Declares com.lightningkite.ktordb.live.LiveObserveModelApi
class LiveObserveModelApi extends ObserveModelApi_1.ObserveModelApi {
    constructor(openSocket) {
        super();
        this.openSocket = openSocket;
        this.alreadyOpen = new khrysalis_runtime_1.EqualOverrideMap();
    }
    observe(query) {
        //multiplexedSocket<ListChange<Model>, Query<Model>>("$multiplexUrl?jwt=$token", path)
        return (0, khrysalis_runtime_1.xMutableMapGetOrPut)(this.alreadyOpen, query, () => this.openSocket(query).pipe((0, operators_1.finalize)(() => {
            this.alreadyOpen.delete(query);
        })).pipe((0, operators_1.publishReplay)(1)).pipe((0, operators_1.refCount)()));
    }
}
exports.LiveObserveModelApi = LiveObserveModelApi;
(function (LiveObserveModelApi) {
    //! Declares com.lightningkite.ktordb.live.LiveObserveModelApi.Companion
    class Companion {
        constructor() {
        }
        create(Model, multiplexUrl, token, path) {
            return new LiveObserveModelApi((query) => {
                var _a;
                return xObservableToListObservable((0, sockets_1.multiplexedSocketReified)([ListChange_1.ListChange, Model], [Query_1.Query, Model], `${multiplexUrl}?jwt=${token}`, path, undefined).pipe((0, operators_1.switchMap)((it) => {
                    it.send(query);
                    return it.messages;
                })), (_a = (0, SortPart_1.xListComparatorGet)(query.orderBy)) !== null && _a !== void 0 ? _a : (0, khrysalis_runtime_1.compareBy)((it) => it._id));
            });
        }
    }
    Companion.INSTANCE = new Companion();
    LiveObserveModelApi.Companion = Companion;
})(LiveObserveModelApi = exports.LiveObserveModelApi || (exports.LiveObserveModelApi = {}));
//! Declares com.lightningkite.ktordb.live.toListObservable>io.reactivex.rxjava3.core.Observablecom.lightningkite.ktordb.ListChangecom.lightningkite.ktordb.live.toListObservable.T
function xObservableToListObservable(this_, ordering) {
    const localList = [];
    return this_.pipe((0, operators_1.map)((it) => {
        const it_9 = it.wholeList;
        if (it_9 !== null) {
            localList.length = 0;
            localList.push(...it_9.slice().sort(ordering));
        }
        const it_11 = it._new;
        if (it_11 !== null) {
            (0, khrysalis_runtime_1.listRemoveAll)(localList, (o) => (0, khrysalis_runtime_1.safeEq)(it_11._id, o._id));
            let index = localList.findIndex((inList) => ordering(it_11, inList) < 0);
            if (index === (-1)) {
                index = localList.length;
            }
            localList.splice(index, 0, it_11);
        }
        else {
            const it_18 = it.old;
            if (it_18 !== null) {
                (0, khrysalis_runtime_1.listRemoveAll)(localList, (o) => (0, khrysalis_runtime_1.safeEq)(it_18._id, o._id));
            }
        }
        return localList;
    }));
}
exports.xObservableToListObservable = xObservableToListObservable;
//# sourceMappingURL=LiveObserveModelApi.js.map