package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.handler
import com.lightningkite.lightningserver.routes.fullUrl
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head


/**
 * Creates the End point for the Admin Index, which will direct the user to each of the models available.
 */
@LightningServerDsl
fun ServerPath.adminIndex(): HttpEndpoint {
    return get.handler {
        HttpResponse.html {
            head {}
            body {
                div {
                    for (section in ModelAdminEndpoints.known) {
                        div {
                            a(href = section.path.fullUrl()) {
                                +section.info.serialization.serializer.descriptor.serialName
                            }
                        }
                    }
                }
            }
        }
    }
}