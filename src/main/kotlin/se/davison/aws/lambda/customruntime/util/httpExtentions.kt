package se.davison.aws.lambda.customruntime.util

import com.google.gson.Gson
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

fun HttpURLConnection.post(data: Any, gson: Gson): String? {
    requestMethod = "POST"
    doOutput = true
    val outputWriter = OutputStreamWriter(outputStream)
    gson.toJson(data, outputWriter)
    outputWriter.flush()
    return textResult
}

fun HttpURLConnection.post(block: (outputStream: OutputStream) -> Unit): String? {
    requestMethod = "POST"
    doOutput = true
    block(outputStream)
    outputStream.flush()
    outputStream.close()
    return textResult
}

@Suppress("USELESS_CAST")
val HttpURLConnection.stream: InputStream?
    get() = (if (this.responseCode == 200) this.inputStream else this.errorStream) as? InputStream

@Suppress("USELESS_CAST")
val HttpURLConnection.textResult: String?
    get() = stream?.use {
        it.bufferedReader().readText()
    }

fun String.openConnection() = URL(this).openConnection().let {
    it as HttpURLConnection
}