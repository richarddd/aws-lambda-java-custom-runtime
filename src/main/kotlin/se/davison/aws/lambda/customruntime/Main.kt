package se.davison.aws.lambda.customruntime

fun main(args: Array<String>) {
    CustomRuntime.process(args.getOrNull(0))
}