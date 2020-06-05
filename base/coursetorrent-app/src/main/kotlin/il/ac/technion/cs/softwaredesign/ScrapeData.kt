package il.ac.technion.cs.softwaredesign

import java.io.Serializable

sealed class ScrapeData : Serializable

data class Scrape(
    val complete: Int,
    val downloaded: Int,
    val incomplete: Int,
    val name: String?
) : ScrapeData()

data class Failure(
    val reason: String
) : ScrapeData()