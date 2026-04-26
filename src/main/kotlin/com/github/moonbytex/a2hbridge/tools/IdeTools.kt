package com.github.moonbytex.a2hbridge.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

class IdeTools(private val project: com.intellij.openapi.project.Project) {

    fun readFile(path: String): Result<String> {
        return ReadAction.compute<Result<String>, Throwable> {
            val file = findFileByPath(path)
                ?: return@compute Result.failure<String>(Exception("File not found: $path"))
            Result.success(String(file.contentsToByteArray(), Charsets.UTF_8))
        }
    }

    fun searchCode(query: String): String {
        return ReadAction.compute<String, Throwable> {
            val files = FilenameIndex.getFilesByName(project, "*${query}*", GlobalSearchScope.projectScope(project))
            if (files.isEmpty()) return@compute "No files found matching: $query"
            files.mapNotNull { file ->
                val vf = file.virtualFile ?: return@mapNotNull null
                "File: ${vf.path}\n\n${String(vf.contentsToByteArray(), Charsets.UTF_8)}"
            }.joinToString("\n---\n")
        }
    }

    fun listProjectFiles(dirPath: String): Result<String> {
        return ReadAction.compute<Result<String>, Throwable> {
            val dir = LocalFileSystem.getInstance().findFileByPath(dirPath)
                ?: return@compute Result.failure<String>(Exception("Directory not found: $dirPath"))
            val listing = dir.children.joinToString("\n") { child ->
                "${if (child.isDirectory) "[DIR]" else "[FILE]"} ${child.name}"
            }
            Result.success(listing)
        }
    }

    fun writeCode(path: String, content: String): Result<Unit> {
        var result: Result<Unit> = Result.failure<Unit>(Exception("Unknown error"))
        ApplicationManager.getApplication().runWriteAction {
            try {
                val file = LocalFileSystem.getInstance().findFileByPath(path)
                if (file != null) {
                    file.setBinaryContent(content.toByteArray(Charsets.UTF_8))
                    result = Result.success(Unit)
                } else {
                    val parentPath = path.substringBeforeLast("/", "")
                    val parent = LocalFileSystem.getInstance().findFileByPath(parentPath)
                    if (parent == null) {
                        result = Result.failure<Unit>(Exception("Parent directory not found: $parentPath"))
                    } else {
                        val newFile = parent.createChildData(this, path.substringAfterLast("/"))
                        newFile.setBinaryContent(content.toByteArray(Charsets.UTF_8))
                        LocalFileSystem.getInstance().refreshIoFiles(listOf(java.io.File(path)), true, false, null)
                        result = Result.success(Unit)
                    }
                }
            } catch (e: Exception) {
                result = Result.failure<Unit>(e)
            }
        }
        return result
    }

    private fun findFileByPath(path: String): VirtualFile? {
        return if (isAbsolutePath(path)) {
            LocalFileSystem.getInstance().findFileByPath(path)
        } else {
            val basePath = project.basePath ?: return null
            LocalFileSystem.getInstance().findFileByPath("$basePath/$path")
        }
    }

    private fun isAbsolutePath(path: String): Boolean {
        return path.startsWith("/") || path.matches(Regex("^[A-Za-z]:[/\\\\].*"))
    }
}
