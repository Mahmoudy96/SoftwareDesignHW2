import Utils.statsStorage
import Utils.torrentStorage
import Utils.peerStorage
import Storage.*
import Storage.PeerStorage
import Storage.TorrentStorage
import com.google.inject.Inject
import com.google.inject.Provides
import com.google.inject.Singleton
import dev.misfitlabs.kotlinguice4.KotlinModule

import il.ac.technion.cs.softwaredesign.storage.impl.*
import il.ac.technion.cs.softwaredesign.storage.*
import java.util.concurrent.CompletableFuture

class LibraryModule : KotlinModule() {
    override fun configure() {
        bind<Peer>().to<PeerStorage>()
        bind<Statistics>().to<StatisticsStorage>()
        bind<Torrent>().to<TorrentStorage>()


    }

    //Binding with annotations(tutorial on Guice )
    @Provides
    @Singleton
    @Inject
    @torrentStorage
    fun provideTorrentStorage(factory: SecureStorageFactory): CompletableFuture<SecureStorage> {
        return factory.open("torrent".toByteArray())

    }

    @Provides
    @Singleton
    @Inject
    @statsStorage
    fun provideStatsStorage(factory: SecureStorageFactory): CompletableFuture<SecureStorage> {
        return factory.open("statistics".toByteArray())
    }

    @Provides
    @Singleton
    @Inject
    @peerStorage
    fun providePeerStorage(factory: SecureStorageFactory): CompletableFuture<SecureStorage> {
        return factory.open("peers".toByteArray())
    }
}