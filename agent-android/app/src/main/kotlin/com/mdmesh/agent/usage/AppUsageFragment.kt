package com.mdmesh.agent.usage

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap as asUsageImage
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mdmesh.agent.ui.compose.MDMeshTheme
import com.mdmesh.agent.web.AdminAccountActivity

class AppUsageFragment:androidx.fragment.app.Fragment() {
    private lateinit var model:UsageViewModel
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        model=androidx.lifecycle.ViewModelProvider(this)[UsageViewModel::class.java]
    }
    override fun onCreateView(inflater:android.view.LayoutInflater,container:android.view.ViewGroup?,savedInstanceState:Bundle?):android.view.View = androidx.compose.ui.platform.ComposeView(requireContext()).apply {
        setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { MDMeshTheme { UsageScreen(model) {startActivity(Intent(requireContext(),AdminAccountActivity::class.java))} } }
    }
    override fun onResume(){super.onResume();if(!com.mdmesh.agent.web.NetworkAccessClient(requireContext()).isAdmin() || model.state.value.requiresLogin)model.refresh()}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageScreen(model:UsageViewModel,account:()->Unit) {
    val s by model.state.collectAsStateWithLifecycle()
    var query by rememberSaveableText()
    val apps=remember(s.apps,query){s.apps.filter{it.name.contains(query,true)||it.pkg.contains(query,true)}}
    // AgentNavigation consumes window insets for every tab before they reach Compose.
    Scaffold(contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),topBar={TopAppBar(title={Text("Sử dụng ứng dụng")},actions={IconButton(onClick={model.refresh()},enabled=!s.loading){Icon(androidx.compose.ui.res.painterResource(com.mdmesh.agent.R.drawable.network_refresh),contentDescription="Làm mới")};IconButton(onClick=account){Icon(androidx.compose.ui.res.painterResource(com.mdmesh.agent.R.drawable.network_account),contentDescription="Tài khoản quản trị")}})}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp)) {
            Text("Tất cả cấu hình",style=MaterialTheme.typography.titleMedium)
            Text("Tổng thời gian cộng từ các thiết bị",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(1 to "Hôm nay",7 to "7 ngày",30 to "30 ngày").forEach{(days,label)->FilterChip(selected=s.days==days,onClick={model.refresh(days)},label={Text(label)},enabled=!s.saving)}}
            OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("Tìm ứng dụng")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            if(s.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(s.error.isNotEmpty()) { Text(s.error,color=MaterialTheme.colorScheme.error);if(s.requiresLogin)TextButton(onClick=account){Text("Mở tài khoản quản trị")} else TextButton(onClick={model.refresh()}){Text("Thử lại")} }
            if(s.error.isEmpty()&&!s.loading)Text("${apps.size} ứng dụng · ${usageTime(apps.sumOf{it.ms})}",modifier=Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.labelLarge)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(!s.loading&&s.error.isEmpty()&&apps.isEmpty())item{Text("Chưa có báo cáo sử dụng trong khoảng ngày này.",Modifier.padding(24.dp))}
                items(apps,key={it.pkg}){app ->
                    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().clickable{model.open(app)}) {
                        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                            UsageIcon(app)
                            Column(Modifier.weight(1f).padding(horizontal=12.dp)) {Text(app.name,fontWeight=FontWeight.Medium,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);Text("${app.configurations.count{it.devices>0}} cấu hình có báo cáo",style=MaterialTheme.typography.bodySmall);LinearProgressIndicator(progress={if(s.apps.firstOrNull()?.ms==0L)0f else app.ms.toFloat()/(s.apps.firstOrNull()?.ms?:1).toFloat()},modifier=Modifier.fillMaxWidth().padding(top=6.dp))}
                            Text(usageTime(app.ms),style=MaterialTheme.typography.labelLarge,modifier=Modifier.widthIn(max=88.dp))
                        }
                    }
                }
            }
        }
    }
    s.selected?.let { app ->
        ModalBottomSheet(onDismissRequest={model.close()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            UsageSheet(s,app,model)
        }
    }
}
@Composable private fun rememberSaveableText()=androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf("")}

