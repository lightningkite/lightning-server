
type PathImpl<T, K extends keyof T> =
    K extends string
        ? T[K] extends Record<string, any>
            ? T[K] extends ArrayLike<any>
                ? K | `${K}.${PathImpl<T[K], Exclude<keyof T[K], keyof any[]>>}`
                : K | `${K}.${PathImpl<T[K], keyof T[K]>}`
            : K
        : never;

export type Path<T> = PathImpl<T, keyof T> | (keyof T & string);

let sfetch: (url: string, init?: RequestInit) => Promise<Response> = fetch
export function systemFetch(): (url: string, init?: RequestInit) => Promise<Response> {
    return sfetch
}
export function setSystemFetch(func: (url: string, init?: RequestInit) => Promise<Response>) {
    sfetch = func
}

let syncResponseInterceptors: Array<(response: Response) => Response> = []
let asyncResponseInterceptors: Array<(response: Response) => Promise<Response>> = []
async function interceptors(response: Response): Promise<Response> {
    let current = response
    for(const f of syncResponseInterceptors) {
        current = f(current)
    }
    for(const f of asyncResponseInterceptors) {
        current = await f(current)
    }
    return current
}

export async function apiCall<T>(url: string, body: T, request: RequestInit, fileUploads?: Record<Path<T>, File>, responseInterceptors?: (x: Response)=>Response): Promise<Response> {
    let f: Promise<Response>
    if(fileUploads === undefined || Object.keys(fileUploads).length === 0) {
        f = interceptors(await sfetch(url, {
            ...request,
            headers: {
                ...request.headers,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            body: JSON.stringify(body)
        }))
    } else {
        const data = new FormData()
        data.append("__json", JSON.stringify(body))
        for(const key in fileUploads) {
            data.append(key, fileUploads[key as Path<T>], fileUploads[key as Path<T>].name)
        }
        f = interceptors(await sfetch(url, {
            ...request,
            headers: {
                ...request.headers,
                "Accept": "application/json",
            },
            body: data
        }))
    }
    return f.then(x => {
        let response = responseInterceptors?.(x) ?? x
        if(!response.ok) {
            throw response
        }
        else return response
    })
}