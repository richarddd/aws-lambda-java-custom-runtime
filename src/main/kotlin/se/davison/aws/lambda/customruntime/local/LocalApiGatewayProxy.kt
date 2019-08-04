package se.davison.aws.lambda.customruntime.local

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpServer
import se.davison.aws.lambda.customruntime.util.Constants
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.Executors
import kotlin.random.Random

enum class RequestMethod {
    GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE
}

class LocalApiGatewayProxy {

    companion object {
        private val GSON = GsonBuilder().serializeNulls().create()
    }

    fun start() {

        try {
            startServer()
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    private fun startServer() {
        val server = HttpServer.create(InetSocketAddress(Constants.LOCAL_PORT), 10)
        server.createContext("/") {

            var response = ByteArray(0)

            try {

                val responseEvent = APIGatewayProxyRequestEvent().apply {
                    when (RequestMethod.valueOf(it.requestMethod)) {
                        RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH -> withBody(it.requestBody.bufferedReader().readText())
                    }
                    withPath(it.requestURI.path)
                    withHeaders(it.requestHeaders.mapValues {
                        it.value.joinToString()
                    })
                    it.requestURI.path.substring(1).substringAfter("/", "").let {
                        if (it.isNotBlank()) {
                            withPathParameters(mapOf("any" to it.dropLast(1)))
                        }
                    }
                    withHttpMethod(it.requestMethod)
                    withMultiValueHeaders(it.requestHeaders)

                    val multiQuery = it.requestURI.query?.let {
                        mutableMapOf<String, MutableList<String>>()
                    }
                    val parsedQuery = it.requestURI.query?.split("&")?.associate {
                        it.split("=").let {
                            val key = it[0]
                            val value = (it.getOrNull(1) ?: "")
                            multiQuery!!.getOrPut(key, { mutableListOf() }).add(value)
                            key to value
                        }
                    }

                    withQueryStringParameters(parsedQuery)
                    withMultiValueQueryStringParameters(multiQuery)
                    withRequestContext(APIGatewayProxyRequestEvent.ProxyRequestContext().apply {
                        resourceId = "mocked-id-${Random.nextInt()}"
                        accountId = "mocked-id-${Random.nextInt()}"
                        stage = "dev"
                        requestId = UUID.randomUUID().toString()
                        resourcePath = "/${it.requestURI.path.substring(1).let {
                            val slashIndex = it.indexOf("/")
                            if (slashIndex > -1) {
                                it.substring(0, slashIndex) + "/{any+}"
                            } else {
                                it
                            }
                        }}"
                        identity = APIGatewayProxyRequestEvent.RequestIdentity().apply {
                            sourceIp = "0.0.0.0"
                            userAgent = it.requestHeaders.getFirst("User-Agent")
                        }
                    })
                }

                response = GSON.toJson(responseEvent).toByteArray()
                it.sendResponseHeaders(200, response.size.toLong())
            } catch (exception: Exception) {
                response = GSON.toJson(exception).toByteArray()
                it.sendResponseHeaders(500, response.size.toLong())
            }
            it.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            val out = it.responseBody
            out.write(response)
            out.close()
        }
        server.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        server.start()
    }
}