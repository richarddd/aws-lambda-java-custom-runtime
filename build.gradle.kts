plugins {
    `java-library`
    maven
    idea
    kotlin("jvm") version "1.3.41"
    id("com.adarshr.test-logger") version "1.7.0"
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

    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("com.amazonaws:aws-lambda-java-events:$awsLambdaEventsVersion")
    implementation("com.amazonaws:aws-lambda-java-core:$awsLambdaCoreVersion")

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

tasks.withType<Jar> {
    manifest {

        attributes(
                mapOf(
                        "Implementation-Title" to "Serverless",
                        "Implementation-Version" to project.version,
                        "Main-Class" to "se.davison.aws.lambda.customruntime.MainKt"
                )
        )
    }
}

testlogger {
    showStandardStreams = true
    //theme = ThemeType.MOCHA
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}