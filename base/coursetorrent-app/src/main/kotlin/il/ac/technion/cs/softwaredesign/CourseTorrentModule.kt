package il.ac.technion.cs.softwaredesign

import LibraryModule
import il.ac.technion.cs.softwaredesign.storage.*
import dev.misfitlabs.kotlinguice4.KotlinModule
import il.ac.technion.cs.softwaredesign.storage.impl.*

class CourseTorrentModule : KotlinModule() {
    override fun configure() {
        install(SecureStorageModule())
        install(LibraryModule())
        bind<CourseTorrent>().to<CourseTorrentImpl>()

    }
}

