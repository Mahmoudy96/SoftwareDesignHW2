//import org.jetbrains.dokka.gradle.DokkaTask
//
//plugins {
//    application
//    id("org.jetbrains.dokka") version "0.10.1"
//}
//repositories {
//    jcenter()
//}
val externalLibraryVersion: String? by extra
val junitVersion: String? by extra
val hamkrestVersion: String? by extra
val guiceVersion: String? by extra
val kotlinGuiceVersion: String? by extra
val fuelVersion: String? by extra
val resultVersion: String? by extra

dependencies {
    implementation("il.ac.technion.cs.softwaredesign", "primitive-storage-layer", externalLibraryVersion)
    implementation("com.github.kittinunf.fuel", "fuel", fuelVersion)
    implementation("com.google.inject", "guice", guiceVersion)
    implementation("dev.misfitlabs.kotlinguice4", "kotlin-guice", kotlinGuiceVersion)
    implementation("com.github.kittinunf.result", "result", resultVersion)

    testImplementation("org.junit.jupiter", "junit-jupiter-api", junitVersion)
    testImplementation("org.junit.jupiter", "junit-jupiter-params", junitVersion)
    testImplementation("com.natpryce", "hamkrest", hamkrestVersion)
}


//tasks {
//    val dokka by getting(DokkaTask::class) {
//        outputFormat = "html"
//        outputDirectory = "$buildDir/dokka"
//    }
//}