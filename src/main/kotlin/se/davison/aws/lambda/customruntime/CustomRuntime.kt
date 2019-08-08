package se.davison.aws.lambda.customruntime

import com.amazonaws.services.lambda.runtime.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import se.davison.aws.lambda.customruntime.exception.LambdaInitException
import se.davison.aws.lambda.customruntime.exception.LambdaInvocationException
import se.davison.aws.lambda.customruntime.local.LocalApiGatewayProxy
import se.davison.aws.lambda.customruntime.util.*
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.logging.Logger
import kotlin.system.exitProcess

class CustomRuntime private constructor(private val handlerConfig: String? = null) {

    class NextInvocation(
            val id: String,
            val statusCode: Int,
            val stream: InputStream?,
            val headers: Map<String, String>,
            val handlerClass: String?)

    class Handlers(var requestHandler: RequestHandler<Any, *>? = null,
                   var requestStreamHandler: RequestStreamHandler? = null) {
        var requestHandlerClass: Class<*>? = null

        fun clear() {
            requestHandler = null
            requestStreamHandler = null
        }

        fun create(nextInvocation: NextInvocation) {
            if (requestHandler !== null || requestStreamHandler !== null) {
                return
            }
            val handlerClass = (if (!Environment.IS_LOCAL) Environment.HANDLER_CLASS else nextInvocation.handlerClass)?.let {
                Class.forName(it)
            } ?: throw LambdaInitException("Malformed or missing handler name")
            val instance = handlerClass.getConstructor().newInstance()
            @Suppress("UNCHECKED_CAST")
            when (instance) {
                is RequestHandler<*, *> -> requestHandler = instance as RequestHandler<Any, *>?
                is RequestStreamHandler -> requestStreamHandler = instance
                else -> throw LambdaInitException("Class ($handlerClass) does not implement RequestHandler or RequestStreamHandler interface")
            }

            requestHandlerClass = requestHandler?.getGenericTypeArguments()?.get(0)
            LambdaContext.logger = Logger.getLogger(handlerClass.simpleName)
        }
    }

