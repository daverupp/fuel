/*
* Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/
// Inspired By https://github.com/ktorio/ktor/blob/master/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt

package fuel.ktor

import fuel.Fuel
import fuel.HttpLoader
import fuel.Request
import io.ktor.client.call.UnsupportedContentTypeException
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.callContext
import io.ktor.client.engine.mergeHeaders
import io.ktor.client.features.HttpTimeout
import io.ktor.client.features.SocketTimeoutException
import io.ktor.client.features.convertLongTimeoutToLongWithInfiniteAsZero
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.utils.clientDispatcher
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.InternalAPI
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.SilentSupervisor
import io.ktor.util.createLRUCache
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.Closeable
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.http.HttpMethod
import okio.BufferedSource
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
@InternalAPI
class FuelEngine(override val config: FuelConfig) : HttpClientEngineBase("ktor-fuel") {

    override val dispatcher: CoroutineDispatcher by lazy {
        Dispatchers.clientDispatcher(
            config.threadsCount,
            "ktor-fuel-dispatcher"
        )
    }

    override val supportedCapabilities = setOf(HttpTimeout)

    private val requestsJob: CoroutineContext

    override val coroutineContext: CoroutineContext

    /**
     * Cache that keeps least recently used [OkHttpClient] instances.
     */
    private val clientCache = createLRUCache(::createOkHttpClient, {}, config.clientCacheSize)
    init {
        val parent = super.coroutineContext[Job]!!
        requestsJob = SilentSupervisor(parent)
        coroutineContext = super.coroutineContext + requestsJob

        GlobalScope.launch(super.coroutineContext, start = CoroutineStart.ATOMIC) {
            try {
                requestsJob[Job]!!.join()
            } finally {
                clientCache.forEach { (_, client) ->
                    client.connectionPool.evictAll()
                }
                (dispatcher as Closeable).close()
            }
        }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val requestEngine = clientCache[data.getCapabilityOrNull(HttpTimeout)]
            ?: error("OkHttpClient can't be constructed because HttpTimeout feature is not installed")
        val httpLoader = HttpLoader.Builder().okHttpClient(requestEngine).build()
        Fuel.setHttpLoader(httpLoader)

        val callContext = callContext()
        val engineRequest = data.convertToFuelRequest(callContext)

        val requestTime = GMTDate()
        val engineResponse = Fuel.method(engineRequest)
        val body = engineResponse.body
        callContext[Job]!!.invokeOnCompletion { body?.close() }

        val responseContent = body?.source()?.toChannel(callContext, data) ?: ByteReadChannel.Empty
        return buildResponseData(engineResponse, requestTime, responseContent, callContext)
    }

    private fun buildResponseData(
        response: Response, requestTime: GMTDate, body: Any, callContext: CoroutineContext
    ): HttpResponseData {
        val status = HttpStatusCode(response.code, response.message)
        val version = response.protocol.fromOkHttp()
        val headers = response.headers.fromOkHttp()

        return HttpResponseData(status, requestTime, headers, version, body, callContext)
    }

    private fun createOkHttpClient(timeoutExtension: HttpTimeout.HttpTimeoutCapabilityConfiguration?): OkHttpClient {
        val builder = OkHttpClient.Builder()
        builder.apply(config.config)
        config.proxy?.let { builder.proxy(it) }
        timeoutExtension?.let {
            builder.setupTimeoutAttributes(it)
        }

        return builder.build()
    }
}

private fun BufferedSource.toChannel(context: CoroutineContext, requestData: HttpRequestData): ByteReadChannel =
    GlobalScope.writer(context) {
        use { source ->
            var lastRead = 0
            while (source.isOpen && context.isActive && lastRead >= 0) {
                channel.write { buffer ->
                    lastRead = try {
                        source.read(buffer)
                    } catch (cause: Throwable) {
                        throw mapExceptions(cause, requestData)
                    }
                }
            }
        }
    }.channel

private fun mapExceptions(cause: Throwable, request: HttpRequestData): Throwable = when (cause) {
    is java.net.SocketTimeoutException -> SocketTimeoutException(request, cause)
    else -> cause
}

@InternalAPI
private fun HttpRequestData.convertToFuelRequest(callContext: CoroutineContext): Request {
    val builder = Request.Builder()

    with(builder) {
        data(url.toString().toHttpUrl())

        mergeHeaders(headers, body) { key, value ->
            addHeader(key, value)
        }

        method(method.value)

        val bodyBytes = if (HttpMethod.permitsRequestBody(method.value)) {
            body.convertToOkHttpBody(callContext)
        } else null

        requestBody(bodyBytes)
    }

    return builder.build()
}

internal fun OutgoingContent.convertToOkHttpBody(callContext: CoroutineContext): RequestBody? = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes().toRequestBody(null, 0)
    is OutgoingContent.ReadChannelContent -> StreamRequestBody(contentLength) { readFrom() }
    is OutgoingContent.WriteChannelContent -> {
        StreamRequestBody(contentLength) { GlobalScope.writer(callContext) { writeTo(channel) }.channel }
    }
    is OutgoingContent.NoContent -> ByteArray(0).toRequestBody(null, 0)
    else -> throw UnsupportedContentTypeException(this)
}

/**
 * Update [OkHttpClient.Builder] setting timeout configuration taken from
 * [HttpTimeout.HttpTimeoutCapabilityConfiguration].
 */
@InternalAPI
private fun OkHttpClient.Builder.setupTimeoutAttributes(
    timeoutAttributes: HttpTimeout.HttpTimeoutCapabilityConfiguration
): OkHttpClient.Builder {
    timeoutAttributes.connectTimeoutMillis?.let {
        connectTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
    }
    timeoutAttributes.socketTimeoutMillis?.let {
        readTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
        writeTimeout(convertLongTimeoutToLongWithInfiniteAsZero(it), TimeUnit.MILLISECONDS)
    }
    return this
}