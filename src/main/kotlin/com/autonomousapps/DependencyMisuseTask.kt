@file:Suppress("UnstableApiUsage")

package com.autonomousapps

import com.autonomousapps.internal.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Produces a report of unused direct dependencies and used transitive dependencies.
 */
open class DependencyMisuseTask @Inject constructor(
    objects: ObjectFactory,
    private val workerExecutor: WorkerExecutor
) : DefaultTask() {

    init {
        group = "verification"
        description = "Produces a report of unused direct dependencies and used transitive dependencies"
    }

    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    val declaredDependencies: RegularFileProperty = objects.fileProperty()

    @PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    val usedClasses: RegularFileProperty = objects.fileProperty()

    @get:OutputFile
    val outputUnusedDependencies: RegularFileProperty = objects.fileProperty()

    @get:OutputFile
    val outputUsedTransitives: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun action() {
        // Input
        val declaredDependenciesFile = declaredDependencies.get().asFile
        val usedClassesFile = usedClasses.get().asFile

        // Output
        val outputUnusedDependenciesFile = outputUnusedDependencies.get().asFile
        val outputUsedTransitivesFile = outputUsedTransitives.get().asFile

        // Cleanup prior execution
        outputUnusedDependenciesFile.delete()
        outputUsedTransitivesFile.delete()

        val declaredLibraries = declaredDependenciesFile.readText().fromJsonList<Component>()
        val usedClasses = usedClassesFile.readLines()

        val unusedLibs = mutableListOf<String>()
        val usedTransitives = mutableListOf<TransitiveDependency>()
        val usedDirectClasses = mutableListOf<String>()
        declaredLibraries
            // Exclude dependencies with zero class files (such as androidx.legacy:legacy-support-v4)
            .filterNot { it.classes.isEmpty() }
            .forEach { lib ->
                var count = 0
                val classes = sortedSetOf<String>()

                lib.classes.forEach { declClass ->
                    // Looking for unused direct dependencies
                    if (!lib.isTransitive) {
                        if (!usedClasses.contains(declClass)) {
                            // Unused class
                            count++
                        } else {
                            // Used class
                            usedDirectClasses.add(declClass)
                        }
                    }

                    // Looking for used transitive dependencies
                    if (lib.isTransitive
                        // Black-listing this one.
                        && lib.identifier != "org.jetbrains.kotlin:kotlin-stdlib"
                        // Assume all these come from android.jar
                        && !declClass.startsWith("android.")
                        && usedClasses.contains(declClass)
                        // Not in the list of used direct dependencies
                        && !usedDirectClasses.contains(declClass)
                    ) {
                        classes.add(declClass)
                    }
                }
                if (count == lib.classes.size
                    // Blacklisting all of these
                    && !lib.identifier.startsWith("org.jetbrains.kotlin:kotlin-stdlib")
                ) {
                    unusedLibs.add(lib.identifier)
                }
                if (classes.isNotEmpty()) {
                    usedTransitives.add(TransitiveDependency(lib.identifier, classes))
                }
            }

        outputUnusedDependenciesFile.writeText(unusedLibs.joinToString("\n"))
        logger.quiet("Unused dependencies report: ${outputUnusedDependenciesFile.path}")
        logger.quiet("Unused dependencies:\n${unusedLibs.joinToString(separator = "\n- ", prefix = "- ")}\n")

        // TODO known issues:
        // 1. Should org.jetbrains.kotlin:kotlin-stdlib be excluded?
        // 2. generated code might used transitives (such as dagger.android using vanilla dagger and org.jetbrains:annotations).
        // 3. Unused directs mis-reports classes referenced in layout XML files (e.g., androidx.constraintlayout:constraintlayout && androidx.constraintlayout.widget.ConstraintLayout)
        outputUsedTransitivesFile.writeText(usedTransitives.toJson())
        logger.quiet("Used transitive dependencies report: ${outputUsedTransitivesFile.path}")
        logger.quiet("Used transitive dependencies:\n${usedTransitives.toPrettyString()}")
    }
}