plugins {
    application
}

application {
    mainClassName = "il.ac.technion.cs.softwaredesign.MainKt"
}
repositories {
    jcenter()
}
val junitVersion: String? by extra
val hamkrestVersion: String? by extra
val guiceVersion: String? by extra
val kotlinGuiceVersion: String? by extra
val mockkVersion: String? by extra
val externalLibraryVersion: String? by extra
val fuelVersion: String? by extra
val resultVersion: String? by extra
val completableFutureVersion: String? by extra
val listenableFutureVersion: String? by extra

dependencies {
    implementation(project(":library"))
    implementation("il.ac.technion.cs.softwaredesign", "primitive-storage-layer", externalLibraryVersion)
    implementation("com.google.inject", "guice", guiceVersion)
    implementation("dev.misfitlabs.kotlinguice4", "kotlin-guice", kotlinGuiceVersion)
    implementation ("com.github.kittinunf.fuel","fuel",fuelVersion)
    implementation("com.github.kittinunf.result","result",resultVersion)
    testImplementation("org.junit.jupiter", "junit-jupiter-api", junitVersion)
    testImplementation("org.junit.jupiter", "junit-jupiter-params", junitVersion)
    testImplementation("com.natpryce", "hamkrest", hamkrestVersion)
    testImplementation("io.mockk", "mockk", mockkVersion)
    // for completable future
    implementation("com.github.vjames19.kotlin-futures","kotlin-futures-jdk8",completableFutureVersion)
    // for listenable future
    implementation("com.github.vjames19.kotlin-futures","kotlin-futures-guava",listenableFutureVersion)
    // For main
    implementation("com.xenomachina", "kotlin-argparser", "2.0.7")
}
