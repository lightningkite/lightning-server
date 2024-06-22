package com.lightningkite.lightningserver.netty

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.*
import io.netty.util.CharsetUtil
import java.util.*


object Utils {
    fun formatParams(request: HttpRequest): StringBuilder {
        val responseData = StringBuilder()
        val queryStringDecoder = QueryStringDecoder(request.uri())
        val params = queryStringDecoder.parameters()
        if (params.isNotEmpty()) {
            for ((key, vals) in params) {
                for (it in vals) {
                    responseData.append("Parameter: ").append(key.uppercase(Locale.getDefault())).append(" = ")
                        .append(it.uppercase(Locale.getDefault())).append("\r\n")
                }
            }
            responseData.append("\r\n")
        }
        return responseData
    }

    fun formatBody(httpContent: HttpContent): java.lang.StringBuilder {
        val responseData = StringBuilder()
        val content: ByteBuf = httpContent.content()
        if (content.isReadable) {
            responseData.append(content.toString(CharsetUtil.UTF_8).uppercase(Locale.getDefault()))
                .append("\r\n")
        }
        return responseData
    }

    fun prepareLastResponse(request: HttpRequest?, trailer: LastHttpContent): StringBuilder {
        val responseData = StringBuilder()
        responseData.append("Good Bye!\r\n")

        if (!trailer.trailingHeaders().isEmpty) {
            responseData.append("\r\n")
            for (name in trailer.trailingHeaders().names()) {
                for (value in trailer.trailingHeaders().getAll(name)) {
                    responseData.append("P.S. Trailing Header: ")
                    responseData.append(name).append(" = ").append(value).append("\r\n")
                }
            }
            responseData.append("\r\n")
        }
        return responseData
    }

    fun evaluateDecoderResult(o: HttpObject?): StringBuilder {
        val responseData = StringBuilder()
        val result = o?.decoderResult()

        if (result?.isSuccess == false) {
            responseData.append("..Decoder Failure: ")
            responseData.append(result.cause())
            responseData.append("\r\n")
        }

        return responseData
    }
}