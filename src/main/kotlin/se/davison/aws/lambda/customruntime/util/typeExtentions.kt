package se.davison.aws.lambda.customruntime.util

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

fun Any.getGenericTypeArguments(): List<Class<*>> {

    val typeParams = mutableListOf<Class<*>>()

    javaClass.genericSuperclass?.let {
        addGenericParams(it, typeParams)
    }
    javaClass.genericInterfaces.forEach {
        addGenericParams(it, typeParams)
    }

    return typeParams
}

private fun addGenericParams(it: Type, typeParams: MutableList<Class<*>>) {
    (it as? ParameterizedType)?.actualTypeArguments?.forEach {
        (it as? Class<*>)?.let {
            typeParams.add(it)
        }
    }
}