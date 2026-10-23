package com.example.fitnotes.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val CATALOG_URL="https://avivajpeyi.github.io/fitnotes/catalog/"
data class CatalogEntry(val id:String,val version:String,val path:String,val sha256:String,val title:String,val description:String,
    val goal:String,val level:String,val equipment:List<String>,val days:Int,val minutes:Int,val starter:Boolean)
data class CatalogManifest(val version:String,val entries:List<CatalogEntry>,val exercises:Map<String,String>,val raw:String)
data class CatalogSnapshot(val manifest:CatalogManifest,val refreshedAt:Long?,val available:Set<String>,val status:String)
data class CatalogFilter(val query:String="",val goal:String="",val level:String="",val days:Int?=null,val maxMinutes:Int?=null,val equipment:Set<String> = emptySet()) {
    fun matches(e:CatalogEntry)= (query.isBlank() || (e.title+" "+e.description).contains(query,true)) &&
        (goal.isEmpty() || e.goal==goal) && (level.isEmpty() || e.level==level) && (days==null || e.days==days) &&
        (maxMinutes==null || e.minutes<=maxMinutes) && (equipment.isEmpty() || e.equipment.all{it in equipment})
}
object CatalogValidator {
    fun manifest(raw:String):CatalogManifest {
        require(raw.toByteArray().size<=256000){"Catalog manifest exceeds 256 KB."};StrictJson(raw).check();val root=JSONObject(raw)
        require(root.get("schemaVersion")==1){"Unsupported catalog schema. Update the app or use bundled starters."}
        fun text(o:JSONObject,k:String,max:Int=200):String {val v=o.get(k);require(v is String && v.isNotBlank() && v.length<=max && v.none(Char::isISOControl)){"Invalid catalog $k."};return v}
        fun id(o:JSONObject,k:String):String=text(o,k,128).also{require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it)){"Invalid catalog identity."}}
        fun count(o:JSONObject,k:String,range:IntRange):Int {val v=o.get(k);require(v is Int && v in range){"Invalid catalog $k."};return v}
        val license=root.getJSONObject("license");require(text(license,"id")=="CC-BY-4.0");text(license,"attribution",2000)
        val refs=root.getJSONArray("exercises");require(refs.length() in 1..200)
        val exercises=(0 until refs.length()).map{refs.getJSONObject(it).let{o->id(o,"id") to text(o,"name")}}
        require(exercises.map{it.first}.distinct().size==exercises.size && exercises.map{it.second}.distinct().size==exercises.size){"Duplicate catalog exercise reference."}
        val rows=root.getJSONArray("programs");require(rows.length() in 1..64)
        val entries=(0 until rows.length()).map{i->val o=rows.getJSONObject(i);val key=id(o,"id");val version=id(o,"programVersion");val path=text(o,"path")
            require(path=="programs/$key-v$version.json"){"Catalog paths must be relative versioned program files."}
            val hash=text(o,"sha256");require(Regex("[a-f0-9]{64}").matches(hash));require(text(o,"license")=="CC-BY-4.0")
            val gear=o.getJSONArray("equipment");require(gear.length() in 1..20);val equipment=(0 until gear.length()).map{j->val v=gear.get(j);require(v is String && v.length in 1..60 && v.none(Char::isISOControl));v}
            require(equipment.distinct().size==equipment.size);val starter=o.get("starter");require(starter is Boolean)
            CatalogEntry(key,version,path,hash,text(o,"title"),text(o,"description",2000),text(o,"goal",60),text(o,"level",60),equipment,count(o,"daysPerWeek",1..14),count(o,"minutesPerDay",1..720),starter)
        }
        require(entries.map{it.id}.distinct().size==entries.size){"Duplicate catalog program ID."}
        return CatalogManifest(id(root,"catalogVersion"),entries,exercises.toMap(),raw)
    }
    fun program(raw:String,e:CatalogEntry,m:CatalogManifest):PortableProgram {
        require(planFingerprint(raw)==e.sha256){"Catalog file changed or download is incomplete. Refresh and retry."}
        val p=PortableProgramParser.parse(raw);val root=p.document
        require(p.id==e.id && p.version==e.version && p.name==e.title && root.getInt("daysPerWeek")==e.days && root.getString("description")==e.description && root.getString("level")==e.level && root.getJSONArray("goals").let{it.length()==1 && it.getString(0)==e.goal}){"Program identity, filters or schedule differ from the catalog."}
        require(root.getJSONArray("equipment").let{a->(0 until a.length()).map{a.getString(it)}.toSet()}==e.equipment.toSet())
        require(root.getJSONObject("license").getString("id")=="CC-BY-4.0")
        val days=root.getJSONArray("days")
        for(i in 0 until days.length()) {val day=days.getJSONObject(i);require(day.getInt("durationMinutes")==e.minutes);val moves=day.getJSONArray("exercises")
            for(j in 0 until moves.length()){val move=moves.getJSONObject(j);require(m.exercises[move.getString("canonicalId")]==move.getString("name")){"Unknown or mismatched catalog exercise reference."}
                val sets=move.getJSONArray("sets");for(k in 0 until sets.length()){val set=sets.getJSONObject(k);require(set.isNull("weight") && set.isNull("unit")){"The public catalog cannot prescribe personal weights."}}
            }
        }
        return p
    }
}
fun interface CatalogTransport { fun get(relative:String,maxBytes:Int):String }
class PublicCatalogTransport(private val connect:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}):CatalogTransport {
    override fun get(relative:String,maxBytes:Int):String {
        require(relative=="manifest.json" || Regex("programs/[A-Za-z0-9._-]+-v[A-Za-z0-9._-]+\\.json").matches(relative))
        val connection=connect(URL(CATALOG_URL+relative))
        connection.instanceFollowRedirects=false;connection.connectTimeout=8000;connection.readTimeout=8000
        connection.requestMethod="GET";connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Cache-Control","no-cache")
        try {
            require(connection.responseCode==200){"Catalog unavailable (HTTP ${connection.responseCode}). Cached starters and installed programs still work."}
            require(connection.contentType?.substringBefore(';')?.trim()=="application/json"){"Catalog response is not JSON."}
            require(connection.contentLengthLong<=maxBytes){"Catalog response exceeds its size limit."}
            return connection.inputStream.use{input->val output=java.io.ByteArrayOutputStream();val block=ByteArray(8192);while(true){val n=input.read(block);if(n<0)break;require(output.size()+n<=maxBytes){"Catalog response exceeds its size limit."};output.write(block,0,n)};output.toString("UTF-8")}
        } finally {connection.disconnect()}
    }
}
/** This cache contains public definitions only. It has no database/repository
 * reference, token, user ID or upload operation. Installed copies live elsewhere. */
