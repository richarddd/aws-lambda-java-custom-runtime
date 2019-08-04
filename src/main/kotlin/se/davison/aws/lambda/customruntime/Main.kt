package se.davison.aws.lambda.customruntime

import se.davison.aws.lambda.customruntime.local.LocalApiGatewayProxy

fun main(args: Array<String>) {
    LocalApiGatewayProxy().start()
    //CustomRuntime(args.getOrNull(0)).process().also(::println)
}