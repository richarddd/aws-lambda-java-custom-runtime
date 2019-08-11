package se.davison.aws.lambda.customruntime.local

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.sun.net.httpserver.HttpServer
import se.davison.aws.lambda.customruntime.CustomRuntime
import se.davison.aws.lambda.customruntime.util.Environment
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.logging.Level
import java.util.logging.Logger


class ResponseLock {
    var response: String = "TIMEOUT"
    val latch = CountDownLatch(1)
}

class RequestEvent(val id: String, val json: String, val method: String, val path: String)

class HandlerMatcher(val path: String, val handler: String) {
    private val regex = Regex(path)
    fun matches(test: String) = test.startsWith(path) || regex.matches(path)
}

class LocalApiGatewayProxy(handlerConfig: String) {

    private val handlers = GSON.fromJson<Map<String, Map<String, String>>>(handlerConfig, object : TypeToken<Map<String, Map<String, String>>>() {}.type).mapValues {
        it.value.entries.map {
            HandlerMatcher(it.key, it.value)
        }
    }

    private var server: HttpServer? = null

    companion object {
        private val GSON = GsonBuilder().serializeNulls().create()

        private val LOGGER = Logger.getLogger(LocalApiGatewayProxy::class.java.simpleName)

        private var instance: LocalApiGatewayProxy? = null

        fun start(handlerConfig: String) {
            instance = instance ?: LocalApiGatewayProxy(handlerConfig)
            instance?.start()
        }

        fun stop() {
            instance?.stop()
        }
    }

    private fun stop() {
        println("Shutting down local server")
        this.server?.stop(0)
    }

    private fun start() {
        try {
            startServer()
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    private fun startServer() {
        val requestEventQueue = SynchronousQueue<RequestEvent>()
        val responsesMap = ConcurrentHashMap<String, ResponseLock>()
        val server = HttpServer.create(InetSocketAddress(Environment.LOCAL_PORT), 10)
        server.router {
            path("${Environment.RUNTIME_DATE}/runtime") {
                path("/invocation") {
                    get("/next") {
                        val requestEvent = requestEventQueue.take()

                        val handlerName = (handlers[requestEvent.method]
                                ?: handlers["ANY"])?.find {
                            it.matches(requestEvent.path)
                        }?.handler

                        responseHeaders.add(CustomRuntime.REQUEST_ID_HEADER_NAME, requestEvent.id)
                        responseHeaders.add(CustomRuntime.LOCAL_HANDLER_HEADER_NAME, handlerName)

                        log("Processing with handler $handlerName", requestEvent.id)

                        contentType("application/json")
                        textResponse(200, requestEvent.json)
                    }
                    get("/:requestId/error") {
                        responsesMap[this.requestParameters["requestId"]]?.let {
                            it.response = this.requestBody.readText()
                            it.latch.countDown()
                        }
                        emptyResponse(200)
                    }
                    post("/:requestId/response") {
                        val requestId = this.requestParameters["requestId"]
                        responsesMap[requestId]?.let {
                            it.response = this.requestBody.readText()
                            it.latch.countDown()
                            log("Handler responded", requestId!!)
                        }
                        emptyResponse(200)
                    }
                }

                get("/init/error") {
                    responseHeaders.add(CustomRuntime.REQUEST_ID_HEADER_NAME, "aaaa")
                    textResponse(200, "Init error")
                }
            }
            get("*") {
                if (this.requestURI.path.contains(Regex("""\.\w{2,4}$"""))) {
                    emptyResponse(400)
                    return@get
                }


                val httpExchange = this
                val requestId = UUID.randomUUID().toString()
                val proxyEvent = APIGatewayProxyRequestEvent().apply {

                    when (Router.RequestMethod.valueOf(httpExchange.requestMethod)) {
                        Router.RequestMethod.POST, Router.RequestMethod.PUT, Router.RequestMethod.PATCH -> withBody(httpExchange.requestBody.bufferedReader().readText())
                    }
                    withPath(httpExchange.requestURI.path)
                    withHeaders(httpExchange.requestHeaders.mapValues {
                        it.value.joinToString()
                    })
                    httpExchange.requestURI.path.substring(1).substringAfter("/", "").let {
                        if (it.isNotBlank()) {
                            withPathParameters(mapOf("any" to it.dropLast(1)))
                        }
                    }
                    withHttpMethod(httpExchange.requestMethod)
                    withMultiValueHeaders(httpExchange.requestHeaders)

                    val multiQuery = httpExchange.requestURI.query?.let {
                        mutableMapOf<String, MutableList<String>>()
                    }
                    val parsedQuery = httpExchange.requestURI.query?.split("&")?.associate {
                        it.split("=").let {
                            val key = it[0]
                            val value = (it.getOrNull(1) ?: "")
                            multiQuery!!.getOrPut(key, ::mutableListOf).add(value)
                            key to value
                        }
                    }

                    withQueryStringParameters(parsedQuery)
                    withMultiValueQueryStringParameters(multiQuery)
                    withRequestContext(APIGatewayProxyRequestEvent.ProxyRequestContext().apply {
                        resourceId = "mocked-id-${Random().nextInt()}"
                        accountId = "mocked-id-${Random().nextInt()}"
                        stage = "dev"
                        this.requestId = requestId
                        resourcePath = "/${httpExchange.requestURI.path.substring(1).let {
                            val slashIndex = it.indexOf("/")
                            if (slashIndex > -1) {
                                it.substring(0, slashIndex) + "/{any+}"
                            } else {
                                it
                            }
                        }}"
                        identity = APIGatewayProxyRequestEvent.RequestIdentity().apply {
                            sourceIp = "0.0.0.0"
                            userAgent = httpExchange.requestHeaders.getFirst("User-Agent")
                        }
                    })
                }

                log("Incoming event ${proxyEvent.httpMethod} (${proxyEvent.path})", requestId)

                val eventJson = GSON.toJson(proxyEvent)
                responsesMap[requestId] = ResponseLock()
                requestEventQueue.offer(RequestEvent(requestId, eventJson, proxyEvent.httpMethod, proxyEvent.path), 1, SECONDS)
                responsesMap[requestId]?.latch?.await(30, SECONDS)
                contentType("application/json")
                textResponse(200, responsesMap[requestId]!!.response)
            }
        }
        //twice as many as running lambda processors
        server.executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2)
        server.start()
        this.server = server
        println("""
  _____         __             ___            __  _          
 / ___/_ _____ / /____  __ _  / _ \__ _____  / /_(_)_ _  ___ 
/ /__/ // (_-</ __/ _ \/  ' \/ , _/ // / _ \/ __/ /  ' \/ -_)
\___/\_,_/___/\__/\___/_/_/_/_/|_|\_,_/_//_/\__/_/_/_/_/\__/ 
                                                                                                                       
        """.trimIndent())
        println("Started local server at: http://localhost:${Environment.LOCAL_PORT}")
    }

    private fun log(message: String, requsetId: String) {
        LOGGER.log(Level.INFO, "Proxy (requestId=$requsetId): $message")
    }


}
