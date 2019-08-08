package se.davison.aws.lambda.customruntime.local

import java.util.*
import kotlin.collections.ArrayList


enum class RuntimeRoute(val route: String) {
    NEXT("/invocation/next"),
    RESPONSE("/invocation/:requestId/response"),
    INVOCATION_ERROR("/invocation/:requestId/error"),
    INITIALIZE_ERROR("/init/error");


    override fun toString(): String {
        return route
    }

    private val paramFunctions: MutableMap<RuntimeRoute, MutableList<String>> by lazy {
        val map = HashMap<RuntimeRoute, MutableList<String>>()
        val fixedParts = mutableListOf<String>()
        values().forEach {
            val parts = (if (it.route.startsWith("/")) it.route.substring(1) else it.route).split("/")
            var add = false
            fixedParts.clear()
            parts.forEach {
                if (it.startsWith(":")) {
                    add = true
                    fixedParts.add(it.substring(1))
                } else {
                    fixedParts.add(it)
                }
            }
            if (add) {
                map[it] = ArrayList(fixedParts)
            }
        }
        map
    }


    fun withParams(params: Map<String, String>) =
            paramFunctions[this]?.let {
                "/${it.joinToString("/") {
                    params[it] ?: it
                }}"
            } ?: this.route
}