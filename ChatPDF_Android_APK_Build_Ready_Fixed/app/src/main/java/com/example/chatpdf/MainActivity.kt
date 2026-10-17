package com.example.chatpdf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

data class QuizQ(val question:String, val options:List<String>, val answer:Int, val explanation:String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val executor = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        tts = TextToSpeech(this, this)
        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFFFF9B45), onPrimary = Color(0xFF211307),
                secondary = Color(0xFF79C8C4), background = Color(0xFF101722),
                surface = Color(0xFF192331), surfaceVariant = Color(0xFF263446),
                onBackground = Color(0xFFF3F6FA), onSurface = Color(0xFFF3F6FA),
                onSurfaceVariant = Color(0xFFB9C4D1)
            )
            MaterialTheme(colorScheme = colors) { ChatPDFScreen() }
        }
    }
    override fun onInit(status: Int) { if(status == TextToSpeech.SUCCESS) tts?.language = Locale("fa", "IR") }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); executor.shutdownNow(); super.onDestroy() }

    @Composable
    private fun ChatPDFScreen() {
        val ctx = LocalContext.current
        var pdfUri by remember { mutableStateOf<Uri?>(null) }
        var pdfName by remember { mutableStateOf("هنوز PDF انتخاب نشده") }
        var page by remember { mutableIntStateOf(0) }
        var pageCount by remember { mutableIntStateOf(0) }
        var pageText by remember { mutableStateOf("") }
        var bookText by remember { mutableStateOf("") }
        var selectedText by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf("صفحه") }
        var question by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("یک فایل PDF باز کنید؛ سپس می‌توانید توضیح، آزمون یا پرسش‌وپاسخ بسازید.") }
        var busy by remember { mutableStateOf(false) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var quiz by remember { mutableStateOf<List<QuizQ>>(emptyList()) }
        var quizIndex by remember { mutableIntStateOf(0) }
        var chosen by remember { mutableIntStateOf(-1) }
        var score by remember { mutableIntStateOf(0) }
        var answered by remember { mutableStateOf(false) }
        var showQuiz by remember { mutableStateOf(false) }
        var savedQuizzes by remember { mutableStateOf(listOf<File>()) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if(uri != null) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_:Exception) {}
                pdfUri = uri
                pdfName = uri.lastPathSegment ?: "PDF"
                page = 0
                busy = true
                Thread {
                    try {
                        val text = extractPdfText(uri)
                        runOnUiThread {
                            bookText = text
                            pageCount = try { PDDocument.load(copyUriToCache(uri)).use { it.numberOfPages } } catch (_:Exception) { 0 }
                            pageText = getPageText(text, 0, pageCount)
                            bitmap = renderPage(uri, 0)
                            output = "PDF آماده است. حالت زمینه را انتخاب کنید."
                            busy = false
                        }
                    } catch(e:Exception) { runOnUiThread { output = "خطا در باز کردن PDF: ${e.message}"; busy=false } }
                }.start()
            }
        }
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ChatPDF", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Text("دستیار هوشمند مطالعهٔ PDF", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("AI", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
            }
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("کتابخانهٔ من", style = MaterialTheme.typography.titleMedium)
                    Text(pdfName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("＋ افزودن PDF") }
                OutlinedButton(onClick = { if(pdfUri!=null && page>0) { page--; pageText=getPageText(bookText,page,pageCount); bitmap=renderPage(pdfUri!!,page) } }, enabled = page>0) { Text("قبلی") }
                OutlinedButton(onClick = { if(pdfUri!=null && page<pageCount-1) { page++; pageText=getPageText(bookText,page,pageCount); bitmap=renderPage(pdfUri!!,page) } }, enabled = page<pageCount-1) { Text("بعدی") }
            }
                    if(pageCount>0) Text("صفحه ${page+1} از $pageCount", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max=420.dp)) }
                }
            }
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ابزارهای مطالعه", style = MaterialTheme.typography.titleMedium)
            Text("زمینهٔ پاسخ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("انتخاب‌شده", "صفحه", "کل کتاب").forEach { label ->
                    FilterChip(selected=mode==label, onClick={mode=label}, label={Text(label)})
                }
            }
            if(mode=="انتخاب‌شده") {
                OutlinedTextField(value=selectedText,onValueChange={selectedText=it},label={Text("متن انتخابی را اینجا بچسبانید")},modifier=Modifier.fillMaxWidth(),minLines=3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled=!busy && (contextText(mode, pageText, bookText, selectedText).isNotBlank()), onClick={
                    val text=contextText(mode,pageText,bookText,selectedText)
                    runAsync({ ai("این متن را به زبان فارسی روشن و آموزشی توضیح بده. نکات کلیدی و مثال کوتاه بیاور.", text) }, { output=it }, { output="خطا: $it" }, {busy=it})
                }) { Text("توضیح با AI") }
                Button(enabled=!busy && contextText(mode,pageText,bookText,selectedText).isNotBlank(), onClick={
                    val text=contextText(mode,pageText,bookText,selectedText)
                    runAsync({ generateQuiz(text) }, { result ->
                        quiz=if(result.isBlank()) emptyList() else parseQuiz(result)
                        quizIndex=0; score=0; chosen=-1; answered=false
                        showQuiz=quiz.isNotEmpty()
                        output=if(quiz.isEmpty()) "سؤالی از پاسخ AI تشخیص داده نشد؛ دوباره تلاش کنید." else "آزمون ساخته شد (${quiz.size} سؤال)."
                    }, {output="خطا: $it"}, {busy=it})
                }) { Text("ساخت آزمون") }
            }
                }
            }
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("گفت‌وگو با سند", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value=question,onValueChange={question=it},label={Text("سؤال خود را دربارهٔ PDF بنویسید")},modifier=Modifier.fillMaxWidth(),minLines=2, shape=RoundedCornerShape(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled=!busy && question.isNotBlank() && contextText(mode,pageText,bookText,selectedText).isNotBlank(),onClick={
                    val q=question
                    val text=contextText(mode,pageText,bookText,selectedText)
                    runAsync({ ai("فقط بر اساس متن منبع پاسخ بده. اگر پاسخ در متن نیست، صریح بگو. پاسخ فارسی و دقیق باشد.", "متن منبع:\n${text.take(14000)}\n\nپرسش: $q") }, {output=it}, {output="خطا: $it"}, {busy=it})
                }) { Text("پرسش‌وپاسخ") }
                OutlinedButton(onClick={ if(output.isNotBlank()) tts?.speak(output,TextToSpeech.QUEUE_FLUSH,null,"chatpdf-output") }) { Text("خواندن پاسخ") }
                OutlinedButton(onClick={ if(pageText.isNotBlank()) tts?.speak(pageText.take(3500),TextToSpeech.QUEUE_FLUSH,null,"chatpdf-page") }) { Text("خواندن صفحه") }
            }
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color=MaterialTheme.colorScheme.primary)
            Surface(shape=RoundedCornerShape(22.dp), color=MaterialTheme.colorScheme.surface, tonalElevation=2.dp, modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("پاسخ هوش مصنوعی", style=MaterialTheme.typography.titleMedium, color=MaterialTheme.colorScheme.primary)
                    Text(output, textAlign=TextAlign.Start)
                }
            }
            if(showQuiz && quiz.isNotEmpty()) {
                HorizontalDivider()
                Text("آزمون (${quizIndex+1}/${quiz.size})", style=MaterialTheme.typography.titleLarge)
                Text(quiz[quizIndex].question)
                quiz[quizIndex].options.forEachIndexed { i, opt ->
                    Row {
                        RadioButton(selected=chosen==i,onClick={if(!answered) chosen=i}, enabled=!answered)
                        Text(opt,Modifier.padding(top=12.dp))
                    }
                }
                if(!answered) Button(onClick={
                    if(chosen>=0) { answered=true; if(chosen==quiz[quizIndex].answer) score++ }
                },enabled=chosen>=0) { Text("ثبت پاسخ") }
                if(answered) {
                    Text(if(chosen==quiz[quizIndex].answer) "درست است!" else "پاسخ درست: ${quiz[quizIndex].options[quiz[quizIndex].answer]}")
                    Text(quiz[quizIndex].explanation)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={
                            if(quizIndex<quiz.lastIndex) {quizIndex++;chosen=-1;answered=false}
                            else {output="پایان آزمون: امتیاز $score از ${quiz.size}"; showQuiz=false}
                        }) { Text(if(quizIndex<quiz.lastIndex) "سؤال بعدی" else "پایان") }
                        OutlinedButton(onClick={ saveQuiz(quiz); output="آزمون در حافظهٔ برنامه ذخیره شد." }) { Text("ذخیره آزمون") }
                    }
                }
            }
            OutlinedButton(onClick={
                savedQuizzes=File(ctx.filesDir,"quizzes").listFiles()?.filter{it.extension=="json"}?.sortedByDescending{it.lastModified()} ?: emptyList()
                output=if(savedQuizzes.isEmpty()) "هنوز آزمون ذخیره‌شده‌ای وجود ندارد." else "آزمون‌های ذخیره‌شده:\n"+savedQuizzes.mapIndexed{i,f->"${i+1}. ${f.nameWithoutExtension}"}.joinToString("\n")+"\nفهرست در پوشهٔ داخلی برنامه نگهداری می‌شود."
            }) { Text("آزمون‌های ذخیره‌شده") }
            savedQuizzes.forEach { f ->
                TextButton(onClick={
                    try { quiz=readQuiz(f); quizIndex=0; score=0; chosen=-1; answered=false; showQuiz=quiz.isNotEmpty(); output="آزمون بارگذاری شد." } catch(e:Exception) { output="خطا: ${e.message}" }
                }) { Text("باز کردن: ${f.nameWithoutExtension}") }
            }
        }
    }

    private fun runAsync(work:()->String, success:(String)->Unit, failure:(String)->Unit, busy:(Boolean)->Unit) {
        busy(true)
        Thread {
            try { val result=work(); runOnUiThread { success(result); busy(false) } }
            catch(e:Exception) { runOnUiThread { failure(e.message ?: e.toString()); busy(false) } }
        }.start()
    }
    private fun copyUriToCache(uri: Uri): File {
        val f=File(cacheDir,"chatpdf-current.pdf")
        contentResolver.openInputStream(uri).use { input -> FileOutputStream(f).use { output -> input!!.copyTo(output) } }
        return f
    }
    private fun extractPdfText(uri:Uri):String {
        val f=copyUriToCache(uri)
        return PDDocument.load(f).use { doc ->
            val stripper=PDFTextStripper()
            stripper.sortByPosition=true
            stripper.getText(doc).take(200000)
        }
    }
    private fun getPageText(all:String, page:Int, count:Int):String {
        if(all.isBlank()) return ""
        // PDFBox text is separated by form-feed on most documents.
        val pages=all.split('\u000c')
        return pages.getOrNull(page)?.take(18000) ?: if(count<=1) all.take(18000) else ""
    }
    private fun renderPage(uri:Uri, index:Int):Bitmap? {
        return try {
            val f=copyUriToCache(uri)
            val pfd=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd).use { renderer ->
                if(index !in 0 until renderer.pageCount) return null
                renderer.openPage(index).use { pdfPage ->
                    val width=1000
                    val height=(width.toFloat()*pdfPage.height/pdfPage.width).toInt().coerceAtMost(1800)
                    val bmp=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    pdfPage.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        } catch(_:Exception) { null }
    }
    private fun ai(system:String,user:String):String {
        val body=JSONObject().put("model","openai").put("messages",JSONArray()
            .put(JSONObject().put("role","system").put("content",system))
            .put(JSONObject().put("role","user").put("content",user)))
            .put("temperature",0.4).put("max_tokens",1200)
        val conn=URL("https://text.pollinations.ai/openai").openConnection() as HttpURLConnection
        conn.requestMethod="POST"; conn.connectTimeout=20000; conn.readTimeout=90000
        conn.setRequestProperty("Content-Type","application/json")
        conn.setRequestProperty("Authorization","Bearer pollinations")
        conn.doOutput=true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code=conn.responseCode
        val stream=if(code in 200..299) conn.inputStream else conn.errorStream
        val response=stream.bufferedReader().use{it.readText()}
        conn.disconnect()
        if(code !in 200..299) throw IllegalStateException("سرویس AI پاسخ HTTP $code داد: ${response.take(500)}")
        val json=JSONObject(response)
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf{it.isNotBlank()} ?: response
    }
    private fun generateQuiz(text:String):String {
        val prompt="""از متن زیر 5 سؤال چهارگزینه‌ای فارسی بساز. برای هر سؤال دقیقاً از این قالب استفاده کن:
[Q] متن سؤال
[1] گزینه اول
[2] گزینه دوم
[3] گزینه سوم
[4] گزینه چهارم
[ANS] شماره گزینه درست (1 تا 4)
[EXP] توضیح کوتاه پاسخ
[/Q]
فقط همین قالب را خروجی بده.
متن:
${text.take(12000)}"""
        val result=ai("تو سازنده آزمون آموزشی دقیق هستی.",prompt)
        val regex=Regex("""\[Q\]\s*(.*?)\s*\[1\]\s*(.*?)\s*\[2\]\s*(.*?)\s*\[3\]\s*(.*?)\s*\[4\]\s*(.*?)\s*\[ANS\]\s*([1-4۱-۴])\s*\[EXP\]\s*(.*?)\s*\[/Q\]""",setOf(RegexOption.DOT_MATCHES_ALL))
        val qs=regex.findAll(result).mapNotNull { m ->
            val a=m.groupValues[6].firstOrNull()?.let { if(it in '۱'..'۴') it-'۱' else it-'1' } ?: -1
            if(a !in 0..3) null else QuizQ(m.groupValues[1].trim(),listOf(m.groupValues[2].trim(),m.groupValues[3].trim(),m.groupValues[4].trim(),m.groupValues[5].trim()),a,m.groupValues[7].trim())
        }.toList()
        if(qs.isEmpty()) return ""
        return JSONObject().put("questions",JSONArray().also { arr -> qs.forEach { q -> arr.put(JSONObject().put("question",q.question).put("options",JSONArray(q.options)).put("answer",q.answer).put("explanation",q.explanation)) } }).toString()
    }
    private fun parseQuiz(json:String):List<QuizQ> {
        val arr=JSONObject(json).getJSONArray("questions")
        return (0 until arr.length()).map { i ->
            val q=arr.getJSONObject(i); val o=q.getJSONArray("options")
            QuizQ(q.getString("question"),(0 until o.length()).map{o.getString(it)},q.getInt("answer"),q.optString("explanation"))
        }
    }
    private fun saveQuiz(quiz:List<QuizQ>) {
        val dir=File(filesDir,"quizzes"); dir.mkdirs()
        val arr=JSONArray()
        quiz.forEach { q -> arr.put(JSONObject().put("question",q.question).put("options",JSONArray(q.options)).put("answer",q.answer).put("explanation",q.explanation)) }
        File(dir,"آزمون_${System.currentTimeMillis()}.json").writeText(JSONObject().put("questions",arr).toString(),Charsets.UTF_8)
    }
    private fun readQuiz(f:File):List<QuizQ> = parseQuiz(f.readText(Charsets.UTF_8))
}
private fun contextText(mode:String,page:String,book:String,selected:String):String = when(mode) {
    "انتخاب‌شده" -> selected.trim()
    "کل کتاب" -> book.take(200000)
    else -> page.trim()
}
