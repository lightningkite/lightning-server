import { Condition } from './Condition'
import { Modification } from './Modification'

export interface Query<T> {
    condition?: Condition<T>// = Condition.Always<T>(),
    orderBy?: Array<keyof T>
    skip?: number // = 0,
    limit?: number // = 100,
}
export interface MassModification<T> {
    condition: Condition<T>
    modification: Modification<T>
}
export interface EntryChange<T> {
    old?: T | null
    new?: T | null
}
export interface ListChange<T> {
    wholeList?: Array<T> | null
    old?: T | null
    new?: T | null
}
