package se.davison.aws.lambda.customruntime.exception


class LambdaInitException(msg: String, cause: Exception?=null) : LambdaException(msg, cause)
