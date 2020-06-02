package il.ac.technion.cs.softwaredesign

import LibraryModule
import Utils.HTTPGet
import dev.misfitlabs.kotlinguice4.KotlinModule
import il.ac.technion.cs.softwaredesign.storage.*


class CourseTorrentTestModule: KotlinModule()  {
    override fun configure() {
        bind<SecureStorageFactory>().toInstance(TestSecureStorageFactory())
        bind<SecureStorage>().toInstance(TestSecureStorage())
        install(LibraryModule())
        bind<CourseTorrent>().to<CourseTorrentImpl>()
    }
}
