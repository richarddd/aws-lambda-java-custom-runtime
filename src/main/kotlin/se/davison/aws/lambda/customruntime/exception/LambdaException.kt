package se.davison.aws.lambda.customruntime.exception

abstract class LambdaException(msg: String) : Exception(msg) {
    val type: String = this::class.java.name
}