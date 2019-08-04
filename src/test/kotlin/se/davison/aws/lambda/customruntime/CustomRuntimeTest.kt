package se.davison.aws.lambda.customruntime

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.google.gson.GsonBuilder
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.assertEquals

val GSON = GsonBuilder().create()

class TestHandler : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    override fun handleRequest(input: APIGatewayProxyRequestEvent, context: Context) = APIGatewayProxyResponseEvent()
            .withBody(GSON.toJson(input))
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

    val runtime = CustomRuntime()
    runtime.eventData = File(javaClass.classLoader.getResource("event.json")!!.file).reader().readText()


    Environment["_HANDLER"] = TestHandler::class.java.name

    describe("Test runtime") {

        it("should runtime without crashing") {

            val expected = GSON.toJson(
                    APIGatewayProxyResponseEvent().withBody(
                            GSON.toJson(GSON.fromJson(runtime.eventData, APIGatewayProxyRequestEvent::class.java))))

            assertEquals(expected, runtime.process())
        }

    }
})
