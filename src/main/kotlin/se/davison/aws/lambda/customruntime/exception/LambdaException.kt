package se.davison.aws.lambda.customruntime.exception

abstract class LambdaException(msg: String, cause: Exception? = null) : Exception(msg, cause) {
    val type: String = this::class.java.name
}