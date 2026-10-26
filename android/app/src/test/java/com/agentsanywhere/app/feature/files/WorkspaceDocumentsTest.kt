package com.agentsanywhere.app.feature.files

import org.junit.Assert.*
import org.junit.Test

class WorkspaceDocumentsTest {
    @Test fun fileFiltersRetainDirectoriesAndHandleUppercaseExtensions() {
        val files = listOf(FileEntry("nested", "/nested", true, null), FileEntry("Notes.MD", "/notes", false, 10),
            FileEntry("report.PDF", "/report", false, 10), FileEntry("a.PNG", "/image", false, 10), FileEntry("log.txt", "/log", false, 10))
        assertEquals(listOf("nested", "Notes.MD"), filterWorkspaceFiles(files, WorkspaceFileKind.Markdown, "").map { it.name })
        assertEquals(listOf("nested", "report.PDF"), filterWorkspaceFiles(files, WorkspaceFileKind.Pdf, "").map { it.name })
        assertEquals(listOf("nested", "a.PNG"), filterWorkspaceFiles(files, WorkspaceFileKind.Image, "").map { it.name })
        assertEquals(listOf("log.txt"), filterWorkspaceFiles(files, WorkspaceFileKind.All, " LOG ").map { it.name })
    }
    @Test fun relativeDocumentLinksResolveFromCurrentDocumentNotCurrentFolder() {
        assertEquals("/work/reports/result.pdf", resolveWorkspaceLink("/work/reports/notes.md", "result.pdf"))
        assertEquals("/work/chart.png", resolveWorkspaceLink("/work/reports/notes.md", "../chart.png"))
        assertEquals("/work/reports/my report.pdf", resolveWorkspaceLink("/work/reports/notes.md", "my%20report.pdf#page=2"))
        assertEquals("C:/Work/images/a.png", resolveWorkspaceLink("C:\\Work\\notes.md", "images/a.png"))
        assertEquals("/other/result.pdf", resolveWorkspaceLink("/work/notes.md", "/other/result.pdf"))
    }
}
