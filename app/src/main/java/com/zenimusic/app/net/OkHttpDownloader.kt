package com.zenimusic.app.net

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/** NewPipe Extractor downloader backed by OkHttp. */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        for ((name, values) in request.headers()) {
            for (v in values) builder.addHeader(name, v)
        }
        val data = request.dataToSend()
        if (data != null && request.httpMethod() != "GET") {
            val contentType = request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull()
            builder.post(data.toRequestBody(contentType))
        } else {
            builder.get()
        }
        client.newCall(builder.build()).execute().use { resp ->
            val headers = mutableMapOf<String, List<String>>()
            for (name in resp.headers.names()) {
                headers[name] = resp.headers.values(name)
            }
            return Response(
                resp.code,
                resp.message,
                headers,
                resp.body?.string(),
                resp.request.url.toString()
            )
        }
    }
}
