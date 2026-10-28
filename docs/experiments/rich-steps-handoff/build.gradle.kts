plugins { kotlin("jvm") version "2.2.21" }
repositories { mavenCentral() }
dependencies { testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2") }
kotlin { jvmToolchain(21) }
tasks.test { testLogging { showStandardStreams = true; events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
