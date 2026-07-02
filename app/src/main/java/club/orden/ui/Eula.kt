package club.orden.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import club.orden.HomeViewModel
import club.orden.R

/** Gates the app behind a one-time terms acceptance, then hands off to the main screen. */
@Composable
fun AppRoot(vm: HomeViewModel = viewModel()) {
    val accepted by vm.eulaAccepted.collectAsState()
    when (accepted) {
        null -> Box(Modifier.fillMaxSize()) // still loading from disk — blank, no EULA flash
        false -> EulaScreen(onAccept = vm::acceptEula)
        else -> HomeScreen(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EulaScreen(onAccept: () -> Unit) {
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("ORDEN") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Image(
                painter = painterResource(R.drawable.orden_shield),
                contentDescription = null,
                modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(20.dp))
            Text("Условия использования", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                "Настоящее программное обеспечение предназначено исключительно для частного " +
                    "домашнего использования (B2C).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Категорически запрещается использование Сервиса государственными органами " +
                    "Российской Федерации, министерствами, ведомствами, а также лицами и " +
                    "организациями, находящимися в санкционных списках OFAC (SDN List).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Нажимая «Принимаю», вы подтверждаете, что не относитесь к перечисленным " +
                    "категориям, и принимаете эти условия.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                Text("Принимаю и продолжить")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
