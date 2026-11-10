package com.billbook.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.billbook.app.MainActivity
import com.billbook.app.ui.profiles.ProfilesScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.billbook.app.data.TxType
import com.billbook.app.ui.bank.AppPickerScreen
import com.billbook.app.ui.bank.BankScreen
import com.billbook.app.ui.edit.EditScreen
import com.billbook.app.ui.home.HomeScreen
import com.billbook.app.ui.importer.ImportScreen
import com.billbook.app.ui.insights.InsightsScreen
import com.billbook.app.ui.rules.RulesScreen
import com.billbook.app.ui.statement.StatementScreen

@Composable
fun AppNav(openRequest: String? = null, onOpenHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    LaunchedEffect(openRequest) {
        if (openRequest == MainActivity.OPEN_BANK) {
            nav.navigate("bank") { launchSingleTop = true }
            onOpenHandled()
        }
    }
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpen = { id -> nav.navigate("edit?id=$id") },
                onAdd = { type -> nav.navigate("edit?type=${type.name}") },
                onBillImage = { path -> nav.navigate("edit?image=${Uri.encode(path)}") },
                onStatement = { nav.navigate("statement") },
                onInsights = { nav.navigate("insights") },
                onBank = { nav.navigate("bank") },
                onProfiles = { nav.navigate("profiles") },
                onRules = { nav.navigate("rules") },
                onImportStatement = { uri -> nav.navigate("import?uri=${Uri.encode(uri.toString())}") },
            )
        }
        composable(
            route = "import?uri={uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
            ImportScreen(uri = uri, onDone = { nav.popBackStack() })
        }
        composable("profiles") {
            ProfilesScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "edit?id={id}&type={type}&image={image}&detected={detected}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType; defaultValue = 0L },
                navArgument("type") { type = NavType.StringType; defaultValue = TxType.EXPENSE.name },
                navArgument("image") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("detected") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { entry ->
            val args = entry.arguments
            EditScreen(
                id = args?.getLong("id") ?: 0L,
                type = TxType.valueOf(args?.getString("type") ?: TxType.EXPENSE.name),
                imagePath = args?.getString("image"),
                detectedId = args?.getLong("detected") ?: 0L,
                onDone = { nav.popBackStack() },
            )
        }
        composable("rules") {
            RulesScreen(onBack = { nav.popBackStack() })
        }
        composable("insights") {
            InsightsScreen(onBack = { nav.popBackStack() })
        }
        composable("statement") {
            StatementScreen(onBack = { nav.popBackStack() })
        }
        composable("bank") {
            BankScreen(
                onBack = { nav.popBackStack() },
                onChooseApps = { nav.navigate("bank/apps") },
                onEditDetected = { id -> nav.navigate("edit?detected=$id") },
            )
        }
        composable("bank/apps") {
            AppPickerScreen(onDone = { nav.popBackStack() })
        }
    }
}
