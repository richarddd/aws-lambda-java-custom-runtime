package se.davison.aws.lambda.customruntime.local

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import se.davison.aws.lambda.customruntime.util.Environment
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


typealias RequestMap<K, V> = EnumMap<Router.RequestMethod, MutableMap<K, V>>


class Router internal constructor(path: String = "") : HttpHandler {

    companion object {
        fun router(block: Router.() -> Unit): Router = Router().apply(block).apply {
            initialize()
        }
    }

    enum class RequestMethod {
        GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE
    }


    internal class RoutePart(private val part: String) {

        private val regExp = if (part.isNotEmpty() && part.first() == '(' && part.last() == ')') Regex(part) else null
        private val paramName = if (part.isNotEmpty() && part.first() == ':') part.substring(1) else null

        fun matches(part: String): Triple<Boolean, String?, String?> {
            if (regExp != null) {
                return Triple(part.matches(regExp), null, null)
            }
            if (paramName != null) {
                return Triple(true, paramName, part)
            }
            return Triple(this.part == part || this.part == "*", null, null)
        }
    }

    private val basePath = path.stripSlashes()
    private val routes: RequestMap<String, HttpExchange.() -> Unit> = EnumMap(RequestMethod::class.java)
    private val routeCandidates: RequestMap<Int, MutableList<String>> = EnumMap(RequestMethod::class.java)
    private val routeParts: RequestMap<String, List<RoutePart>> = EnumMap(RequestMethod::class.java)

    init {
        RequestMethod.values().forEach {
            this.routes[it] = mutableMapOf<String, HttpExchange.() -> Unit>()
            this.routeCandidates[it] = mutableMapOf<Int, MutableList<String>>()
        }

    }

    internal fun initialize() {
        routes.entries.forEach {
            val method = it.key
            it.value.entries.forEach {
                val parts = it.key.split("/")
                routeCandidates[method]!!.getOrPut(parts.size, ::ArrayList).add(it.key)
                routeParts.getOrPut(method, ::HashMap)[it.key] = parts.map(::RoutePart)
            }
        }
    }

    override fun handle(exchange: HttpExchange) {
        try {
            val requestMethod = RequestMethod.valueOf(exchange.requestMethod)
            val params = HashMap<String, String>()
            val path = exchange.requestURI.path
            val parts = path.stripSlashes().split("/")
            routes[requestMethod]?.keys?.find {
                it == "*" || it == findMatched(requestMethod, parts, params)
            }?.let {
                exchange.setAttribute(HttpExchange::requestParameters.name, params)
                routes[requestMethod]!![it]!!(exchange)
            } ?: exchange.notFound()
        } catch (exception: Exception) {
            exchange.errorResponse(500, exception.message ?: "Internal Server Error")
        }

    }

    private fun findMatched(requestMethod: RequestMethod, parts: List<String>, params: HashMap<String, String>): String? {
        var matchedRoute: String? = null
        routeCandidates[requestMethod]!![parts.size]?.let {
            for (route in it) {
                params.clear()
                for ((index, requestPart) in parts.withIndex()) {
                    val routePart = routeParts[requestMethod]!![route]!!.getOrNull(index)
                    if (routePart != null) {
                        val (match, paramKey, paramValue) = routePart.matches(requestPart)
                        if (paramKey != null && paramValue != null) {
                            params[paramKey] = paramValue
                        }
                        if (!match) {
                            break
                        }
                        if (index == parts.lastIndex) {
                            matchedRoute = route
                        }
                    } else {
                        break
                    }
                }
            }
        }
        return matchedRoute
    }

    fun path(path: String, block: Router.() -> Unit) {
        val nestedRouter = Router(path)
        block(nestedRouter)
        merge(nestedRouter)
    }

    private fun merge(nestedRouter: Router) {
        nestedRouter.routes.forEach {
            val method = it.key
            it.value.forEach {
                add(method, it.key, it.value)
            }
        }
    }


    private fun add(requestMethod: RequestMethod, path: String, block: HttpExchange.() -> Unit) {
        val basePath = if (basePath.isEmpty()) "" else "$basePath/"
        routes[requestMethod]!!["$basePath${path.stripSlashes()}"] = block
    }

    //httpMethods
    fun get(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.GET, path, block)
    }

    fun post(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.POST, path, block)
    }

    fun delete(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.DELETE, path, block)
    }

    fun put(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.PUT, path, block)
    }

    fun patch(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.PATCH, path, block)
    }

    fun head(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.HEAD, path, block)
    }

    fun options(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.OPTIONS, path, block)
    }

    fun trace(path: String, block: HttpExchange.() -> Unit) {
        add(RequestMethod.TRACE, path, block)
    }

    fun HttpExchange.notFound() {
        textResponse(404, "Not found")
    }

    //extention functions
    fun HttpExchange.textResponse(status: Int, text: String) {
        val response = text.toByteArray()
        sendResponseHeaders(status, response.size.toLong())
        val out = this.responseBody
        out.write(response)
        out.close()
    }

    fun HttpExchange.emptyResponse(status: Int) {
        textResponse(200, "")
    }

    fun HttpExchange.errorResponse(status: Int, message: String) {
        textResponse(500, "message")
    }

    fun HttpExchange.contentType(type: String) {
        this.responseHeaders.add("Content-Type", type)
    }

    fun String.stripSlashes() = if (this.isEmpty()) this else (if (this.startsWith("/")) this.substring(1) else this).let {
        if (it.endsWith("/")) {
            it.dropLast(1)
        } else {
            it
        }
    }
}

//extention functions
fun HttpServer.router(block: Router.() -> Unit): HttpServer {
    this.createContext("/", Router.router(block))
    return this
}

fun HttpServer.default(port: Int = 3000): HttpServer {
    val server = HttpServer.create(InetSocketAddress(Environment.LOCAL_PORT), 10)
    return this
}

@Suppress("UNCHECKED_CAST")
val HttpExchange.requestParameters: Map<String, String>
    get() = getAttribute(HttpExchange::requestParameters.name) as Map<String, String>

fun InputStream.readText(): String = this.bufferedReader().readText()


