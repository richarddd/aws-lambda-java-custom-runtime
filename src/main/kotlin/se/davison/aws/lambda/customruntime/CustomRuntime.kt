package se.davison.aws.lambda.customruntime

import com.amazonaws.services.lambda.runtime.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import se.davison.aws.lambda.customruntime.exception.LambdaInitException
import se.davison.aws.lambda.customruntime.exception.LambdaInvocationException
import se.davison.aws.lambda.customruntime.util.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.*
import java.util.logging.Logger

class CustomRuntime(var eventData: String? = null) {

    class NextInvocation(val id: String, val statusCode: Int, val handler: String, val headers: Map<String, String>)

    companion object {
        private val GSON = GsonBuilder().create()
        var errorTransformer: (exception: Exception) -> Exception = { it }
    }


    private fun nextInvocation() =
            if (Constants.IS_LOCAL) Triple(
                    mapOf("Lambda-Runtime-Aws-Request-Id" to "1"),
                    200,
                    eventData?.toByteArray()?.let(::ByteArrayInputStream) ?: File(Constants.EVENT_PATH).let {
                        if (!it.exists()) {
                            throw IllegalStateException("You are running lambda locally but cant find event data:\nYou need to pass json as arguments or create a file named event.json in root or set env \$EVENT_PATH to a file containing event json")
                        }
                        it.inputStream()
                    }
            ) else
                "http://${Constants.RUNTIME_API}/${Constants.RUNTIME_DATE}/runtime/invocation/next".openConnection().let {
                    Triple(
                            it.headerFields.entries.associate {
                                it.key to it.value.joinToString { it }
                            },
                            it.responseCode,
                            it.stream
                    )
                }

    private fun responseInvocation(requestId: String, data: Any) =
            if (Constants.IS_LOCAL) GSON.toJson(data) else
                responseConnection(requestId).post(
                        data,
                        GSON
                )

    private fun responseStream(requestId: String, block: (outputStream: OutputStream) -> Unit) =
            if (Constants.IS_LOCAL) ByteArrayOutputStream().let {
                block(it)
                it.flush()
                it.close()
                it.toString()
            } else
                responseConnection(requestId).post(block)


    private fun responseConnection(requestId: String) =
            "http://${Constants.RUNTIME_API}/${Constants.RUNTIME_DATE}/runtime/invocation/$requestId/response".openConnection()

    private fun <T : Exception> errorInvocation(exception: T, requestId: String? = null) {
        val transformed = errorTransformer(exception)
        transformed.printStackTrace()
        if (Constants.IS_LOCAL) GSON.toJson(transformed) else
            "http://${Constants.RUNTIME_API}/${Constants.RUNTIME_DATE}/runtime/${requestId?.let {
                "invocation/$requestId"
            } ?: "init"}/error".openConnection().post(
                    transformed,
                    GSON
            )
    }


    fun process(): String {

        var requestId: String? = null

        try {

            if (Constants.HANDLER_CLASS == null) {
                throw LambdaInitException("Malformed or missing handler name")
            }
            val handlerClass = Class.forName(Constants.HANDLER_CLASS)

            val instance = handlerClass.getConstructor().newInstance()
            var requestHandler: RequestHandler<Any, *>? = null
            var requestStreamHandler: RequestStreamHandler? = null
            @Suppress("UNCHECKED_CAST")
            when (instance) {
                is RequestHandler<*, *> -> requestHandler = instance as RequestHandler<Any, *>?
                is RequestStreamHandler -> requestStreamHandler = instance
                else -> throw LambdaInitException("Class ($handlerClass) does not implement RequestHandler or RequestStreamHandler interface")
            }

            val handlerRequestClass = requestHandler?.getGenericTypeArguments()?.get(0)

            LambdaContext.logger = Logger.getLogger(handlerClass.name)

            while (true) {
                val (headers, responseCode, stream) = nextInvocation()

                requestId = headers["Lambda-Runtime-Aws-Request-Id"]!!

                if (responseCode != 200) {
                    errorInvocation(
                            LambdaInvocationException("Next invocation error"),
                            requestId
                    )
                }

                val context = LambdaContext(requestId, headers)

                val responseData = if (requestHandler != null) {
                    responseInvocation(requestId, requestHandler.handleRequest(
                            GSON.fromJson(stream?.bufferedReader()?.readText(), handlerRequestClass),
                            context
                    ))
                } else {
                    responseStream(requestId) {
                        requestStreamHandler!!.handleRequest(stream, it, context)
                    }
                }

                if (Constants.IS_LOCAL) {
                    return responseData!!
                }
            }

        } catch (exception: Exception) {
            val mappedException = when (exception) {
                is ClassNotFoundException -> LambdaInitException("Class not found")
                is IllegalAccessException -> LambdaInitException("Unable to access class")
                is InstantiationException -> LambdaInitException("Unable to instantiate class")
                else -> exception
            }
            errorInvocation(
                    mappedException,
                    requestId
            )
        }
        return ""
    }

    class LambdaContext(private val requestId: String, private val headers: Map<String, String>) : Context {

        companion object {
            lateinit var logger: Logger
        }

        private val remainingTime: Int = (Date().time - (headers["Lambda-Runtime-Deadline-Ms"] ?: "0").toLong()).toInt()

        override fun getAwsRequestId() = requestId

        override fun getLogStreamName(): String = System.getenv("AWS_LAMBDA_LOG_STREAM_NAME") ?: "local"

        override fun getClientContext(): ClientContext {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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

        override fun getMemoryLimitInMB(): Int = (System.getenv("AWS_LAMBDA_FUNCTION_MEMORY_SIZE") ?: "1024").toInt()

        override fun getLogGroupName(): String = System.getenv("AWS_LAMBDA_LOG_GROUP_NAME") ?: "local"

        override fun getFunctionVersion(): String = System.getenv("AWS_LAMBDA_FUNCTION_VERSION") ?: "-1"

        override fun getIdentity(): CognitoIdentity {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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

}
