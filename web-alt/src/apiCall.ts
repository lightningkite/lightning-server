
type PathImpl<T, K extends keyof T> =
    K extends string
        ? T[K] extends Record<string, any>
            ? T[K] extends ArrayLike<any>
                ? K | `${K}.${PathImpl<T[K], Exclude<keyof T[K], keyof any[]>>}`
                : K | `${K}.${PathImpl<T[K], keyof T[K]>}`
            : K
        : never;

export type Path<T> = PathImpl<T, keyof T> | (keyof T & string);

export function apiCall<T>(url: string, body: T, request: RequestInit, fileUploads?: Record<Path<T>, File>): Promise<Response> {
    let f: Promise<Response>
    if(fileUploads === undefined || Object.keys(fileUploads).length === 0) {
        f = fetch(url, {
            ...request,
            headers: {
                ...request.headers,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            body: JSON.stringify(body)
        })
    } else {
        const data = new FormData()
        data.append("__json", JSON.stringify(body))
        for(const key in fileUploads) {
            data.append(key, fileUploads[key as Path<T>], fileUploads[key as Path<T>].name)
        }
        f = fetch(url, {
            ...request,
            headers: {
                ...request.headers,
                "Accept": "application/json",
            },
            body: data
        })
    }
    return f.then(x => {
        if(!x.ok) throw x
        else return x
    })
}