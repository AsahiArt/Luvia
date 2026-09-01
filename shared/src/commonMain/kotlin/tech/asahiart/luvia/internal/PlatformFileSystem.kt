package tech.asahiart.luvia.internal

import okio.FileSystem

/**
 * okio declares `FileSystem.SYSTEM` only in its platform source sets, so common
 * code cannot reference it without failing the metadata compilation.
 */
internal expect val platformFileSystem: FileSystem
