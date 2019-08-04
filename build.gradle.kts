plugins {
    java
    maven
    idea
    kotlin("jvm") version "1.3.41"
}

group = "se.davison.aws.lambda.customruntime"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://dl.bintray.com/spekframework/spek")
    }
}

//kotlin
val kotlinVersion = "1.3.41"

//deps
val awsLambdaCoreVersion = "1.2.0"
val awsLambdaEventsVersion = "2.2.6"
val gsonVersion = "2.8.5"

//tests
val junitPlatformVersion = "1.5.0"
val spekVersion = "2.0.5"

dependencies {

    implementation(kotlin("stdlib-jdk8"))

    compile("com.google.code.gson:gson:$gsonVersion")
    compile("com.amazonaws:aws-lambda-java-events:$awsLambdaEventsVersion")
    compile("com.amazonaws:aws-lambda-java-core:$awsLambdaCoreVersion")

    testImplementation(kotlin("reflect", kotlinVersion))
    testImplementation(kotlin("test", kotlinVersion))

    testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spekVersion") {
        exclude(group = "org.jetbrains.kotlin")
    }

    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:$spekVersion") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.junit.platform")
    }

    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitPlatformVersion") {
        because("Needed to run tests IDEs that bundle an older version")
    }
    testImplementation(gradleTestKit())
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("spek2")
    }
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}