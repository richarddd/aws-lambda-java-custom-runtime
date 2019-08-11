# AWS Java Custom Runtime
Custom Runtime wrapper for Java written in Kotlin

## Installation

1. Add Jitpack to repos in gradle.build.kts
    
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

2. Setup GraalVM reflection (https://github.com/oracle/graal/blob/master/substratevm/REFLECTION.md)

    1. This could be done in many ways. One option is to specify a reflection json and pass to native compilation (`-H:ReflectionConfigurationFiles=reflect.json`). However that's extremely verbose.
    
        A better option is to use another library by me which solves these problems automatically using compile time providers, annotations and class scanning.
        
    2. Add this depedency `compile("com.github.richarddd:graal-auto-reflection:master-SNAPSHOT")`
    
    3. Create this provider class and modify according to your project.
    *NOTE*: It's important that you stay away from Kotlin methods in here and use Stream API as this class runs during GraalVM build time. It is of course possible to use Kotlin here as well, but those classes needs to be initialized at build-time using Native-Image configuration.
 
       ```kotlin
       class AwsReflectionProvider : ReflectionProvider {
           override fun packages(classGraph: ClassGraph) = ArrayList<String>()
       
           override fun classes(classGraph: ClassGraph): List<Class<*>> {
               return classGraph.enableClassInfo().scan().let {
                   Stream.concat(
                       it.getClassesImplementing(RequestStreamHandler::class.java.name).stream().map { it.loadClass() },
                       it.getClassesImplementing(RequestHandler::class.java.name).stream().flatMap {
                           Stream.concat(
                               Stream.of(it.loadClass()),
                               it.typeSignature.superinterfaceSignatures[0].typeArguments.stream().map {
                                   (it.typeSignature as ClassRefTypeSignature).loadClass()
                               })
                       }
                   )
                       .distinct().toList()
               }
           }
       
       
       }
       
       
       @Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")
       class ReflectionData : ReflectionProvider {
           override fun packages(classGraph: ClassGraph) = ArrayList<String>()
       
           override fun classes(classGraph: ClassGraph) = Arrays.asList(APIGatewayProxyResponseEvent::class.java)
       }
       ```
    
    4. Annotate all your response (stuff that you return from your handlers) classes with `@Reflect`
    
4. Compile native image.
    
    Copy this install script to your project directory.

    ```bash
    sudo mkdir  -p ./scripts && \
    wget -O ./scripts/build.sh "https://github.com/richarddd/aws-lambda-java-custom-runtime/raw/master/build.sh" && \
    chmod 755 ./scripts/build.sh
   ```
    
5. Use script to build native binary.

    `./scripts/build.sh`
    
    (To build and run on your machine use: `./scripts/build.sh local`)

6. Add this to `bootstrap`

    ```bash
    #!/bin/sh
    ./runtime
   ```

#### Graal buildscript arguments
You could use `--args` to pass extra arguments to your GraalVM native-image build script, i.e: `./scripts/build.sh --args -H:+ReportExceptionStackTraces`



    
