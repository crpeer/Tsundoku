package app.tsundoku.reader.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tsundoku.reader.di.AppContainer
import app.tsundoku.reader.ui.book.BookDetailScreen
import app.tsundoku.reader.ui.book.RemoteBook
import app.tsundoku.reader.ui.browse.BrowseScreen
import app.tsundoku.reader.ui.browse.SourceBrowseScreen
import app.tsundoku.reader.ui.library.LibraryScreen
import app.tsundoku.reader.ui.more.AddSourceScreen
import app.tsundoku.reader.ui.more.ImportLegadoScreen
import app.tsundoku.reader.ui.more.MoreScreen
import app.tsundoku.reader.ui.more.SourceDiagnoseScreen
import app.tsundoku.reader.ui.more.SourceManageScreen
import app.tsundoku.reader.ui.reader.ReaderScreen
import app.tsundoku.reader.ui.search.GlobalSearchScreen
import app.tsundoku.reader.ui.updates.UpdatesScreen

object Routes {
    const val LIBRARY = "library"
    const val UPDATES = "updates"
    const val BROWSE = "browse"
    const val MORE = "more"
    const val BOOK = "book/{bookId}"
    const val READER = "reader/{bookId}?chapter={chapter}"
    const val SOURCE = "source/{sourceId}"
    const val GLOBAL_SEARCH = "global_search?q={q}"
    const val SOURCE_MANAGE = "source_manage"
    const val SOURCE_DIAGNOSE = "source_diagnose/{sourceId}?name={name}"
    const val ADD_SOURCE = "add_source"
    const val IMPORT_LEGADO = "import_legado"
    const val REMOTE = "remote?sourceId={sourceId}&url={url}&title={title}&cover={cover}&author={author}"

    fun book(id: Long) = "book/$id"
    fun reader(id: Long, chapter: Int = -1) = "reader/$id?chapter=$chapter"
    fun source(id: Long) = "source/$id"

    fun globalSearch(keyword: String) = "global_search?q=${Uri.encode(keyword)}"

    fun diagnose(sourceId: Long, name: String) =
        "source_diagnose/$sourceId?name=${Uri.encode(name)}"

    fun remote(sourceId: Long, url: String, title: String, cover: String?, author: String?) =
        "remote?sourceId=$sourceId" +
            "&url=${Uri.encode(url)}" +
            "&title=${Uri.encode(title)}" +
            "&cover=${Uri.encode(cover.orEmpty())}" +
            "&author=${Uri.encode(author.orEmpty())}"
}

private data class TopTab(val route: String, val label: String, val icon: ImageVector)

private val topTabs = listOf(
    TopTab(Routes.LIBRARY, "书架", Icons.Outlined.LibraryBooks),
    TopTab(Routes.UPDATES, "更新", Icons.Outlined.Update),
    TopTab(Routes.BROWSE, "发现", Icons.Outlined.Explore),
    TopTab(Routes.MORE, "更多", Icons.Outlined.MoreHoriz),
)

@Composable
fun AppRoot(container: AppContainer, initialSearchQuery: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topTabs.any { it.route == currentRoute }

    LaunchedEffect(initialSearchQuery) {
        val keyword = initialSearchQuery?.trim().orEmpty()
        if (keyword.isNotEmpty()) {
            navController.navigate(Routes.globalSearch(keyword))
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenReader = { navController.navigate(Routes.reader(it)) },
                    onOpenBook = { navController.navigate(Routes.book(it)) },
                )
            }

            composable(Routes.UPDATES) {
                UpdatesScreen(
                    onOpenChapter = { bookId, chapter ->
                        navController.navigate(Routes.reader(bookId, chapter))
                    },
                )
            }

            composable(Routes.BROWSE) {
                BrowseScreen(
                    onOpenSource = { navController.navigate(Routes.source(it)) },
                    onGlobalSearch = { navController.navigate(Routes.globalSearch("")) },
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onManageSources = { navController.navigate(Routes.SOURCE_MANAGE) },
                )
            }

            composable(Routes.MORE) {
                MoreScreen(
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onManageSources = { navController.navigate(Routes.SOURCE_MANAGE) },
                    onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                    onImportLegado = { navController.navigate(Routes.IMPORT_LEGADO) },
                )
            }

            composable(Routes.IMPORT_LEGADO) {
                ImportLegadoScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.BOOK,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: -1L
                BookDetailScreen(
                    bookId = bookId,
                    remote = null,
                    onBack = { navController.popBackStack() },
                    onRead = { id, chapter -> navController.navigate(Routes.reader(id, chapter)) },
                )
            }

            composable(
                route = Routes.REMOTE,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.LongType },
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("cover") { type = NavType.StringType; defaultValue = "" },
                    navArgument("author") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments
                BookDetailScreen(
                    bookId = -1L,
                    remote = RemoteBook(
                        sourceId = args?.getLong("sourceId") ?: 0L,
                        url = args?.getString("url").orEmpty(),
                        title = args?.getString("title").orEmpty(),
                        cover = args?.getString("cover")?.ifBlank { null },
                        author = args?.getString("author")?.ifBlank { null },
                    ),
                    onBack = { navController.popBackStack() },
                    onRead = { id, chapter -> navController.navigate(Routes.reader(id, chapter)) },
                )
            }

            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("chapter") {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                ),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: -1L
                val chapter = entry.arguments?.getInt("chapter") ?: -1
                ReaderScreen(
                    bookId = bookId,
                    initialChapter = chapter,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.SOURCE,
                arguments = listOf(navArgument("sourceId") { type = NavType.LongType }),
            ) { entry ->
                val sourceId = entry.arguments?.getLong("sourceId") ?: -1L
                SourceBrowseScreen(
                    sourceId = sourceId,
                    onBack = { navController.popBackStack() },
                    onOpenRemote = { source, item ->
                        navController.navigate(
                            Routes.remote(
                                sourceId = source.id,
                                url = item.url,
                                title = item.title,
                                cover = item.cover,
                                author = item.author,
                            ),
                        )
                    },
                )
            }

            composable(
                route = Routes.GLOBAL_SEARCH,
                arguments = listOf(
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                GlobalSearchScreen(
                    initialQuery = entry.arguments?.getString("q"),
                    onBack = { navController.popBackStack() },
                    onOpenRemote = { source, item ->
                        navController.navigate(
                            Routes.remote(
                                sourceId = source.id,
                                url = item.url,
                                title = item.title,
                                cover = item.cover,
                                author = item.author,
                            ),
                        )
                    },
                )
            }

            composable(Routes.SOURCE_MANAGE) {
                SourceManageScreen(
                    onBack = { navController.popBackStack() },
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onDiagnose = { source ->
                        navController.navigate(Routes.diagnose(source.id, source.name))
                    },
                )
            }

            composable(
                route = Routes.SOURCE_DIAGNOSE,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.LongType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                SourceDiagnoseScreen(
                    sourceId = entry.arguments?.getLong("sourceId") ?: -1L,
                    sourceName = entry.arguments?.getString("name").orEmpty().ifBlank { "书源" },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ADD_SOURCE) {
                AddSourceScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