@Composable
private fun UsageSheet(s:UsageState,app:UsageApp,model:UsageViewModel) {
    var choosing by remember(app.pkg){mutableStateOf(false)}
    val selected=app.configurations.firstOrNull{it.id==s.configuration}
    val rule=s.policy?.optJSONArray("rules")?.objects()?.firstOrNull{it.optString("packageName")==app.pkg}
    val savedMinutes=rule?.takeIf{it.optBoolean("enabled",true)}?.optInt("dailyLimitMinutes",0)?.takeIf{it>0}?.toString().orEmpty()
    val savedBlock=s.policy?.optJSONArray("schedules")?.objects()?.any{it.optString("id").startsWith("app-block:${app.pkg}:")&&it.optBoolean("enabled")}==true
    var minutes by remember(s.configuration,s.policy){mutableStateOf(savedMinutes)}
    var blocked by remember(s.configuration,s.policy){mutableStateOf(savedBlock)}
    val dirty=minutes!=savedMinutes||blocked!=savedBlock
    val valid=minutes.isBlank()||minutes.toIntOrNull() in 1..1440
    LazyColumn(Modifier.fillMaxWidth().imePadding().padding(horizontal=20.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text(app.name,style=MaterialTheme.typography.headlineSmall);Text("${usageTime(app.ms)} · Tất cả cấu hình",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {OutlinedButton(onClick={choosing=!choosing},enabled=!s.saving&&!dirty,modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth()){Text(selected?.name?:"Chọn cấu hình");Text("${selected?.let{usageTime(it.ms)}?:""}  ▾",style=MaterialTheme.typography.bodySmall)}};Text(if(dirty)"Lưu hoặc hoàn tác trước khi chuyển cấu hình." else "Sắp xếp theo usage giảm dần · Tự chọn cấu hình dùng nhiều nhất",style=MaterialTheme.typography.bodySmall)}
        if(choosing) items(app.configurations,key={it.id}) { c ->
            Row(Modifier.fillMaxWidth().clickable(enabled=!s.saving&&!dirty){model.select(c.id);choosing=false}.padding(8.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=c.id==s.configuration,onClick=null);Column(Modifier.padding(start=12.dp)){Text(c.name);Text(if(c.devices==0)"Chưa có dữ liệu" else "${usageTime(c.ms)} · ${c.devices} thiết bị có báo cáo",style=MaterialTheme.typography.bodySmall)}}
        }
        if(s.policy==null)item {if(s.message.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth())}
        else {
            item {HorizontalDivider();Text("Chính sách của ${selected?.name}",style=MaterialTheme.typography.titleMedium);Text("Áp dụng cho toàn bộ thiết bị trong cấu hình. Giới hạn tính riêng trên mỗi thiết bị mỗi ngày.",style=MaterialTheme.typography.bodySmall)}
            item {OutlinedTextField(value=minutes,onValueChange={if(it.length<=4&&it.all(Char::isDigit))minutes=it},label={Text("Giới hạn phút/ngày")},supportingText={Text(if(valid)"Để trống để bỏ giới hạn thời gian" else "Nhập từ 1 đến 1440 phút")},isError=!valid,enabled=!s.saving,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Cấm sử dụng");Text("Chặn cả ngày trên mọi thiết bị",style=MaterialTheme.typography.bodySmall)};Switch(checked=blocked,onCheckedChange={blocked=it},enabled=!s.saving)}}
            item {Text("Khung giờ và các chính sách chặn khác được giữ nguyên. Gỡ lệnh cấm tại đây không gỡ chặn từ chính sách khác.",style=MaterialTheme.typography.bodySmall)}
            item {Button(onClick={model.save(minutes.toIntOrNull(),blocked)},enabled=dirty&&valid&&!s.saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(s.saving)"Đang lưu…" else "Lưu cho ${selected?.name}")};if(dirty)TextButton(onClick={minutes=savedMinutes;blocked=savedBlock},enabled=!s.saving){Text("Hoàn tác")}}
        }
        if(s.message.isNotEmpty())item {Text(s.message,color=MaterialTheme.colorScheme.primary)}
    }
}
@Composable
private fun UsageIcon(app:UsageApp) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,app.pkg) {
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            UsageIconCache.get(app.pkg) ?: runCatching { context.packageManager.getApplicationIcon(app.pkg).toUsageBitmap().also { UsageIconCache.put(app.pkg,it) } }.getOrNull()
        }
    }
    val icon=bitmap
    if(icon!=null)androidx.compose.foundation.Image(icon,contentDescription=null,modifier=Modifier.size(40.dp))
    else Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer){Text(app.name.take(1).uppercase(),Modifier.padding(12.dp),fontWeight=FontWeight.Bold)}
}
private fun android.graphics.drawable.Drawable.toUsageBitmap():androidx.compose.ui.graphics.ImageBitmap {
    val bitmap=android.graphics.Bitmap.createBitmap(80,80,android.graphics.Bitmap.Config.ARGB_8888)
    setBounds(0,0,80,80);draw(android.graphics.Canvas(bitmap))
    return bitmap.asUsageImage()
}

private val UsageIconCache=android.util.LruCache<String,androidx.compose.ui.graphics.ImageBitmap>(64)
