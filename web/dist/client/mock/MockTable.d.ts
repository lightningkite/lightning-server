import { Condition } from '../../shared/Condition';
import { HasId } from '../../shared/HasId';
import { UUIDFor } from '../../shared/UUIDFor';
import { SignalData } from '../SignalData';
import { Observable, Subject } from 'rxjs';
export declare class MockTable<Model extends HasId> {
    constructor();
    readonly data: Map<UUIDFor<Model>, Model>;
    readonly signals: Subject<SignalData<Model>>;
    observe(condition: Condition<Model>): Observable<Array<Model>>;
    getItem(id: UUIDFor<Model>): (Model | null);
    asList(): Array<Model>;
    addItem(item: Model): Model;
    replaceItem(item: Model): Model;
    deleteItem(item: Model): void;
    deleteItemById(id: UUIDFor<Model>): void;
}