    companion object {
        fun process(handlerConfig: String?) {
            CustomRuntime(handlerConfig).process()
        }

        const val REQUEST_ID_HEADER_NAME = "Lambda-Runtime-Aws-Request-Id"
        const val LOCAL_HANDLER_HEADER_NAME = "Local-Handler-Class"

        var errorTransformer: (exception: Exception) -> Exception = { it }


        private fun nextUrl() = "${Environment.RUNTIME_BASE_URL}/invocation/next"
        private fun responseUrl(requestId: String) = "${Environment.RUNTIME_BASE_URL}/invocation/$requestId/response"
        private fun errorUrl(requestId: String? = null) = "${Environment.RUNTIME_BASE_URL}/${requestId?.let {
            "invocation/$requestId"
        } ?: "init"}/error"


        private val GSON = GsonBuilder().create()

        var onFatalError: (exception: Exception) -> Unit = {
            exitProcess(1)
        }
    }


    private fun nextInvocation() = nextUrl().openConnection().let {
        val headers = it.headerFields.entries.associate {
            (it.key?.toLowerCase() ?: "") to it.value.joinToString { it }
        }
        NextInvocation(headers[REQUEST_ID_HEADER_NAME.toLowerCase()]!!, it.responseCode, it.stream, headers, headers[LOCAL_HANDLER_HEADER_NAME.toLowerCase()])
    }

    private fun responseInvocation(requestId: String, data: Any) =
            responseUrl(requestId).openConnection().post(
                    data,
                    GSON
            )

    private fun responseStream(requestId: String, block: (outputStream: OutputStream) -> Unit) =
            responseUrl(requestId).openConnection().post(block)


    private fun <T : Exception> errorInvocation(exception: T, requestId: String? = null) {
        val transformed = errorTransformer(exception)
        transformed.printStackTrace()
        errorUrl(requestId).openConnection().post(
                transformed,
                GSON
        )
    }

    private fun processMultiThreaded() {
        for (i in 0..Runtime.getRuntime().availableProcessors()) {
            Thread {
                processSingleThreaded()
            }.start()
        }
    }

    private fun processSingleThreaded() {

        val handlers = Handlers()

        while (true) {
            var requestId: String? = null
            try {
                val nextInvocation = nextInvocation()

                handlers.create(nextInvocation)
                requestId = nextInvocation.id

                if (nextInvocation.statusCode != 200) {
                    errorInvocation(
                            LambdaInvocationException("Next invocation error"),
                            requestId
                    )
                }

                val context = LambdaContext(requestId, nextInvocation.headers)

                if (handlers.requestHandler != null) {
                    responseInvocation(requestId, handlers.requestHandler!!.handleRequest(
                            GSON.fromJson(nextInvocation.stream?.bufferedReader()?.readText(), handlers.requestHandlerClass),
                            context
                    ))
                } else {
                    responseStream(requestId) {
                        handlers.requestStreamHandler!!.handleRequest(nextInvocation.stream, it, context)
                    }
                }

                if (Environment.IS_LOCAL) {
                    handlers.clear()
                }
            } catch (exception: Exception) {
                val mappedException = when (exception) {
                    is NullPointerException -> LambdaInitException("Nullpointer exception", exception)
                    is ClassNotFoundException -> LambdaInitException("Class not found", exception)
                    is IllegalAccessException -> LambdaInitException("Unable to access class", exception)
                    is InstantiationException -> LambdaInitException("Unable to instantiate class", exception)
                    else -> exception
                }
                errorInvocation(
                        mappedException,
                        requestId
                )
                if (mappedException is LambdaInitException) {
                    onFatalError(mappedException)
                }
            }

        }
    }

    fun process() {


        if (Environment.IS_LOCAL) {
            LocalApiGatewayProxy.start(handlerConfig
                    ?: throw IllegalArgumentException("""Runtime is running on a local machine, you must pass in handler json in order to run it locally, i.e:
                        |{
                        |   "GET" : [
                        |       { "/hello" : "example.Hello" }
                        |   ],
                        |   "ANY" : [
                       |       { "/example" : "example.Example" }
                        |   ]
                        |}
                        |
                    """.trimMargin()))
            processMultiThreaded()
        } else {
            processSingleThreaded()
        }
    }

}


class LambdaContext(private val requestId: String, private val headers: Map<String, String>) : Context {

    companion object {
        lateinit var logger: Logger
    }

    private val remainingTime: Int = (Date().time - (headers["Lambda-Runtime-Deadline-Ms"]
            ?: "0").toLong()).toInt()

    override fun getAwsRequestId() = requestId

    override fun getLogStreamName(): String = System.getenv("AWS_LAMBDA_LOG_STREAM_NAME") ?: "local"

    override fun getClientContext(): ClientContext {
        throw NotImplementedError("not implemented")
    }

    override fun getFunctionName(): String = System.getenv("AWS_LAMBDA_FUNCTION_NAME")

    override fun getRemainingTimeInMillis() = remainingTime

    override fun getLogger(): LambdaLogger = object : LambdaLogger {
        override fun log(message: String?) {
            logger.log(message)
        }

        override fun log(message: ByteArray?) {
            logger.log(message)
        }

    }

    override fun getInvokedFunctionArn(): String = headers["Lambda-Runtime-Invoked-Function-Arn"] ?: ""

    override fun getMemoryLimitInMB(): Int = (System.getenv("AWS_LAMBDA_FUNCTION_MEMORY_SIZE")
            ?: "1024").toInt()

    override fun getLogGroupName(): String = System.getenv("AWS_LAMBDA_LOG_GROUP_NAME") ?: "local"

    override fun getFunctionVersion(): String = System.getenv("AWS_LAMBDA_FUNCTION_VERSION") ?: "-1"

    override fun getIdentity(): CognitoIdentity {
        throw NotImplementedError("not implemented")
    }

}

private operator fun JsonElement.get(key: String): JsonElement? {
    return (this as? JsonObject)?.get(key)
}

private operator fun JsonElement.get(index: Int): JsonElement? {
    return (this as? JsonArray)?.let {
        if (index < size()) {
            return get(index)
        }
        null
    }
}
