package se.davison.aws.lambda.customruntime.util

object Constants {
    const val RUNTIME_DATE = "2018-06-01"

    val LOCAL_PORT: Int = System.getenv("AWS_API_GATEWAY_PORT")?.toIntOrNull() ?: 3000
    val RUNTIME_API: String? = System.getenv("AWS_LAMBDA_RUNTIME_API")
    val IS_LOCAL = !(RUNTIME_API?.let { true } ?: false)
    val EVENT_PATH = System.getenv("EVENT_PATH") ?: "event.json"
    val HANDLER_CLASS: String? = System.getenv("_HANDLER")
}