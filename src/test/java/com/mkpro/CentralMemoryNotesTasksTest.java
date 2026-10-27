package com.mkpro;

import com.mkpro.models.notes.NoteItem;
import com.mkpro.models.notes.TaskItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying:
 * 1. TaskItem & NoteItem creation and getters/setters.
 * 2. CentralMemory CRUD operations for tasks (getAllTasks, saveTask, getTask, toggleTask, deleteTask).
 * 3. CentralMemory CRUD operations for notes (getAllNotes, saveNote, getNote, deleteNote).
 * 4. Project-scoped and project-grouped notes & tasks.
 */
public class CentralMemoryNotesTasksTest {

    private Path tempDir;
    private CentralMemory memory;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cm-tasks-notes-test-");
        Path sharedDb = tempDir.resolve("shared").resolve("central_memory.db");
        Path localDb = tempDir.resolve("local").resolve("stats.db");
        memory = new CentralMemory(sharedDb, localDb);
    }

    @AfterEach
    void tearDown() {
        if (memory != null) {
            memory.close();
            memory = null;
        }
        // Best-effort cleanup on Windows where MapDB WAL files may remain open until GC
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }

    // ==========================================================================
    // 1. TaskItem Creation and Getters/Setters
    // ==========================================================================

    @Test
    @DisplayName("TaskItem: default constructor, parameterized constructor, and getters/setters")
    void testTaskItemCreationAndGettersSetters() {
        long before = System.currentTimeMillis();
        TaskItem defaultTask = new TaskItem();
        long after = System.currentTimeMillis();

        assertNull(defaultTask.getId());
        assertNull(defaultTask.getTitle());
        assertEquals("TODO", defaultTask.getStatus());
        assertEquals("medium", defaultTask.getPriority());
        assertNull(defaultTask.getDueDate());
        assertTrue(defaultTask.isProjectScoped());
        assertFalse(defaultTask.isCurrentProject());
        assertEquals("Global", defaultTask.getProjectName());
        assertTrue(defaultTask.getCreatedAt() >= before && defaultTask.getCreatedAt() <= after);
        assertTrue(defaultTask.getUpdatedAt() >= before && defaultTask.getUpdatedAt() <= after);

        // Parameterized constructor
        TaskItem paramTask = new TaskItem("task-1", "Build Feature", "IN_PROGRESS", "high");
        assertEquals("task-1", paramTask.getId());
        assertEquals("Build Feature", paramTask.getTitle());
        assertEquals("IN_PROGRESS", paramTask.getStatus());
        assertEquals("high", paramTask.getPriority());
        assertNull(paramTask.getDueDate());
        assertTrue(paramTask.isProjectScoped());

        // Scoped constructors
        TaskItem globalConstructed = new TaskItem("task-g", "Global Task", "TODO", "low", false);
        assertFalse(globalConstructed.isProjectScoped());
        assertFalse(globalConstructed.isCurrentProject());
        assertEquals("global", globalConstructed.getProjectPath());
        assertEquals("Global", globalConstructed.getProjectName());

        Path customProjPath = tempDir.resolve("my-awesome-project");
        TaskItem pathConstructed = new TaskItem("task-p", "Project Task", "TODO", "high", customProjPath.toString());
        assertTrue(pathConstructed.isProjectScoped());
        assertFalse(pathConstructed.isCurrentProject());
        assertEquals(customProjPath.toString(), pathConstructed.getProjectPath());
        assertEquals("my-awesome-project", pathConstructed.getProjectName());

        TaskItem currentProjConstructed = new TaskItem("task-curr", "Current Project Task", "TODO", "high", CentralMemory.getCurrentProjectPath());
        assertTrue(currentProjConstructed.isProjectScoped());
        assertTrue(currentProjConstructed.isCurrentProject());

        // Null status/priority fallback to defaults
        TaskItem nullDefaultsTask = new TaskItem("task-2", "Another Feature", null, null);
        assertEquals("TODO", nullDefaultsTask.getStatus());
        assertEquals("medium", nullDefaultsTask.getPriority());

        // Getters and Setters
        paramTask.setId("task-updated");
        paramTask.setTitle("Updated Title");
        paramTask.setStatus("DONE");
        paramTask.setPriority("low");
        paramTask.setDueDate("2026-12-31");
        paramTask.setCreatedAt(1000L);
        paramTask.setUpdatedAt(2000L);

        assertEquals("task-updated", paramTask.getId());
        assertEquals("Updated Title", paramTask.getTitle());
        assertEquals("DONE", paramTask.getStatus());
        assertEquals("low", paramTask.getPriority());
        assertEquals("2026-12-31", paramTask.getDueDate());
        assertEquals(1000L, paramTask.getCreatedAt());
        assertEquals(2000L, paramTask.getUpdatedAt());

        // Equals & HashCode based on ID
        TaskItem taskA = new TaskItem("id-same", "Title A", "TODO", "low");
        TaskItem taskB = new TaskItem("id-same", "Title B", "DONE", "high");
        TaskItem taskC = new TaskItem("id-different", "Title A", "TODO", "low");

        assertEquals(taskA, taskB);
        assertEquals(taskA.hashCode(), taskB.hashCode());
        assertNotEquals(taskA, taskC);
        assertEquals(taskA, taskA);
        assertNotEquals(taskA, null);
        assertNotEquals(taskA, "some string");

        // toString
        String str = paramTask.toString();
        assertTrue(str.contains("task-updated"));
        assertTrue(str.contains("Updated Title"));
        assertTrue(str.contains("DONE"));
        assertTrue(str.contains("low"));
    }

    // ==========================================================================
    // 2. NoteItem Creation and Getters/Setters
    // ==========================================================================

    @Test
    @DisplayName("NoteItem: default constructor, parameterized constructor, and getters/setters")
    void testNoteItemCreationAndGettersSetters() {
        long before = System.currentTimeMillis();
        NoteItem defaultNote = new NoteItem();
        long after = System.currentTimeMillis();

        assertNull(defaultNote.getId());
        assertNull(defaultNote.getTitle());
        assertNull(defaultNote.getContent());
        assertNotNull(defaultNote.getTags());
        assertTrue(defaultNote.getTags().isEmpty());
        assertFalse(defaultNote.isPinned());
        assertTrue(defaultNote.isProjectScoped());
        assertFalse(defaultNote.isCurrentProject());
        assertEquals("Global", defaultNote.getProjectName());
        assertTrue(defaultNote.getCreatedAt() >= before && defaultNote.getCreatedAt() <= after);
        assertTrue(defaultNote.getUpdatedAt() >= before && defaultNote.getUpdatedAt() <= after);

        // Parameterized constructor
        NoteItem paramNote = new NoteItem("note-1", "Meeting Notes", "Discussed roadmap");
        assertEquals("note-1", paramNote.getId());
        assertEquals("Meeting Notes", paramNote.getTitle());
        assertEquals("Discussed roadmap", paramNote.getContent());
        assertNotNull(paramNote.getTags());
        assertFalse(paramNote.isPinned());
        assertTrue(paramNote.isProjectScoped());

        // Scoped constructors
        NoteItem globalConstructed = new NoteItem("note-g", "Global Note", "Content", false);
        assertFalse(globalConstructed.isProjectScoped());
        assertFalse(globalConstructed.isCurrentProject());
        assertEquals("global", globalConstructed.getProjectPath());
        assertEquals("Global", globalConstructed.getProjectName());

        Path customProjPath = tempDir.resolve("my-awesome-project");
        NoteItem pathConstructed = new NoteItem("note-p", "Project Note", "Content", customProjPath.toString());
        assertTrue(pathConstructed.isProjectScoped());
        assertFalse(pathConstructed.isCurrentProject());
        assertEquals(customProjPath.toString(), pathConstructed.getProjectPath());
        assertEquals("my-awesome-project", pathConstructed.getProjectName());

        NoteItem currentProjConstructed = new NoteItem("note-curr", "Current Project Note", "Content", CentralMemory.getCurrentProjectPath());
        assertTrue(currentProjConstructed.isProjectScoped());
        assertTrue(currentProjConstructed.isCurrentProject());

        // Getters and Setters
        paramNote.setId("note-updated");
        paramNote.setTitle("Updated Meeting Notes");
        paramNote.setContent("Updated roadmap discussion");
        List<String> tags = List.of("meeting", "roadmap", "q4");
        paramNote.setTags(tags);
        paramNote.setPinned(true);
        paramNote.setCreatedAt(5000L);
        paramNote.setUpdatedAt(6000L);

        assertEquals("note-updated", paramNote.getId());
        assertEquals("Updated Meeting Notes", paramNote.getTitle());
        assertEquals("Updated roadmap discussion", paramNote.getContent());
        assertEquals(tags, paramNote.getTags());
        assertTrue(paramNote.isPinned());
        assertEquals(5000L, paramNote.getCreatedAt());
        assertEquals(6000L, paramNote.getUpdatedAt());

        // Null tags setter safe guard
        paramNote.setTags(null);
        assertNotNull(paramNote.getTags());
        assertTrue(paramNote.getTags().isEmpty());

        // Equals & HashCode based on ID
        NoteItem noteA = new NoteItem("id-same", "Title A", "Content A");
        NoteItem noteB = new NoteItem("id-same", "Title B", "Content B");
        NoteItem noteC = new NoteItem("id-different", "Title A", "Content A");

        assertEquals(noteA, noteB);
        assertEquals(noteA.hashCode(), noteB.hashCode());
        assertNotEquals(noteA, noteC);
        assertEquals(noteA, taskOrNoteSelf(noteA));
        assertNotEquals(noteA, null);
        assertNotEquals(noteA, "some string");

        // toString
        String str = paramNote.toString();
        assertTrue(str.contains("note-updated"));
        assertTrue(str.contains("Updated Meeting Notes"));
        assertTrue(str.contains("pinned=true"));
    }

    private NoteItem taskOrNoteSelf(NoteItem n) {
        return n;
    }

    // ==========================================================================
    // 3. CentralMemory CRUD Operations for Tasks
    // ==========================================================================

    @Test
    @DisplayName("CentralMemory: Task CRUD (getAllTasks, saveTask, getTask, toggleTask, deleteTask)")
    void testTaskCrudOperations() {
        // Initial state: empty list
        List<TaskItem> initialTasks = memory.getAllTasks();
        assertNotNull(initialTasks);
        assertTrue(initialTasks.isEmpty());

        // 1. saveTask with specific ID
        TaskItem task1 = new TaskItem("t-1", "Design API", "TODO", "high");
        task1.setDueDate("2026-11-01");
        TaskItem savedTask1 = memory.saveTask(task1);
        assertNotNull(savedTask1);
        assertEquals("t-1", savedTask1.getId());

        // 2. getTask
        TaskItem fetched1 = memory.getTask("t-1");
        assertNotNull(fetched1);
        assertEquals("t-1", fetched1.getId());
        assertEquals("Design API", fetched1.getTitle());
        assertEquals("TODO", fetched1.getStatus());
        assertEquals("high", fetched1.getPriority());
        assertEquals("2026-11-01", fetched1.getDueDate());

        // 3. getAllTasks contains saved task
        List<TaskItem> allAfterOne = memory.getAllTasks();
        assertEquals(1, allAfterOne.size());
        assertEquals("t-1", allAfterOne.get(0).getId());

        // 4. saveTask with null ID (auto-generates UUID)
        TaskItem taskAutoId = new TaskItem(null, "Auto ID Task", "IN_PROGRESS", "medium");
        TaskItem savedAuto = memory.saveTask(taskAutoId);
        assertNotNull(savedAuto);
        assertNotNull(savedAuto.getId());
        assertFalse(savedAuto.getId().isBlank());
        assertEquals("Auto ID Task", savedAuto.getTitle());

        TaskItem fetchedAuto = memory.getTask(savedAuto.getId());
        assertNotNull(fetchedAuto);
        assertEquals("Auto ID Task", fetchedAuto.getTitle());

        assertEquals(2, memory.getAllTasks().size());

        // 5. Update existing task
        fetched1.setTitle("Design REST & WebSocket API");
        fetched1.setPriority("low");
        TaskItem updated1 = memory.saveTask(fetched1);
        assertNotNull(updated1);
        TaskItem refetched1 = memory.getTask("t-1");
        assertEquals("Design REST & WebSocket API", refetched1.getTitle());
        assertEquals("low", refetched1.getPriority());

        // 6. toggleTask: TODO -> DONE
        TaskItem toggledToDone = memory.toggleTask("t-1");
        assertNotNull(toggledToDone);
        assertEquals("DONE", toggledToDone.getStatus());

        TaskItem verifiedDone = memory.getTask("t-1");
        assertEquals("DONE", verifiedDone.getStatus());

        // toggleTask: DONE -> TODO
        TaskItem toggledToTodo = memory.toggleTask("t-1");
        assertNotNull(toggledToTodo);
        assertEquals("TODO", toggledToTodo.getStatus());

        TaskItem verifiedTodo = memory.getTask("t-1");
        assertEquals("TODO", verifiedTodo.getStatus());

        // 7. deleteTask
        boolean deleted = memory.deleteTask("t-1");
        assertTrue(deleted);
        assertNull(memory.getTask("t-1"));

        // Second delete of same ID returns false
        assertFalse(memory.deleteTask("t-1"));

        // List size decreases to 1
        List<TaskItem> remainingTasks = memory.getAllTasks();
        assertEquals(1, remainingTasks.size());
        assertEquals(savedAuto.getId(), remainingTasks.get(0).getId());

        // Delete the remaining task
        assertTrue(memory.deleteTask(savedAuto.getId()));
        assertTrue(memory.getAllTasks().isEmpty());

        // 8. Edge cases
        assertNull(memory.saveTask(null));
        assertNull(memory.getTask(null));
        assertNull(memory.getTask(""));
        assertNull(memory.getTask("   "));
        assertNull(memory.getTask("non-existent"));

        assertFalse(memory.deleteTask(null));
        assertFalse(memory.deleteTask(""));
        assertFalse(memory.deleteTask("non-existent"));

        assertNull(memory.toggleTask(null));
        assertNull(memory.toggleTask(""));
        assertNull(memory.toggleTask("non-existent"));
    }

    // ==========================================================================
    // 4. CentralMemory CRUD Operations for Notes
    // ==========================================================================

    @Test
    @DisplayName("CentralMemory: Note CRUD (getAllNotes, saveNote, getNote, deleteNote)")
    void testNoteCrudOperations() {
        // Initial state: empty list
        List<NoteItem> initialNotes = memory.getAllNotes();
        assertNotNull(initialNotes);
        assertTrue(initialNotes.isEmpty());

        // 1. saveNote with specific ID
        NoteItem note1 = new NoteItem("n-1", "Architecture Blueprint", "Hybrid agent network design");
        note1.setTags(List.of("architecture", "design", "v4"));
        note1.setPinned(true);
        NoteItem savedNote1 = memory.saveNote(note1);
        assertNotNull(savedNote1);
        assertEquals("n-1", savedNote1.getId());

        // 2. getNote
        NoteItem fetched1 = memory.getNote("n-1");
        assertNotNull(fetched1);
        assertEquals("n-1", fetched1.getId());
        assertEquals("Architecture Blueprint", fetched1.getTitle());
        assertEquals("Hybrid agent network design", fetched1.getContent());
        assertEquals(List.of("architecture", "design", "v4"), fetched1.getTags());
        assertTrue(fetched1.isPinned());

        // 3. getAllNotes contains saved note
        List<NoteItem> allAfterOne = memory.getAllNotes();
        assertEquals(1, allAfterOne.size());
        assertEquals("n-1", allAfterOne.get(0).getId());

        // 4. saveNote with null ID (auto-generates UUID)
        NoteItem noteAutoId = new NoteItem(null, "Auto ID Note", "Generated UUID test");
        NoteItem savedAuto = memory.saveNote(noteAutoId);
        assertNotNull(savedAuto);
        assertNotNull(savedAuto.getId());
        assertFalse(savedAuto.getId().isBlank());
        assertEquals("Auto ID Note", savedAuto.getTitle());

        NoteItem fetchedAuto = memory.getNote(savedAuto.getId());
        assertNotNull(fetchedAuto);
        assertEquals("Auto ID Note", fetchedAuto.getTitle());

        assertEquals(2, memory.getAllNotes().size());

        // 5. Update existing note
        fetched1.setTitle("Architecture Blueprint v2");
        fetched1.setContent("Updated design specs");
        fetched1.setPinned(false);
        fetched1.setTags(List.of("architecture", "final"));
        NoteItem updated1 = memory.saveNote(fetched1);
        assertNotNull(updated1);

        NoteItem refetched1 = memory.getNote("n-1");
        assertEquals("Architecture Blueprint v2", refetched1.getTitle());
        assertEquals("Updated design specs", refetched1.getContent());
        assertFalse(refetched1.isPinned());
        assertEquals(List.of("architecture", "final"), refetched1.getTags());

        // 6. deleteNote
        boolean deleted = memory.deleteNote("n-1");
        assertTrue(deleted);
        assertNull(memory.getNote("n-1"));

        // Second delete returns false
        assertFalse(memory.deleteNote("n-1"));

        // List size decreases to 1
        List<NoteItem> remainingNotes = memory.getAllNotes();
        assertEquals(1, remainingNotes.size());
        assertEquals(savedAuto.getId(), remainingNotes.get(0).getId());

        // Delete the remaining note
        assertTrue(memory.deleteNote(savedAuto.getId()));
        assertTrue(memory.getAllNotes().isEmpty());

        // 7. Edge cases
        assertNull(memory.saveNote(null));
        assertNull(memory.getNote(null));
        assertNull(memory.getNote(""));
        assertNull(memory.getNote("   "));
        assertNull(memory.getNote("non-existent"));

        assertFalse(memory.deleteNote(null));
        assertFalse(memory.deleteNote(""));
        assertFalse(memory.deleteNote("non-existent"));
    }

    // ==========================================================================
    // 5. Project-Scoped and Project-Grouped Notes
    // ==========================================================================

    @Test
    @DisplayName("CentralMemory: Project-scoped & Global notes (saveNote, saveGlobalNote, fallback, grouping, clearing)")
    void testProjectScopedAndGroupedNotes() {
        String currentProjectPath = CentralMemory.getCurrentProjectPath();
        String otherProjectPath = tempDir.resolve("other-project").toAbsolutePath().normalize().toString();

        // 1. Save a current project note
        NoteItem projNote = new NoteItem("note-proj-1", "Current Project Note", "Scoped to current project");
        memory.saveNote(projNote);

        // 2. Save a global note
        NoteItem globalNote = new NoteItem("note-global-1", "Global Note", "Shared across all projects");
        memory.saveGlobalNote(globalNote);

        // 3. Save a note for another project
        NoteItem otherProjNote = new NoteItem("note-other-1", "Other Project Note", "Scoped to other-project", otherProjectPath);
        memory.saveNote(otherProjNote);

        // Verify getProjectNotes() returns only current project's notes
        List<NoteItem> currentProjectNotes = memory.getProjectNotes();
        assertEquals(1, currentProjectNotes.size());
        assertEquals("note-proj-1", currentProjectNotes.get(0).getId());
        assertEquals(currentProjectPath, currentProjectNotes.get(0).getProjectPath());
        assertTrue(currentProjectNotes.get(0).isProjectScoped());
        assertTrue(currentProjectNotes.get(0).isCurrentProject());

        // Verify getProjectNotes(otherProjectPath) returns other project's notes
        List<NoteItem> otherProjectNotes = memory.getProjectNotes(otherProjectPath);
        assertEquals(1, otherProjectNotes.size());
        assertEquals("note-other-1", otherProjectNotes.get(0).getId());
        assertEquals(otherProjectPath, otherProjectNotes.get(0).getProjectPath());
        assertEquals("other-project", otherProjectNotes.get(0).getProjectName());
        assertFalse(otherProjectNotes.get(0).isCurrentProject());

        // Verify getGlobalNotes() returns only global notes
        List<NoteItem> globalNotes = memory.getGlobalNotes();
        assertEquals(1, globalNotes.size());
        assertEquals("note-global-1", globalNotes.get(0).getId());
        assertEquals("global", globalNotes.get(0).getProjectPath());
        assertEquals("Global", globalNotes.get(0).getProjectName());
        assertFalse(globalNotes.get(0).isProjectScoped());
        assertFalse(globalNotes.get(0).isCurrentProject());

        // Verify getNote() precedence: project lookup -> global fallback -> other project lookup
        NoteItem sharedIdGlobal = new NoteItem("shared-id", "Global Fallback Title", "Global Content");
        memory.saveGlobalNote(sharedIdGlobal);
        assertEquals("Global Fallback Title", memory.getNote("shared-id").getTitle());

        NoteItem sharedIdProject = new NoteItem("shared-id", "Project Override Title", "Project Content");
        memory.saveNote(sharedIdProject);
        // Project note overrides global note for current project
        assertEquals("Project Override Title", memory.getNote("shared-id").getTitle());

        // Other project note is also retrievable by ID
        NoteItem fetchedOther = memory.getNote("note-other-1");
        assertNotNull(fetchedOther);
        assertEquals("Other Project Note", fetchedOther.getTitle());

        // Verify getNotesGroupedByProject()
        Map<String, List<NoteItem>> grouped = memory.getNotesGroupedByProject();
        assertTrue(grouped.containsKey(currentProjectPath));
        assertTrue(grouped.containsKey(otherProjectPath));
        assertTrue(grouped.containsKey("global"));
        assertEquals(2, grouped.get(currentProjectPath).size()); // note-proj-1 and shared-id
        assertEquals(1, grouped.get(otherProjectPath).size());   // note-other-1
        assertEquals(2, grouped.get("global").size());           // note-global-1 and shared-id

        // Verify getAllNotes() orders current project notes first and deduplicates shared-id with current project taking precedence
        List<NoteItem> allNotes = memory.getAllNotes();
        assertEquals(4, allNotes.size()); // 2 current project + 1 other project + 1 non-overridden global
        assertEquals(currentProjectPath, allNotes.get(0).getProjectPath());
        assertEquals(currentProjectPath, allNotes.get(1).getProjectPath());

        // Verify clearProjectNotes() removes only current project notes
        memory.clearProjectNotes();
        assertTrue(memory.getProjectNotes().isEmpty());
        assertEquals(1, memory.getProjectNotes(otherProjectPath).size());
        assertEquals(2, memory.getGlobalNotes().size());
        // Now getNote("shared-id") falls back to the global note!
        NoteItem fallbackAfterClear = memory.getNote("shared-id");
        assertNotNull(fallbackAfterClear);
        assertEquals("Global Fallback Title", fallbackAfterClear.getTitle());

        // Verify clearGlobalNotes() removes only global notes
        memory.clearGlobalNotes();
        assertTrue(memory.getGlobalNotes().isEmpty());
        assertNull(memory.getNote("shared-id"));
        assertEquals(1, memory.getProjectNotes(otherProjectPath).size());
    }

    // ==========================================================================
    // 6. Project-Scoped and Project-Grouped Tasks
    // ==========================================================================

    @Test
    @DisplayName("CentralMemory: Project-scoped & Global tasks (saveTask, saveGlobalTask, fallback, toggleTask, grouping, clearing)")
    void testProjectScopedAndGroupedTasks() {
        String currentProjectPath = CentralMemory.getCurrentProjectPath();
        String otherProjectPath = tempDir.resolve("other-task-project").toAbsolutePath().normalize().toString();

        // 1. Save a current project task
        TaskItem projTask = new TaskItem("task-proj-1", "Current Project Task", "TODO", "high");
        memory.saveTask(projTask);

        // 2. Save a global task
        TaskItem globalTask = new TaskItem("task-global-1", "Global Task", "TODO", "medium");
        memory.saveGlobalTask(globalTask);

        // 3. Save a task for another project
        TaskItem otherProjTask = new TaskItem("task-other-1", "Other Project Task", "TODO", "low", otherProjectPath);
        memory.saveTask(otherProjTask);

        // Verify getProjectTasks() returns only current project's tasks
        List<TaskItem> currentProjectTasks = memory.getProjectTasks();
        assertEquals(1, currentProjectTasks.size());
        assertEquals("task-proj-1", currentProjectTasks.get(0).getId());
        assertEquals(currentProjectPath, currentProjectTasks.get(0).getProjectPath());
        assertTrue(currentProjectTasks.get(0).isProjectScoped());
        assertTrue(currentProjectTasks.get(0).isCurrentProject());

        // Verify getProjectTasks(otherProjectPath) returns other project's tasks
        List<TaskItem> otherProjectTasks = memory.getProjectTasks(otherProjectPath);
        assertEquals(1, otherProjectTasks.size());
        assertEquals("task-other-1", otherProjectTasks.get(0).getId());
        assertEquals(otherProjectPath, otherProjectTasks.get(0).getProjectPath());
        assertEquals("other-task-project", otherProjectTasks.get(0).getProjectName());
        assertFalse(otherProjectTasks.get(0).isCurrentProject());

        // Verify getGlobalTasks() returns only global tasks
        List<TaskItem> globalTasks = memory.getGlobalTasks();
        assertEquals(1, globalTasks.size());
        assertEquals("task-global-1", globalTasks.get(0).getId());
        assertEquals("global", globalTasks.get(0).getProjectPath());
        assertEquals("Global", globalTasks.get(0).getProjectName());
        assertFalse(globalTasks.get(0).isProjectScoped());
        assertFalse(globalTasks.get(0).isCurrentProject());

        // Verify toggleTask works across current project, global, and other projects while preserving projectPath
        TaskItem toggledOther = memory.toggleTask("task-other-1");
        assertNotNull(toggledOther);
        assertEquals("DONE", toggledOther.getStatus());
        assertEquals(otherProjectPath, toggledOther.getProjectPath());

        TaskItem toggledGlobal = memory.toggleTask("task-global-1");
        assertNotNull(toggledGlobal);
        assertEquals("DONE", toggledGlobal.getStatus());
        assertEquals("global", toggledGlobal.getProjectPath());

        // Verify getTask() precedence: project lookup -> global fallback -> other project lookup
        TaskItem sharedIdGlobal = new TaskItem("shared-task-id", "Global Fallback Task", "TODO", "low");
        memory.saveGlobalTask(sharedIdGlobal);
        assertEquals("Global Fallback Task", memory.getTask("shared-task-id").getTitle());

        TaskItem sharedIdProject = new TaskItem("shared-task-id", "Project Override Task", "TODO", "high");
        memory.saveTask(sharedIdProject);
        assertEquals("Project Override Task", memory.getTask("shared-task-id").getTitle());

        // Verify getTasksGroupedByProject()
        Map<String, List<TaskItem>> grouped = memory.getTasksGroupedByProject();
        assertTrue(grouped.containsKey(currentProjectPath));
        assertTrue(grouped.containsKey(otherProjectPath));
        assertTrue(grouped.containsKey("global"));
        assertEquals(2, grouped.get(currentProjectPath).size()); // task-proj-1 and shared-task-id
        assertEquals(1, grouped.get(otherProjectPath).size());   // task-other-1
        assertEquals(2, grouped.get("global").size());           // task-global-1 and shared-task-id

        // Verify getAllTasks() orders current project tasks first and deduplicates shared-task-id with current project taking precedence
        List<TaskItem> allTasks = memory.getAllTasks();
        assertEquals(4, allTasks.size()); // 2 current project + 1 other project + 1 non-overridden global
        assertEquals(currentProjectPath, allTasks.get(0).getProjectPath());
        assertEquals(currentProjectPath, allTasks.get(1).getProjectPath());

        // Verify clearProjectTasks() removes only current project tasks
        memory.clearProjectTasks();
        assertTrue(memory.getProjectTasks().isEmpty());
        assertEquals(1, memory.getProjectTasks(otherProjectPath).size());
        assertEquals(2, memory.getGlobalTasks().size());
        // Now getTask("shared-task-id") falls back to the global task
        TaskItem fallbackAfterClear = memory.getTask("shared-task-id");
        assertNotNull(fallbackAfterClear);
        assertEquals("Global Fallback Task", fallbackAfterClear.getTitle());

        // Verify clearGlobalTasks() removes only global tasks
        memory.clearGlobalTasks();
        assertTrue(memory.getGlobalTasks().isEmpty());
        assertNull(memory.getTask("shared-task-id"));
        assertEquals(1, memory.getProjectTasks(otherProjectPath).size());
    }

    // ==========================================================================
    // 7. Listener & Remote Sync Operations for Tasks and Notes
    // ==========================================================================

    @Test
    @DisplayName("CentralMemory: Task and Note operations trigger listeners and support remote sync")
    void testListenersAndRemoteSync() {
        List<String> eventKeys = new ArrayList<>();
        memory.addListener((key, value) -> eventKeys.add(key));

        // Save task triggers listener
        TaskItem task = new TaskItem("sync-t1", "Sync Task", "TODO", "high");
        memory.saveTask(task);
        assertTrue(eventKeys.contains("task:sync-t1"));

        // Toggle task triggers listener
        memory.toggleTask("sync-t1");
        assertTrue(eventKeys.contains("task:sync-t1"));

        // Delete task triggers listener
        memory.deleteTask("sync-t1");
        assertTrue(eventKeys.contains("task_deleted:sync-t1"));

        // Save note triggers listener
        NoteItem note = new NoteItem("sync-n1", "Sync Note", "Sync Note Content");
        memory.saveNote(note);
        assertTrue(eventKeys.contains("note:sync-n1"));

        // Delete note triggers listener
        memory.deleteNote("sync-n1");
        assertTrue(eventKeys.contains("note_deleted:sync-n1"));

        // Remote sync for task
        String taskJson = "{\"id\":\"remote-t1\",\"title\":\"Remote Task\",\"status\":\"DONE\",\"priority\":\"low\",\"createdAt\":100,\"updatedAt\":200}";
        memory.updateFromRemote("task:remote-t1", taskJson);
        TaskItem remoteTask = memory.getTask("remote-t1");
        assertNotNull(remoteTask);
        assertEquals("Remote Task", remoteTask.getTitle());
        assertEquals("DONE", remoteTask.getStatus());

        // Remote sync for note
        String noteJson = "{\"id\":\"remote-n1\",\"title\":\"Remote Note\",\"content\":\"Remote Content\",\"tags\":[\"remote\"],\"pinned\":true,\"createdAt\":300,\"updatedAt\":400}";
        memory.updateFromRemote("note:remote-n1", noteJson);
        NoteItem remoteNote = memory.getNote("remote-n1");
        assertNotNull(remoteNote);
        assertEquals("Remote Note", remoteNote.getTitle());
        assertEquals("Remote Content", remoteNote.getContent());
        assertTrue(remoteNote.isPinned());
    }
}
