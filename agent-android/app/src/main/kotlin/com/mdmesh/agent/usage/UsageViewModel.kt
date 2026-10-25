package com.mdmesh.agent.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mdmesh.agent.web.NetworkAccessClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class UsageConfiguration(val id:Int,val name:String,val ms:Long=0,val devices:Int=0)
data class UsageApp(val pkg:String,val name:String,val ms:Long,val configurations:List<UsageConfiguration>)
data class UsageState(val requiresLogin:Boolean=false,val loading:Boolean=false,val error:String="",val days:Int=1,val apps:List<UsageApp> = emptyList(),val configurations:List<UsageConfiguration> = emptyList(),val selected:UsageApp?=null,val configuration:Int?=null,val policy:JSONObject?=null,val saving:Boolean=false,val message:String="")
internal interface UsageTransport {
    fun identity():String = ""
    fun isAdmin():Boolean
    suspend fun admin(path:String,body:JSONObject?=null,method:String?=null):JSONObject
}
internal class UsageViewModel @JvmOverloads constructor(app:Application, private val client:UsageTransport=object:UsageTransport {
    private val delegate=NetworkAccessClient(app)
    override fun identity():String { val s=com.mdmesh.agent.web.AdminSessionStore.get(app).snapshot();return "${s.server}:${s.generation}" }
    override fun isAdmin()=delegate.isAdmin()
    override suspend fun admin(path:String,body:JSONObject?,method:String?)=delegate.admin(path,body,method)
}):AndroidViewModel(app) {
    val state=MutableStateFlow(UsageState())
    private var metadata:Pair<List<UsageConfiguration>,Map<String,String>>?=null
    private var identity=""
    private var request:Job?=null
    private var policyRequest:Job?=null
    init { refresh() }
    fun refresh(days:Int=state.value.days) {
        request?.cancel(); policyRequest?.cancel()
        val nextIdentity=client.identity()
        if(identity!=nextIdentity){metadata=null;identity=nextIdentity;state.value=UsageState(days=days)}
        state.value=state.value.copy(loading=true,error="",requiresLogin=false,days=days,apps=if(days==state.value.days)state.value.apps else emptyList(),selected=null,policy=null)
        request=viewModelScope.launch {
            try {
                check(client.isAdmin()) { "Đăng nhập quản trị để xem thống kê tất cả cấu hình." }
                val result=withContext(Dispatchers.IO) {
                    val library=metadata ?: run {
                        val configurations=client.admin("/private/configurations/list").getJSONArray("data").objects().map { UsageConfiguration(it.getInt("id"),it.getString("name")) }
                        val names=client.admin("/private/applications/search").getJSONArray("data").objects().associate { it.optString("pkg") to it.optString("name") }
                        (configurations to names).also { metadata=it }
                    }
                    val configs=library.first
                    val names=library.second
                    val today=LocalDate.now()
                    val rows=client.admin("/private/app-usage/summary?from=${today.minusDays(days-1L)}&to=$today").getJSONArray("data").objects()
                    val apps=rows.groupBy { it.getString("packageName") }.map { (pkg,items) ->
                        val ranked=configs.map { c -> val row=items.firstOrNull { it.optInt("configurationId")==c.id };c.copy(ms=row?.optLong("foregroundMs")?:0,devices=row?.optInt("deviceCount")?:0) }.sortedWith(compareByDescending<UsageConfiguration>{it.ms}.thenBy{it.name})
                        UsageApp(pkg,names[pkg]?.takeIf{it.isNotBlank()} ?: "$pkg · Ngoài thư viện",items.sumOf{it.optLong("foregroundMs")},ranked)
                    }.sortedWith(compareByDescending<UsageApp>{it.ms}.thenBy{it.name})
                    configs to apps
                }
                state.value=state.value.copy(loading=false,apps=result.second,configurations=result.first)
            } catch(e:Exception) { if(e is CancellationException)throw e;state.value=state.value.copy(loading=false,requiresLogin=!client.isAdmin(),apps=emptyList(),configurations=emptyList(),error=e.message?:"Không tải được thống kê") }
        }
    }
    fun open(app:UsageApp) { state.value=state.value.copy(selected=app,message="");app.configurations.firstOrNull()?.let{select(it.id)} }
    fun close() { if(state.value.saving)return;policyRequest?.cancel();state.value=state.value.copy(selected=null,policy=null,message="") }
    fun select(id:Int) {
        policyRequest?.cancel();state.value=state.value.copy(configuration=id,policy=null,message="")
        policyRequest=viewModelScope.launch {
            try {val policy=client.admin("/private/app-usage/configuration/$id").getJSONObject("data");state.value=state.value.copy(policy=policy)}
            catch(e:Exception){if(e is CancellationException)throw e;state.value=state.value.copy(message=e.message?:"Không tải được chính sách")}
        }
    }
    fun save(minutes:Int?,blocked:Boolean) {
        val current=state.value;val app=current.selected?:return;val id=current.configuration?:return;val original=current.policy?:return
        if(current.saving)return
        state.value=current.copy(saving=true,message="")
        viewModelScope.launch {
            try {
                // Always refetch before editing, preserving unrelated rules and schedules.
                val policy=client.admin("/private/app-usage/configuration/$id").getJSONObject("data")
                check(policy.toString()==original.toString()) { "Chính sách vừa thay đổi. Chọn lại cấu hình để tải bản mới trước khi lưu." }
                val rules=policy.optJSONArray("rules")?.objects().orEmpty().toMutableList()
                val rule=rules.firstOrNull{it.optString("packageName")==app.pkg} ?: JSONObject().put("packageName",app.pkg).put("warningMinutes",5).put("action","suspend").put("enabled",true).put("allowedWindows",JSONArray()).also{rules.add(it)}
                rule.put("dailyLimitMinutes",minutes?:JSONObject.NULL)
                if(minutes!=null) rule.put("enabled",true)
                policy.put("rules",JSONArray(rules))
                val prefix=blockPrefix(app.pkg)
                val schedules=policy.optJSONArray("schedules")?.objects().orEmpty().filterNot{it.optString("id").startsWith(prefix)}.toMutableList()
                if(blocked) {
                    // Two adjacent daily windows cover all 24 hours using the existing Agent contract.
                    listOf("00:00" to "12:00","12:00" to "00:00").forEachIndexed { index,times ->
                        schedules.add(JSONObject().put("id",prefix+index).put("name","Cấm ${app.name}".take(120)).put("enabled",true).put("scope","SELECTED").put("packages",JSONArray().put(app.pkg)).put("exceptions",JSONArray()).put("days",JSONArray(listOf(1,2,3,4,5,6,7))).put("from",times.first).put("to",times.second).put("warningMinutes",0))
                    }
                }
                policy.put("schedules",JSONArray(schedules))
                val saved=client.admin("/private/app-usage/configuration/$id",policy,"PUT").getJSONObject("data")
                state.value=state.value.copy(saving=false,policy=saved,message="Đã lưu cho cấu hình. Thiết bị sẽ áp dụng khi đồng bộ; các chính sách khác vẫn có hiệu lực.")
            }catch(e:Exception){if(e is CancellationException)throw e;state.value=state.value.copy(saving=false,message=e.message?:"Không lưu được")}
        }
    }
}
internal fun JSONArray.objects()=(0 until length()).map{getJSONObject(it)}
internal fun usageTime(ms:Long):String {val m=ms/60000;return if(m>=60)"${m/60} giờ ${m%60} phút" else if(ms>0&&m==0L)"< 1 phút" else "$m phút"}

internal fun blockPrefix(pkg:String)="app-block:${java.util.UUID.nameUUIDFromBytes(pkg.toByteArray())}:"
