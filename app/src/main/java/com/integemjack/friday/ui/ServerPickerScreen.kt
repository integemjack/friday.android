package com.integemjack.friday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.integemjack.friday.data.SavedServer
import com.integemjack.friday.schat.normalizeServer
import kotlinx.coroutines.launch

/**
 * 选聊天服务器：没登录时 App 一打开先是这一页，选好了才到登录页（[LoginScreen]）。
 *
 * - 加过的服务器排成一列，点一台就去登录页登它；上次用的那台标「上次用的」
 * - 「添加服务器」：地址 + 名字（可不填）+ 服务器密码（私有服务器才要）。先探一下连不连得上，
 *   连不上把原因写出来，确认地址没错可以「仍然添加」；加好了直接去登录页
 * - 一台都还没有（新装的 App）：添加的表单直接摆着，没有默认服务器
 * - 每台右边 ⋮：编辑 / 删除
 */
@Composable
fun ServerPickerScreen(onPicked: (SavedServer) -> Unit) {
    val model = LocalAppModel.current
    val schat by model.schat.state.collectAsState()
    val servers by model.schat.servers.collectAsState()
    // 表单：null = 收着；"" = 新加一台；某个地址 = 在改那一台
    var form by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = servers.firstOrNull { it.address == form }
    val formOpen = form != null || servers.isEmpty()

    // 这页不在 Surface 里：没写颜色的字/图标默认是纯黑，深色模式下就看不见了
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(28.dp))
                Brand(
                    "选择服务器",
                    if (servers.isEmpty()) "先添加一台 SChat 聊天服务器，再用它登录"
                    else "选一台 SChat 聊天服务器登录，或者再添加一台",
                )
                Spacer(Modifier.height(28.dp))

                if (servers.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        servers.forEach { server ->
                            ServerRow(
                                server = server,
                                last = server.address == schat.server,
                                onClick = { onPicked(server) },
                                onEdit = { form = server.address },
                                onRemove = {
                                    if (form == server.address) form = null
                                    model.schat.removeServer(server.address)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (formOpen) {
                    // 换一台改 / 从改换成新加：表单里的字重来
                    key(form.orEmpty()) {
                        ServerForm(
                            initial = editing,
                            cancellable = servers.isNotEmpty(),
                            onCancel = { form = null },
                            onSaved = { saved ->
                                form = null
                                // 新加的直接去登它；改的留在这页
                                if (editing == null) onPicked(saved)
                            },
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { form = "" },
                        contentPadding = PaddingValues(vertical = 12.dp),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加服务器")
                    }
                }

                Spacer(Modifier.height(20.dp))
                VersionLine()
            }
        }
    }
}

/** 列表里的一台：名字（没起就是地址）、地址 / 有没有服务器密码、上次用的；右边 ⋮ 编辑 / 删除 */
@Composable
private fun ServerRow(
    server: SavedServer,
    last: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (last) scheme.primaryContainer.copy(alpha = 0.7f) else scheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Dns, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(server.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                server.host.takeIf { server.name.isNotBlank() },
                "有服务器密码".takeIf { server.serverPassword.isNotEmpty() },
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (last) {
            Spacer(Modifier.width(8.dp))
            Chip("上次用的", color = scheme.primary)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = scheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(
                    text = { Text("删除", color = scheme.error) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

/**
 * 添加 / 编辑一台服务器。点「添加」先探一下（/server-info）：通了就存；不通把原因写出来，
 * 地址和密码没再改的话第二次点就是「仍然添加」（服务器暂时没开、这会儿没网也能先加上）
 */
@Composable
private fun ServerForm(
    initial: SavedServer?,
    cancellable: Boolean,
    onCancel: () -> Unit,
    onSaved: (SavedServer) -> Unit,
) {
    val model = LocalAppModel.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var address by rememberSaveable {
        // https 是默认的，不写也行：编辑时就只摆 host:port
        mutableStateOf(initial?.let { if (it.address.startsWith("https://")) it.host else it.address }.orEmpty())
    }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var password by remember { mutableStateOf(initial?.serverPassword.orEmpty()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 探过没通的那组（地址 + 密码）：没再改就不再探，直接存
    var failedFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val normalized = normalizeServer(address)
    val force = failedFor == (normalized to password)
    // 编辑时地址和密码都没动（只改了名字）：不用再探
    val unchanged = initial != null && initial.address == normalized && initial.serverPassword == password.trim()

    fun save() {
        model.schat.saveServer(address, name, password, replacing = initial?.address)?.let(onSaved)
    }

    fun submit() {
        if (normalized.isEmpty()) {
            error = "先填服务器地址"
            return
        }
        if (force || unchanged) {
            save()
            return
        }
        checking = true
        error = null
        scope.launch {
            val problem = model.schat.probeServer(address, password)
            checking = false
            if (problem == null) {
                save()
            } else {
                error = problem
                failedFor = normalized to password
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(if (initial == null) "添加服务器" else "编辑服务器", style = MaterialTheme.typography.titleSmall)
        Text(
            "SChat 聊天服务器的地址：IP:端口 或域名，不写 https:// 也行",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FridayField(
            address, { address = it; error = null }, "服务器地址（如 1.2.3.4:2012）",
            options = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        FridayField(name, { name = it }, "名字（可不填，比如「公司」）")
        FridayField(
            password, { password = it; error = null }, "服务器密码（私有服务器才要填）",
            options = KeyboardOptions(keyboardType = KeyboardType.Password), password = true,
        )
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            if (force) {
                Text(
                    "确认地址没错（比如服务器这会儿没开），可以先加上",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (cancellable) {
                TextButton(onClick = onCancel, enabled = !checking) { Text("取消") }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = ::submit, enabled = address.isNotBlank() && !checking) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        checking -> "正在连…"
                        force -> if (initial == null) "仍然添加" else "仍然保存"
                        initial != null -> "保存"
                        else -> "添加并登录"
                    }
                )
            }
        }
    }
}
