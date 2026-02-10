package com.github.phodal.acpmanager.skills

import com.github.phodal.acpmanager.ui.command.SkillCommand
import com.github.phodal.acpmanager.ui.slash.SlashCommandRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import java.nio.file.*
import java.nio.file.WatchEvent.Kind

private val log = logger<SkillDiscovery>()

/**
 * Discovers and registers Claude Skills as slash commands.
 *
 * Features:
 * - Auto-discovers personal and project skills on startup
 * - Registers each skill as a /skill-name command
 * - Respects invocation type (USER, CLAUDE, BOTH)
 * - Supports hot reload when SKILL.md files change
 * - Handles skill name conflicts (project > personal)
 */
class SkillDiscovery(
    private val projectBasePath: String,
    private val registry: SlashCommandRegistry,
    private val scope: CoroutineScope
) : Disposable {

    private val watcherJob: Job?
    private val registeredSkills = mutableMapOf<String, SkillDefinition>()

    init {
        // Initial discovery on startup
        discoverAndRegisterSkills()
        
        // Start file watcher for hot reload
        watcherJob = scope.launch {
            watchSkillDirectories()
        }
    }

    /**
     * Discover and register all skills from personal and project sources.
     * Handles priority: project > personal (higher priority overrides lower)
     */
    private fun discoverAndRegisterSkills() {
        try {
            log.info("Starting skill discovery...")
            
            // Load personal skills first (lower priority)
            val personalSkills = SkillLoader.loadPersonalSkills()
            log.info("Found ${personalSkills.size} personal skills")
            
            // Load project skills (higher priority, will override personal)
            val projectSkills = SkillLoader.loadProjectSkills(projectBasePath)
            log.info("Found ${projectSkills.size} project skills")
            
            // Merge skills: project > personal
            val allSkills = mutableMapOf<String, SkillDefinition>()
            
            // Add personal skills first
            personalSkills.forEach { skill ->
                allSkills[skill.name] = skill
            }
            
            // Override with project skills
            projectSkills.forEach { skill ->
                allSkills[skill.name] = skill
            }
            
            // Register all skills
            allSkills.forEach { (name, skill) ->
                registerSkill(skill)
            }
            
            registeredSkills.clear()
            registeredSkills.putAll(allSkills)
            
            log.info("Skill discovery complete: ${allSkills.size} skills registered")
        } catch (e: Exception) {
            log.warn("Failed to discover skills: ${e.message}", e)
        }
    }

    /**
     * Register a single skill as a slash command.
     * Only registers skills that are user-invocable (USER or BOTH).
     * CLAUDE-only skills are not registered in the command registry.
     */
    private fun registerSkill(skill: SkillDefinition) {
        try {
            // Only register skills that are visible in user completion
            if (!SkillCommand.isVisibleInCompletion(skill)) {
                log.debug("Skipping CLAUDE-only skill: ${skill.name}")
                return
            }

            val command = SkillCommand.fromSkill(skill)
            registry.register(command)
            log.debug("Registered skill command: /${skill.name}")
        } catch (e: Exception) {
            log.warn("Failed to register skill ${skill.name}: ${e.message}", e)
        }
    }

    /**
     * Unregister a skill command.
     */
    private fun unregisterSkill(skillName: String) {
        try {
            registry.unregister(skillName)
            log.debug("Unregistered skill command: /$skillName")
        } catch (e: Exception) {
            log.warn("Failed to unregister skill $skillName: ${e.message}", e)
        }
    }

    /**
     * Watch skill directories for changes and reload on modification.
     */
    private suspend fun watchSkillDirectories() {
        try {
            val watchService = FileSystems.getDefault().newWatchService()
            
            // Watch personal skills directory
            val personalPath = Paths.get(System.getProperty("user.home"), ".claude", "skills")
            if (Files.exists(personalPath)) {
                personalPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
                log.debug("Watching personal skills directory: $personalPath")
            }
            
            // Watch project skills directory
            val projectPath = Paths.get(projectBasePath, ".claude", "skills")
            if (Files.exists(projectPath)) {
                projectPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
                log.debug("Watching project skills directory: $projectPath")
            }
            
            // Poll for changes
            while (scope.isActive) {
                val key = watchService.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                
                for (event in key.pollEvents()) {
                    @Suppress("UNCHECKED_CAST")
                    val path = event.context() as? Path ?: continue
                    
                    if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                        log.debug("Detected skill file change: $path")
                        // Reload skills after a short delay to ensure file is written
                        delay(500)
                        discoverAndRegisterSkills()
                    }
                }
                
                key.reset()
            }
            
            watchService.close()
        } catch (e: Exception) {
            if (e !is CancellationException) {
                log.warn("Error watching skill directories: ${e.message}", e)
            }
        }
    }

    /**
     * Get all registered skills.
     */
    fun getRegisteredSkills(): Map<String, SkillDefinition> {
        return registeredSkills.toMap()
    }

    /**
     * Get a skill by name.
     */
    fun getSkill(name: String): SkillDefinition? {
        return registeredSkills[name]
    }

    override fun dispose() {
        watcherJob?.cancel()
    }
}

