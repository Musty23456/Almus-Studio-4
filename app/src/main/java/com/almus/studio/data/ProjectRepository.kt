package com.almus.studio.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.UUID

/**
 * Local-only project persistence. No network calls exist anywhere in this class
 * or anything it touches -- projects live entirely under the app's external
 * files directory, which requires no runtime permission on API 26+ and is
 * removed automatically if the user uninstalls the app.
 */
class ProjectRepository(private val context: Context) {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    // Non-reified Moshi.adapter(Class<T>) instead of the reified adapter<T>()
    // extension, which requires an @OptIn(ExperimentalStdlibApi::class) opt-in
    // we'd rather not spread around the codebase for one call site.
    private val adapter = moshi.adapter(Project::class.java)

    private val projectsRoot: File
        get() {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val root = File(base, "Projects")
            if (!root.exists()) root.mkdirs()
            return root
        }

    fun projectDir(projectId: String): File = File(projectsRoot, projectId).apply { mkdirs() }

    fun audioDir(projectId: String): File = File(projectDir(projectId), "audio").apply { mkdirs() }

    fun createProject(name: String, bpm: Int): Project {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val project = Project(
            id = id,
            name = name,
            bpm = bpm,
            createdAtEpochMs = now,
            modifiedAtEpochMs = now,
            tracks = listOf(
                Track(id = UUID.randomUUID().toString(), name = "Track 1", colorHex = TrackColors.forIndex(0)),
                Track(id = UUID.randomUUID().toString(), name = "Track 2", colorHex = TrackColors.forIndex(1))
            )
        )
        save(project)
        return project
    }

    fun save(project: Project) {
        val updated = project.copy(modifiedAtEpochMs = System.currentTimeMillis())
        val file = File(projectDir(project.id), "project.json")
        file.writeText(adapter.indent("  ").toJson(updated))
    }

    fun load(projectId: String): Project? {
        val file = File(projectDir(projectId), "project.json")
        if (!file.exists()) return null
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull()
    }

    fun listProjects(): List<Project> {
        val root = projectsRoot
        val dirs = root.listFiles { f -> f.isDirectory } ?: emptyArray()
        return dirs.mapNotNull { load(it.name) }.sortedByDescending { it.modifiedAtEpochMs }
    }

    fun delete(projectId: String) {
        projectDir(projectId).deleteRecursively()
    }
}
