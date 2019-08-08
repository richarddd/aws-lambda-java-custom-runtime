# AWS Java Custom Runtime
Custom Runtime wrapper for Java written in Kotlin

## Installation

1. Add Jitpack to repos in gradle.build
    
    ```kotlin
    repositories {
       maven {
           url = uri("https://jitpack.io")
       }
    }
    ```

2. Add dependency
    ```kotlin
    dependencies {
        compile("com.github.richarddd:aws-lambda-java-custom-runtime:master-SNAPSHOT")
    }
    ```
## Usage

1. Set main class of your code to use `se.davison.aws.lambda.customruntime.MainKt` i.e:

    ```kotlin
    tasks.withType<Jar> {
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "se.davison.aws.lambda.customruntime.MainKt"
                )
            )
        }
    }
    ```

2. Build jar

3. Create an empty file named `bootstrap` and make it executable:

    ```bash
   sudo echo "" > boostrap && chmod 755 boostrap
   ```

### Java

1. Copy built jar to root directory and name it lambda.jar (You can name it whatever you wan't just make sure to change the script below to match the new name)

2. Add this to `bootstrap`

    ```bash
    #!/bin/sh
    set -euo pipefail
    java -jar lambda.jar
   ```

### GraalVM

1. Install docker

2. Build image
    ```
    docker build -t richarddavison/graalvm-aws-linux2:latest .
   ```

3. Setup GraalVM reflection (https://github.com/oracle/graal/blob/master/substratevm/REFLECTION.md)

    1. This could be done in many ways. One option is to specify a reflection json and pass to native compilation (`-H:ReflectionConfigurationFiles=reflect.json`). However that's extremely verbose.
    
        A better option is to use another library by me which solves these problems automatically using compile time providers, annotations and class scanning.
        
    2. Add this depedency `compile("com.github.richarddd:graal-auto-reflection:master-SNAPSHOT")`
    
    3. Create this provider class and modify according to your project:
    
       ```kotlin
       TODO
       ```
    
    4. Annotate all your response (stuff that you return from your handlers) classes with `@Reflect`
    
4. Compile native image 

    //TODO fix this
    ```
    native() { docker run -it -v "$(pwd):/project" --rm richarddavison/graalvm-aws-linux2:latest --static "$@"; }
    
    native -jar build/libs/serverless-dev-all.jar --no-server \
      --enable-all-security-services \
      -H:+ReportExceptionStackTraces \
      -H:ReflectionConfigurationFiles=reflect.json \
      -H:DynamicProxyConfigurationFiles=proxies.json \
      -H:IncludeResources=META-INF/services/*.* \
      -H:EnableURLProtocols=http,https \
      -H:IncludeResourceBundles=javax.servlet.LocalStrings \
      -H:IncludeResourceBundles=javax.servlet.http.LocalStrings \
      -H:-UseServiceLoaderFeature \
      -H:+TraceServiceLoaderFeature \
      -H:+AddAllCharsets \
      --initialize-at-build-time=kotlin.jvm.internal.Intrinsics \
      --initialize-at-build-time=org.eclipse.jetty.util.thread.TryExecutor \
      --initialize-at-build-time=java.lang.invoke.MethodHandle
    ```



    
