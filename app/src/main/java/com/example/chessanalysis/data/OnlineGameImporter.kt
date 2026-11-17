
package com.example.chessanalysis.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Fetches public chess games from Chess.com and Lichess.
 * Network operations always run on Dispatchers.IO.
 */
object OnlineGameImporter {

    const val CHESS_COM = "chess.com"
    const val LICHESS = "lichess"

    private const val MAX_GAMES = 50
    private const val MAX_CHESS_COM_MONTHS = 6

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 30000

    suspend fun fetchGames(
        platform: String,
        username: String
    ): List<ImportedGame> = withContext(Dispatchers.IO) {

        val cleanUsername = username.trim()

        require(cleanUsername.isNotEmpty()) {
            "Username must not be empty"
        }

        when (platform.lowercase()) {
            CHESS_COM -> fetchChessCom(cleanUsername)
            LICHESS -> fetchLichess(cleanUsername)
            else -> throw IllegalArgumentException("Unsupported platform")
        }
    }

    /**
     * Fetch recent public games from Chess.com.
     */
    private fun fetchChessCom(username: String): List<ImportedGame> {

        val encodedUsername = encodePath(username)

        val archivesUrl =
            "https://api.chess.com/pub/player/$encodedUsername/games/archives"

        val response = httpGet(archivesUrl)

        val archives = JSONObject(response)
            .optJSONArray("archives")
            ?: return emptyList()

        val recentArchives = (0 until archives.length())
            .mapNotNull { archives.optString(it).takeIf(String::isNotBlank) }
            .takeLast(MAX_CHESS_COM_MONTHS)
            .asReversed()

        val games = mutableListOf<ImportedGame>()

        for (archiveUrl in recentArchives) {

            if (games.size >= MAX_GAMES) break

            if (!archiveUrl.startsWith("https://api.chess.com/")) {
                continue
            }

            val pgnUrl = archiveUrl.trimEnd('/') + "/pgn"
            val pgnResponse = httpGet(pgnUrl)

            games.addAll(
                parseMultipleGames(
                    pgnText = pgnResponse,
                    platform = CHESS_COM,
                    username = username
                )
            )
        }

        return games
            .distinctBy { it.pgn.trim() }
            .sortedByDescending { it.date }
            .take(MAX_GAMES)
    }

    /**
     * Fetch recent public games from Lichess.
     */
    private fun fetchLichess(username: String): List<ImportedGame> {

        val encodedUsername = encodePath(username)

        val url =
            "https://lichess.org/api/games/user/$encodedUsername?max=$MAX_GAMES"

        val response = httpGet(
            url = url,
            accept = "application/x-chess-pgn"
        )

        return parseMultipleGames(
            pgnText = response,
            platform = LICHESS,
            username = username
        ).take(MAX_GAMES)
    }

    /**
     * Split a multi-game PGN response into individual games.
     */
    private fun parseMultipleGames(
        pgnText: String,
        platform: String,
        username: String
    ): List<ImportedGame> {

        val text = pgnText.trim()

        if (text.isEmpty()) return emptyList()

        val blocks = Regex(
            """(?m)(?=^\[Event\s+")"""
        ).split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val gameBlocks = if (blocks.size > 1) {
            blocks
        } else {
            listOf(text)
        }

        return gameBlocks.mapNotNull { pgn ->

            val parsed = PgnImporter.parse(pgn)
                ?: return@mapNotNull null

            val tags = parsed.tags

            ImportedGame(
                platform = platform,
                username = username,
                pgn = pgn,
                date = tags["Date"].orEmpty(),
                white = tags["White"].orEmpty(),
                black = tags["Black"].orEmpty(),
                result = tags["Result"].orEmpty()
            )
        }
    }

    /**
     * Performs an HTTP GET request.
     */
    private fun httpGet(
        url: String,
        accept: String = "application/json"
    ): String {

        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty(
                "User-Agent",
                "FarisChess/1.0 Android"
            )

            val status = connection.responseCode

            if (status !in 200..299) {
                throw IOException(
                    when (status) {
                        404 -> "User or games not found (404)"
                        429 -> "Too many requests. Please try again later (429)"
                        else -> "Server returned HTTP $status"
                    }
                )
            }

            return connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

        } finally {
            connection.disconnect()
        }
    }

    /**
     * Safely encodes a username used as a URL path segment.
     */
    private fun encodePath(value: String): String {
        return URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.name()
        ).replace("+", "%20")
    }
}
