
package com.example.chessanalysis.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.UUID

/**
 * A single game imported from Chess.com or Lichess.
 */
data class ImportedGame(
    val id: String = UUID.randomUUID().toString(),
    val platform: String,
    val username: String,
    val pgn: String,
    val date: String = "",
    val white: String = "",
    val black: String = "",
    val result: String = "",
    val importedAt: Long = System.currentTimeMillis()
) {
    fun validate(): ImportedGame {
        require(id.isNotBlank()) { "Game ID must not be empty" }
        require(platform == "chess.com" || platform == "lichess") {
            "Unsupported chess platform"
        }
        require(username.isNotBlank()) { "Username must not be empty" }
        require(pgn.isNotBlank()) { "PGN must not be empty" }
        return this
    }
}

/**
 * Stores imported online games separately from the local game history.
 */
object ImportedGamesRepository {

    private const val TAG = "ImportedGamesRepository"
    private const val FILE_NAME = "imported_games.json"

    private fun atomicFile(context: Context): AtomicFile {
        return AtomicFile(context.filesDir.resolve(FILE_NAME))
    }

    @Synchronized
    fun loadAll(context: Context): List<ImportedGame> {
        val file = atomicFile(context)

        return try {
            val text = file.openRead().bufferedReader().use { it.readText() }
            val array = JSONArray(text)

            buildList {
                for (i in 0 until array.length()) {
                    try {
                        add(gameFromJson(array.getJSONObject(i)))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping invalid imported game at index $i", e)
                    }
                }
            }
        } catch (_: FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read imported games", e)
            emptyList()
        }
    }

    /**
     * Adds games without duplicating previously imported PGNs.
     * Returns only the newly added games.
     */
    @Synchronized
    fun addAll(
        context: Context,
        games: List<ImportedGame>
    ): List<ImportedGame> {
        val existing = loadAll(context).toMutableList()
        val added = mutableListOf<ImportedGame>()

        games.forEach { game ->
            game.validate()

            val duplicate = existing.any {
                it.platform.equals(game.platform, ignoreCase = true) &&
                it.username.equals(game.username, ignoreCase = true) &&
                it.pgn.trim() == game.pgn.trim()
            }

            if (!duplicate) {
                existing.add(game)
                added.add(game)
            }
        }

        if (added.isNotEmpty()) {
            saveAll(context, existing)
        }

        return added
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val games = loadAll(context).toMutableList()
        val removed = games.removeAll { it.id == id }

        if (removed) {
            saveAll(context, games)
        }

        return removed
    }

    @Synchronized
    fun clear(context: Context) {
        atomicFile(context).delete()
    }

    private fun saveAll(
        context: Context,
        games: List<ImportedGame>
    ) {
        val array = JSONArray()

        games.forEach { game ->
            game.validate()
            array.put(gameToJson(game))
        }

        val file = atomicFile(context)
        var output = file.startWrite()

        try {
            output.write(array.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private fun gameToJson(game: ImportedGame) =
        JSONObject().apply {
            put("id", game.id)
            put("platform", game.platform)
            put("username", game.username)
            put("pgn", game.pgn)
            put("date", game.date)
            put("white", game.white)
            put("black", game.black)
            put("result", game.result)
            put("importedAt", game.importedAt)
        }

    private fun gameFromJson(json: JSONObject): ImportedGame {
        return ImportedGame(
            id = json.getString("id"),
            platform = json.getString("platform"),
            username = json.getString("username"),
            pgn = json.getString("pgn"),
            date = json.optString("date", ""),
            white = json.optString("white", ""),
            black = json.optString("black", ""),
            result = json.optString("result", ""),
            importedAt = json.optLong(
                "importedAt",
                System.currentTimeMillis()
            )
        ).validate()
    }
}
