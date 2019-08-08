package se.davison.aws.lambda.customruntime.util

object Environment {
    const val RUNTIME_DATE = "2018-06-01"

    val LOCAL_PORT: Int by lazy {
        System.getenv("AWS_API_GATEWAY_PORT")?.toIntOrNull() ?: 3000
    }
    val RUNTIME_API: String by lazy {
        System.getenv("AWS_LAMBDA_RUNTIME_API") ?: "localhost:$LOCAL_PORT"
    }
    val IS_LOCAL: Boolean by lazy {
        System.getenv("AWS_LAMBDA_RUNTIME_API") == null
    }
    val HANDLER_CLASS: String? by lazy {
        System.getenv("_HANDLER")
    }

    val RUNTIME_BASE_URL = "http://${Environment.RUNTIME_API}/${Environment.RUNTIME_DATE}/runtime"
}