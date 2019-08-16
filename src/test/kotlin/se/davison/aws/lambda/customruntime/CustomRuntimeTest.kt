package se.davison.aws.lambda.customruntime

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.google.gson.GsonBuilder
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import se.davison.aws.lambda.customruntime.util.openConnection
import se.davison.aws.lambda.customruntime.util.textResult
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.ConnectException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue


val GSON = GsonBuilder().create()

class TestHandler : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    override fun handleRequest(input: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent? {
        return APIGatewayProxyResponseEvent()
                .withBody(GSON.toJson(mapOf("a" to "b")))
    }
}

class BrokenHandler : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    override fun handleRequest(input: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent? {
        throw Exception("Something went wrong")
    }
}

object Environment {

    private val environment = Class.forName("java.lang.ProcessEnvironment")
            .getDeclaredField("theUnmodifiableEnvironment").let {
                it.isAccessible = true;

                val modifiersField = Field::class.java.getDeclaredField("modifiers")
                modifiersField.isAccessible = true
                modifiersField.setInt(it, it.modifiers and Modifier.FINAL.inv())

                @Suppress("UNCHECKED_CAST")
                val environmentMap = (it.get(null) as Map<String, String>).toMutableMap()

                it.set(null, environmentMap)
                environmentMap
            }

    operator fun set(name: String, value: String) {
        environment[name] = value
    }

    operator fun get(name: String) = environment[name]
}


@Suppress("unused")
object CustomRuntimeTest : Spek({

    Environment["_HANDLER"] = TestHandler::class.java.name

    val handlers = mapOf(
            "ANY" to mapOf(
                    "/hello" to TestHandler::class.java.name,
                    "/broken" to BrokenHandler::class.java.name
            )
    )


    group("JVM") {

        beforeGroup {
            CustomRuntime.process(GSON.toJson(handlers))
            CustomRuntime.onError = {
                if (it !is ConnectException) {
                    it.printStackTrace()
                }
            }
            CustomRuntime.onFatalError = {
                if (it !is ConnectException) {
                    throw it
                }

            }
        }

        describe("Test runtime") {


            val expectation = GSON.toJson(mapOf("body" to GSON.toJson(mapOf("a" to "b"))))

            //make sure server is up
            Thread.sleep(1000)

            it("should handle a single request") {
                duration {
                    assertEquals(expectation, "http://localhost:3000/hello".openConnection().textResult)
                }.print()
            }

            it("should handle several requests") {
                duration {
                    assertEquals(expectation, "http://localhost:3000/hello".openConnection().textResult)
                    assertEquals(expectation, "http://localhost:3000/hello".openConnection().textResult)
                    assertEquals(expectation, "http://localhost:3000/hello".openConnection().textResult)
                }.print()
            }

            it("should handle several concurrent requests") {

                val count = max(Runtime.getRuntime().availableProcessors() / 2, 1)

                val duration = duration {
                    val expectations = Array(count) {
                        expectation
                    }.toList()
                    val results = Parallel.waitAll(Array(count) {
                        Callable {
                            "http://localhost:3000/hello".openConnection().textResult
                        }
                    }.toList())
                    assertEquals(expectations, results)
                }.print()

                assertTrue(duration.toMillis() < 40)
            }

            it("should return exception of lambda throws") {

                val exception = GSON.toJson(Exception("Something went wrong"))

                duration {
                    assertEquals(exception, "http://localhost:3000/broken".openConnection().textResult)
                }.print()
            }
        }

        afterGroup {
            CustomRuntime.stop()
        }
    }

    group("GraalVM") {

        //        beforeGroup {
//            val builder = ProcessBuilder("native-image", "-jar", File("./build/libs").listFiles { dir, name -> name.endsWith(".jar") }!!.maxBy {
//                it.length()
//            }.toString(), "--no-server", "--enable-all-security-services").inheritIO()
//            builder.start().waitFor()
//        }
//
//        describe("Test GraalVM Runtime") {
//
//            it("should be able to run using GraalVM") {
//
//            }
//        }


    }


})

private fun Duration.print(): Duration {
    println("\u001b[0;94m" + "Duration: ${this.toMillis()}ms" + "\u001b[0m")
    return this
}

fun duration(function: () -> Unit): Duration {
    val start = Instant.now()
    function()
    return Duration.between(start, Instant.now())
}

class Parallel private constructor() {

    companion object {
        fun <T> waitAll(tasks: List<Callable<T>>, threadCount: Int = -1): List<T> {
            val threads = if (threadCount == -1) Runtime.getRuntime().availableProcessors().let {
                if (it > tasks.size) {
                    return@let tasks.size
                }
                it
            } else threadCount
            val executor = Executors.newFixedThreadPool(threads)
            val results = executor.invokeAll(tasks).map {
                it.get()
            }
            executor.shutdown()
            return results
        }
    }
}
