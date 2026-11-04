package io.github.daranna.sqlitebrowser.ui

import com.android.tools.idea.lang.androidSql.AndroidSqlFileType
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient

/**
 * Completion in the query tab's language, with the Android plugin's own SQL support loaded.
 *
 * JUnit 3 style, as the platform's fixtures need, so names are camel case: ktlint allows backticks only on `@Test`.
 */
class SqlEditorCompletionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Without it every completion list is just empty, which looks like a broken schema hook.
        assertTrue(
            "The Android plugin didn't load in the test IDE; see \"Tests find no completions\" in CONTRIBUTING.md",
            PluginManagerCore.isLoaded(PluginId.getId("org.jetbrains.android")),
        )
    }

    private fun complete(sql: String, queryTab: Boolean = true): List<String> {
        myFixture.configureByText(AndroidSqlFileType.INSTANCE, sql)
        val file = myFixture.file.virtualFile
        if (queryTab) file.putUserData(SqlEditor.QUERY_TAB_FILE, true)
        SqlEditor.setSchema(
            file,
            mapOf(
                "article" to listOf(column("article_id"), column("title")),
                "author" to listOf(column("name")),
            ),
        )
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun column(name: String) = DebugDbClient.ColumnInfo(name, "TEXT", false, 0)

    fun testTableNamesAfterFrom() {
        val items = complete("SELECT * FROM a<caret>")
        assertContainsElements(items, "article", "author")
    }

    fun testColumnNamesOfTheSelectedTable() {
        val items = complete("SELECT <caret> FROM article")
        assertContainsElements(items, "article_id", "title")
        assertDoesntContain(items, "name")
    }

    fun testKeywordsInAnyCase() {
        assertContainsElements(complete("SELECT * FROM article wh<caret>"), "WHERE")
    }

    fun testOnlyColumnsAfterATableAndADot() {
        val items = complete("SELECT article.<caret> FROM article")
        assertContainsElements(items, "title")
        assertDoesntContain(items, "SELECT", "WHERE")
    }

    fun testNoKeywordsOutsideQueryTabs() {
        assertDoesntContain(complete("SELECT * FROM article wh<caret>", queryTab = false), "WHERE")
    }
}