class ProgramCatalogRepository(context:Context,private val transport:CatalogTransport=PublicCatalogTransport(),private val clock:()->Long=System::currentTimeMillis) {
    private val assets=context.applicationContext.assets
    private val directory=File(context.applicationContext.filesDir,"program-catalog")
    private val lock=Mutex()
    private fun asset(path:String)=assets.open("catalog/$path").bufferedReader().use{it.readText()}
    private fun atomic(file:File,raw:String){file.parentFile!!.mkdirs();val a=AtomicFile(file);val stream=a.startWrite();try{stream.write(raw.toByteArray(Charsets.UTF_8));a.finishWrite(stream)}catch(e:Exception){a.failWrite(stream);throw e}}
    private fun read(file:File,max:Int):String {val a=AtomicFile(file);return a.openRead().use{require(it.channel.size()<=max);it.bufferedReader().readText()}}
    private fun local(e:CatalogEntry):String? = runCatching{read(File(directory,e.sha256+".json"),PortableProgramParser.MAX_INPUT)}.getOrNull()
        ?.takeIf{planFingerprint(it)==e.sha256} ?: runCatching{asset(e.path)}.getOrNull()?.takeIf{planFingerprint(it)==e.sha256}
    private fun available(m:CatalogManifest)=m.entries.filter{e->local(e)?.let{runCatching{CatalogValidator.program(it,e,m)}.isSuccess}==true}.map{it.id}.toSet()
    private fun trim(m:CatalogManifest) {
        val files=directory.listFiles().orEmpty().filter{Regex("[a-f0-9]{64}\\.json").matches(it.name)}.sortedBy{it.lastModified()}
        val current=m.entries.map{it.sha256+".json"}.toSet();var remaining=files.size
        files.filter{it.name !in current}.forEach{if(remaining>64 && it.delete())remaining--}
    }
    private fun snapshot():CatalogSnapshot {
        val cached=runCatching{val root=JSONObject(read(File(directory,"manifest-cache.json"),600000));require(root.getInt("cacheVersion")==1);val m=CatalogValidator.manifest(root.getString("manifest"));val at=root.getLong("refreshedAt");require(at>0);CatalogSnapshot(m,at,available(m),"Cached catalog; refresh when online.")}.getOrNull()
        if(cached!=null)return cached
        val bundled=CatalogValidator.manifest(asset("manifest.json"));return CatalogSnapshot(bundled,null,available(bundled),"Bundled catalog · three starters available offline.")
    }
    suspend fun load():CatalogSnapshot=withContext(Dispatchers.IO){lock.withLock{snapshot()}}
    suspend fun refresh():CatalogSnapshot=withContext(Dispatchers.IO){lock.withLock{
        val m=CatalogValidator.manifest(transport.get("manifest.json",256000));val at=clock()
        atomic(File(directory,"manifest-cache.json"),JSONObject().put("cacheVersion",1).put("manifest",m.raw).put("refreshedAt",at).toString())
        trim(m)
        CatalogSnapshot(m,at,available(m),"Catalog refreshed. Installed local copies are unchanged.")
    }}
    suspend fun program(e:CatalogEntry,m:CatalogManifest):PortableProgram=withContext(Dispatchers.IO){lock.withLock{
        require(m.entries.any{it==e}){"Catalog selection changed. Reopen its detail."}
        local(e)?.let{runCatching{CatalogValidator.program(it,e,m)}.getOrNull()} ?: run {
            val raw=transport.get(e.path,PortableProgramParser.MAX_INPUT);val p=CatalogValidator.program(raw,e,m)
            atomic(File(directory,e.sha256+".json"),raw);trim(m);p
        }
    }}
}
