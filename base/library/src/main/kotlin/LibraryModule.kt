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
        bind<torrentStats>().to<torrentStatistics>()
        bind<File>().to<FileStorage>()
        bind<Pieces>().to<torrentPieces>()


    }

    //Binding with annotations(tutorial on Guice )
    @Provides
    @Singleton
    @Inject
    @torrentStorage
    fun provideTorrentStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("torrent".toByteArray()).get()

    }

    @Provides
    @Singleton
    @Inject
    @statsStorage
    fun provideStatsStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("statistics".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @peerStorage
    fun providePeerStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("peers".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @Utils.torrentStats
    fun providesTorrentStatsStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("torrentStats".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @Utils.fileStorage
    fun providesFileStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("fileStorage".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @Utils.piecesStorage
    fun providesPiecesStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("piecesStorage".toByteArray()).get()
    }

}