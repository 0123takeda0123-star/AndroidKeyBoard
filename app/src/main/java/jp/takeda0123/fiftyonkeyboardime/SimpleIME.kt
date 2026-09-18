package jp.takeda0123.fiftyonkeyboardime

import android.content.ContentValues
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import androidx.core.content.ContextCompat
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import android.widget.TextView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.widget.Switch
import android.view.inputmethod.ExtractedTextRequest
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.graphics.Color
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent


class SimpleIME : InputMethodService() {

    /* =========================================================
     * ■ IMEモード定義（入力中 / 変換中）
     * ========================================================= */
    enum class ImeMode {
        INPUT,
        CONVERT
    }

    /* =========================================================
     * ■ キーボード種別定義
     * ========================================================= */
    enum class KeyboardType {
        JAPANESE,
        ABC,
        QWERTY,
        SETTINGS
    }

    /* =========================================================
     * ■ 状態保持フィールド
     * ========================================================= */
    private var keyboardView: View? = null
    private lateinit var candidateButtons: List<Button>

    private var mode = ImeMode.INPUT
    // 未確定の入力文字列
    private var plainText = ""
    private var candidates: List<String> = emptyList()
    private var keyboardType = KeyboardType.JAPANESE
    private var isUpperCase = false
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var cursorVisible = true

    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            updatePreviewDisplay()
            deleteHandler.postDelayed(this, 500)
        }
    }
    private var deleteRunnable: Runnable? = null
    private val deleteRepeatDelay = 100L
    private val convertedSegments = mutableListOf<String>()

    private val keyboardLoop = listOf(
        KeyboardType.JAPANESE,
        KeyboardType.ABC,
        KeyboardType.QWERTY
    )

    enum class KeyboardTheme(
        val bgRes: Int,
        val fgRes: Int
    ) {
        WHITE(R.color.white, R.color.black),
        YELLOW(R.color.yellow, R.color.black),
        GREEN(R.color.green, R.color.black),
        GRAY(R.color.gray, R.color.white),
        BLACK(R.color.black, R.color.white),
        TRANSPARENT(R.color.preview, R.color.preview)
    }

    /* =========================================================
     * ■ 文節変換管理
     * ========================================================= */
    private var segmentCandidates: List<SegmentCandidate> = emptyList()
    private data class SegmentCandidate(
        val reading: String,
        val candidates: List<String>
    )
    private var currentSegmentIndex = 0

    private var conversionStart = 0      // プレビュー内ではなく変換対象の開始位置
    private var conversionLength = 0     // APIに投げた元文字列の長さ

    /* =========================================================
     * ■ かな変換ループ定義（濁点・小文字等）
     * ========================================================= */
    private val minusLoopMap: Map<String, List<String>> = mapOf(
        "あ" to listOf("あ", "ぁ"), "い" to listOf("い", "ぃ"),
        "う" to listOf("う", "ぅ"), "え" to listOf("え", "ぇ"),
        "お" to listOf("お", "ぉ"),
        "か" to listOf("か", "が"), "き" to listOf("き", "ぎ"),
        "く" to listOf("く", "ぐ"), "け" to listOf("け", "げ"),
        "こ" to listOf("こ", "ご"),
        "さ" to listOf("さ", "ざ"), "し" to listOf("し", "じ"),
        "す" to listOf("す", "ず"), "せ" to listOf("せ", "ぜ"),
        "そ" to listOf("そ", "ぞ"),
        "た" to listOf("た", "だ"), "ち" to listOf("ち", "ぢ"),
        "つ" to listOf("つ", "っ", "づ"), "て" to listOf("て", "で"),
        "と" to listOf("と", "ど"),
        "は" to listOf("は", "ば", "ぱ"), "ひ" to listOf("ひ", "び", "ぴ"),
        "ふ" to listOf("ふ", "ぶ", "ぷ"), "へ" to listOf("へ", "べ", "ぺ"),
        "ほ" to listOf("ほ", "ぼ", "ぽ"),
        "や" to listOf("や", "ゃ"), "ゆ" to listOf("ゆ", "ゅ"),
        "よ" to listOf("よ", "ょ"), "わ" to listOf("わ", "ゎ")
    )

    private val kakkoCharLoop = listOf("「", "」", "（", "）")
    private val dotCharLoop = listOf("、", "。")

    /* =========================================================
     * ■ キーボード種別切替
     * ========================================================= */
    private fun nextKeyboardType(): KeyboardType {
        val i = keyboardLoop.indexOf(keyboardType)
        return keyboardLoop[(i + 1) % keyboardLoop.size]
    }

    /* =========================================================
     * ■ 直前文字置換処理（濁点ループ用）
     * ========================================================= */
    private fun replaceLastChar(newChar: String) {
        if (plainText.isEmpty()) return
        plainText = plainText.dropLast(1) + newChar
    }

    /* =========================================================
     * ■ キー入力振り分け
     * ========================================================= */
    private fun onKeyInput(label: String) {
        when (keyboardType) {
            KeyboardType.JAPANESE -> handleJapaneseInput(label)
            KeyboardType.ABC,
            KeyboardType.QWERTY -> handleAlphabetInput(label)
            KeyboardType.SETTINGS -> {}
        }
    }

    private fun handleJapaneseInput(label: String) {
        debugLog("convert pressed, mode=$mode")
        if (mode == ImeMode.CONVERT) {
            // 変換中に文字が押されたら変換キャンセル
            cancelConversion()
        }
        plainText += label
        updatePreviewDisplay()
    }

    private fun handleAlphabetInput(label: String) {
        val out = if (isUpperCase) label.uppercase() else label.lowercase()
        currentInputConnection.commitText(out, 1)
    }

    /* =========================================================
     * ■ 削除処理（通常 / 変換中）
     * ========================================================= */
    private fun deleteCharacter() {
        val ic = currentInputConnection ?: return
        val before1 = ic.getTextBeforeCursor(1, 0)
        val before5 = ic.getTextBeforeCursor(5, 0)
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)

        debugLog("before1=[$before1]")
        debugLog("before5=[$before5]")
        debugLog("extracted=[${extracted?.text}]")

        // 変換中なら候補表示を終了
        if (mode == ImeMode.CONVERT) {
            cancelConversion()
        }

        // ① 未変換平文を削除
        if (plainText.isNotEmpty()) {
            plainText = plainText.dropLast(1)
            updatePreviewDisplay()
            return
        }

        // ② 変換済み文節の右端を削除
        if (convertedSegments.isNotEmpty()) {
            val lastIndex = convertedSegments.lastIndex
            val last = convertedSegments[lastIndex]

            if (last.length == 1) {
                convertedSegments.removeAt(lastIndex)
            } else {
                convertedSegments[lastIndex] = last.dropLast(1)
            }

            updatePreviewDisplay()
            return
        }

        // ③ カーソル前に文字が無ければ何もしない
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) {
            return
        }


        // 本当に文書先頭なら何もしない
        if (extracted?.selectionStart == 0) {
            return
        }
        ic.deleteSurroundingText(1, 0)

        // 削除反映後にプレビュー更新
        Handler(Looper.getMainLooper()).post {
            updatePreviewDisplay()
            val before = ic.getTextBeforeCursor(5, 0)?.toString()
            debugLog("before=${before?.replace("\n", "\\n")}")
        }
    }

    /* =========================================================
     * ■ IME表示開始時の初期化
     * ========================================================= */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        debugLog("onStartInputView restarting=$restarting")
        super.onStartInputView(info, restarting)

        if (!restarting) {
            plainText = ""
            convertedSegments.clear()
            cancelConversion()
            updatePreviewDisplay()
        }
        deleteHandler.removeCallbacks(cursorBlinkRunnable)
        cursorVisible = true
        deleteHandler.post(cursorBlinkRunnable)
        applyColors()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        deleteHandler.removeCallbacks(cursorBlinkRunnable)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // ========================================
    // AlphabetAa
    // ========================================
    private fun updateAlphabetKeyLabels(rootView: View) {
        // アルファベットキーIDリスト
        val alphabetIds = listOf(
            R.id.a, R.id.b, R.id.c, R.id.d, R.id.e, R.id.f, R.id.g,
            R.id.h, R.id.i, R.id.j, R.id.k, R.id.l, R.id.m, R.id.n,
            R.id.o, R.id.p, R.id.q, R.id.r, R.id.s, R.id.t, R.id.u,
            R.id.v, R.id.w, R.id.x, R.id.y, R.id.z
        )

        alphabetIds.forEach { id ->
            val button = rootView.findViewById<Button>(id)
            val label = button.text.toString()
            button.text = if (isUpperCase) label.uppercase() else label.lowercase()
        }
    }

    /* =========================================================
     * ■ log
     * ========================================================= */
    private val debugBuffer = StringBuilder()
    private val MAX_LOG_SIZE = 20000  // 上限（肥大化防止）

    private fun debugLog(message: String) {
        val line = "${System.currentTimeMillis()} : $message\n"
        debugBuffer.append(line)

        // 上限超えたら前半を削る（メモリ肥大化防止）
        if (debugBuffer.length > MAX_LOG_SIZE) {
            debugBuffer.delete(0, debugBuffer.length / 2)
        }
    }

    /* =========================================================
     * ■ 入力ビュー生成
     * ========================================================= */
    override fun onCreateInputView(): View {
        // ========================================
        // レイアウト選択
        // ========================================
        val layoutRes = when (keyboardType) {
            KeyboardType.JAPANESE -> R.layout.keyboard
            KeyboardType.ABC -> R.layout.keyboard_abc
            KeyboardType.QWERTY -> R.layout.keyboard_qwerty
            KeyboardType.SETTINGS -> R.layout.dialog_color_settings
        }

        val view = layoutInflater.inflate(layoutRes, null)
        keyboardView = view
        applyColors(view)

        val useLongLabel = resources.configuration.screenWidthDp >= 800

        view.findViewById<Button>(R.id.del)?.text =
            if (useLongLabel) "⌫けす" else "⌫"

        view.findViewById<Button>(R.id.enter)?.text =
            if (useLongLabel) "かくてい" +
                    "⏎" else "⏎"



        view.findViewById<Button>(R.id.setting)?.text =
            if (useLongLabel) "⚙" else "⚙"

        // ========================================
        // 設定画面モード（SETTINGS用）
        // ========================================
        if (keyboardType == KeyboardType.SETTINGS) {
            val themeMap = mapOf(
                R.id.color_white to KeyboardTheme.WHITE,
                R.id.color_yellow to KeyboardTheme.YELLOW,
                R.id.color_green to KeyboardTheme.GREEN,
                R.id.color_gray to KeyboardTheme.GRAY,
                R.id.color_black to KeyboardTheme.BLACK
            )

            themeMap.forEach { (buttonId, theme) ->
                view.findViewById<Button>(buttonId)?.setOnClickListener {
                    saveTheme(theme)
                    keyboardType = KeyboardType.JAPANESE
                    setInputView(onCreateInputView())
                }
            }

            val previewSwitch = view.findViewById<Switch>(R.id.switch_preview)
            previewSwitch?.isChecked = previewEnabled
            previewSwitch?.setOnCheckedChangeListener { _, isChecked ->
                previewEnabled = isChecked
                keyboardType = KeyboardType.JAPANESE
                setInputView(onCreateInputView())
            }

            return view
        }

        // ========================================
        // 変換候補ボタン初期化
        // ========================================
        candidateButtons = listOfNotNull(
            view.findViewById(R.id.kanji_1),
            view.findViewById(R.id.kanji_2),
            view.findViewById(R.id.kanji_3),
            view.findViewById(R.id.kanji_4)
        )
        candidateButtons.forEach { it.visibility = View.INVISIBLE }

        // ========================================
        // 通常キーIDリスト（入力用）
        // ========================================
        val keyIds = listOf(
            R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
            R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0,
            R.id.wa, R.id.ra, R.id.ya, R.id.ma, R.id.ha, R.id.na,
            R.id.ta, R.id.sa, R.id.ka, R.id.a, R.id.qes, R.id.wo,
            R.id.ri, R.id.mi, R.id.hi, R.id.ni, R.id.ti, R.id.si,
            R.id.ki, R.id.i, R.id.eqs, R.id.nn, R.id.ru, R.id.yu,
            R.id.mu, R.id.hu, R.id.nu, R.id.tu, R.id.su, R.id.ku,
            R.id.u, R.id.long_bar, R.id.re, R.id.me, R.id.he,
            R.id.ne, R.id.te, R.id.se, R.id.ke, R.id.e, R.id.ro,
            R.id.yo, R.id.mo, R.id.ho, R.id.no, R.id.to, R.id.so,
            R.id.ko, R.id.o, R.id.sp, R.id.setting2,
            R.id.b, R.id.c, R.id.d, R.id.f, R.id.g,
            R.id.h, R.id.j, R.id.k, R.id.l, R.id.m,
            R.id.n, R.id.p, R.id.q, R.id.r, R.id.s,
            R.id.t, R.id.v, R.id.w, R.id.x, R.id.y, R.id.z, R.id.quo
        )

        keyIds.forEach { id ->
            view.findViewById<Button>(id)?.setOnClickListener { button ->
                onKeyInput((button as Button).text.toString())
                updatePreviewDisplay()
            }
        }

        // ========================================
        // 特殊キー設定（削除・変換・シフト・スペース等）
        // ========================================
        view.findViewById<Button>(R.id.ABC)?.setOnClickListener {
            if (plainText.isNotEmpty()) {
                currentInputConnection.commitText(plainText, 1)
                plainText = ""
            }
            keyboardType = nextKeyboardType()
            setInputView(onCreateInputView())
            clearCandidateView()
            mode = ImeMode.INPUT
        }

        view.findViewById<Button>(R.id.enter)?.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener

            if (convertedSegments.isNotEmpty() || plainText.isNotEmpty()) {
                commitCurrentComposition()
            } else {
                // 未確定文字が無い場合は改行
                ic.commitText("\n", 1)
            }
        }

        view.findViewById<Button>(R.id.shift)?.setOnClickListener {
            isUpperCase = !isUpperCase
            updateAlphabetKeyLabels(view)
        }

        view.findViewById<Button>(R.id.minus)?.setOnClickListener {
            if (mode != ImeMode.INPUT || plainText.isBlank()) return@setOnClickListener
            val last = plainText.last().toString()
            val loop = minusLoopMap.entries.firstOrNull { it.value.contains(last) }?.value ?: return@setOnClickListener
            replaceLastChar(loop[(loop.indexOf(last) + 1) % loop.size])
            updatePreviewDisplay()
        }

        view.findViewById<Button>(R.id.kakko)?.setOnClickListener {
            if (mode != ImeMode.INPUT) return@setOnClickListener
            val last = plainText.lastOrNull()?.toString()
            if (last != null && kakkoCharLoop.contains(last)) {
                val index = kakkoCharLoop.indexOf(last)
                replaceLastChar(kakkoCharLoop[(index + 1) % kakkoCharLoop.size])
                updatePreviewDisplay()
            } else {
                plainText += kakkoCharLoop[0]
                updatePreviewDisplay()
            }
        }

        view.findViewById<Button>(R.id.setting)?.setOnClickListener {
            keyboardType = KeyboardType.SETTINGS
            setInputView(onCreateInputView())
        }

        view.findViewById<Button>(R.id.tra)?.setOnClickListener {
            debugLog("Convert pressed. plainText=$plainText mode=$mode")

            if (mode != ImeMode.INPUT || plainText.isBlank()) return@setOnClickListener

            conversionLength = plainText.length

            requestTransliterate(plainText.trim()) {
                if (segmentCandidates.isNotEmpty()) {
                    mode = ImeMode.CONVERT
                    showCandidates(
                        segmentCandidates[0]
                            .candidates
                            .take(4)
                    )
                    updatePreviewDisplay()
                }
            }
        }
        view.findViewById<Button>(R.id.dot)?.setOnClickListener {
            if (mode != ImeMode.INPUT) return@setOnClickListener
            val last =plainText.lastOrNull()?.toString()
            if (last != null && dotCharLoop.contains(last)) {
                val index = dotCharLoop.indexOf(last)
                replaceLastChar(dotCharLoop[(index + 1) % dotCharLoop.size])
                updatePreviewDisplay()
            } else {
                plainText += dotCharLoop[0]
                updatePreviewDisplay()
            }
        }

        view.findViewById<Button>(R.id.sp)?.setOnClickListener {
            if (mode == ImeMode.CONVERT) {
                cancelConversion()
            }

            if (keyboardType == KeyboardType.JAPANESE) {
                plainText += "　"
            } else {
                plainText += " "
            }

            updatePreviewDisplay()
        }

        view.findViewById<Button>(R.id.del)?.setOnClickListener {
            deleteCharacter()
        }

        view.findViewById<Button>(R.id.setting2)?.setOnClickListener {
            exportLogToDownloads()
        }

        // ========================================
        // 色反映（再適用）
        // ========================================
        applyColors(view)


        val previewText = view.findViewById<TextView>(R.id.preview_text)
        previewText?.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP) {
                return@setOnTouchListener true
            }
            handlePreviewTap(previewText, event.x, event.y)
            true
        }
        return view
    }

    private fun handlePreviewTap(
        previewText: TextView,
        x: Float,
        y: Float
    ) {
        val ic = currentInputConnection ?: return
        val layout = previewText.layout ?: return

        val line = layout.getLineForVertical(y.toInt())
        val offset = layout.getOffsetForHorizontal(line, x)

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return


        if (mode == ImeMode.CONVERT) {
            commitCurrentComposition()

            // プレビュー内のカーソル位置（before の末尾）
            val previewCursor = previewBeforeLength

// タップ位置との差分
            val delta = offset - previewCursor

// 実際のカーソル位置
            val newPos = extracted.selectionStart + delta
        }

        // 範囲外にならないように補正
        val textLength = extracted.text?.length ?: newPos
        val clamped = newPos.coerceIn(0, textLength)

        ic.setSelection(clamped, clamped)

        updatePreviewDisplay()

        debugLog(
            "tap offset=$offset " +
                    "selStart=${extracted.selectionStart} " +
                    "selEnd=${extracted.selectionEnd}"
        )
    }

    private fun commitCurrentComposition(){
        val ic = currentInputConnection ?: return
        // ① 変換済み文節を確定
        convertedSegments.forEach {
            ic.commitText(it, 1)
        }
        // ② 未変換平文を確定
        if (plainText.isNotEmpty()) {
            ic.commitText(plainText, 1)
        }
        // ③ 状態をクリア
        convertedSegments.clear()
        plainText = ""
        cancelConversion()
        updatePreviewDisplay()
    }
    private fun cancelConversion(){
        mode = ImeMode.INPUT
        segmentCandidates = emptyList()
        currentSegmentIndex = 0
        clearCandidateView()
    }

    /* =========================================================
     * ■ 候補選択処理
     * ========================================================= */
    private fun onCandidateSelected(index: Int) {
        if (mode != ImeMode.CONVERT) return
        if (segmentCandidates.isEmpty()) return

        val currentList = segmentCandidates[currentSegmentIndex]
        if (index !in currentList.candidates.indices) return

        val selected = currentList.candidates[index]

        // 選択した候補を変換済み文節へ追加
        convertedSegments.add(selected)

        // 未変換平文から今回変換した読みを取り除く
        plainText = plainText.removePrefix(currentList.reading)

        currentSegmentIndex++

        if (currentSegmentIndex < segmentCandidates.size) {

            showCandidates(
                segmentCandidates[currentSegmentIndex]
                    .candidates
                    .take(4)
            )

        } else {
            commitCurrentComposition()
        }

        updatePreviewDisplay()
    }

    private fun showCandidates(list: List<String>) {
        debugLog("showCandidates called size=${list.size}")
        val view = keyboardView ?: return
        view.post {
            candidateButtons.forEachIndexed { index, button ->
                if (index < list.size) {
                    button.text = list[index]
                    button.visibility = View.VISIBLE
                    button.setOnClickListener { onCandidateSelected(index) }
                } else {
                    button.text = ""
                    button.visibility = View.INVISIBLE
                    button.setOnClickListener(null)
                }
            }
        }
    }

    /* =========================================================
     * ■ 色保存 / 読み込み
     * ========================================================= */
    private fun saveTheme(theme: KeyboardTheme) {
        val prefs = getSharedPreferences("ime_prefs", MODE_PRIVATE)
        prefs.edit().putString("theme", theme.name).apply()
    }

    private fun loadTheme(): KeyboardTheme {
        val prefs = getSharedPreferences("ime_prefs", MODE_PRIVATE)
        val name = prefs.getString("theme", KeyboardTheme.WHITE.name)
        return KeyboardTheme.valueOf(name!!)
    }

    private fun applyColors(rootView: View? = keyboardView) {
        // SETTINGS画面にはテーマを適用しない
        if (keyboardType == KeyboardType.SETTINGS) return

        val theme = loadTheme()
        val bgColor = ContextCompat.getColor(this, theme.bgRes)
        val fgColor = ContextCompat.getColor(this, theme.fgRes)
        val view = rootView ?: return

        // 1. 全体の親は常に透明（窓の土台）
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // 2. 下段エリア（候補＋キーボード）をテーマ色で不透明にする
        view.findViewById<View>(R.id.main_keyboard_area)
            ?.setBackgroundColor(bgColor)

        // 3. プレビューエリアの「窓」の状態を切り替える
        val previewContainer = view.findViewById<View>(R.id.preview_container)
        val previewText = view.findViewById<TextView>(R.id.preview_text)

        if (previewEnabled) {
            // ON：下段と同じ背景色と文字色
            previewContainer?.setBackgroundColor(bgColor)
            previewText?.setTextColor(fgColor)
        } else {
            // OFF：背景も文字も透明（下のアプリを透過）
            previewContainer?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            previewText?.setTextColor(android.graphics.Color.TRANSPARENT)
        }

        // 4. すべてのボタンに対して文字色と枠線を適用
        applyWidgetColors(view, fgColor)
    }

    // ボタン等の細かい色をスキャンして適用する補助関数
    private fun applyWidgetColors(view: View, fgColor: Int) {
        val queue = mutableListOf<View>(view)
        while (queue.isNotEmpty()) {
            val v = queue.removeAt(0)
            if (v is Button) {
                v.setTextColor(fgColor)
                val bg = v.background
                if (bg is android.graphics.drawable.GradientDrawable) {
                    bg.setStroke(4, fgColor)
                }
            } else if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    queue.add(v.getChildAt(i))
                }
            }
        }
    }

    private val PREVIEW_SIDE_CHARS = 25

    private var previewBeforeLength = 0
    private var previewConvertedLength = 0
    private var previewPlainLength = 0
    private fun updatePreviewDisplay() {
        val ic = currentInputConnection ?: return
        val previewText = keyboardView?.findViewById<TextView>(R.id.preview_text) ?: return

        val before = ic.getTextBeforeCursor(PREVIEW_SIDE_CHARS, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(PREVIEW_SIDE_CHARS, 0)?.toString() ?: ""

        val converted = convertedSegments.joinToString("")

        // 今後タップ判定などで使うため保存
        previewBeforeLength = before.length
        previewConvertedLength = converted.length
        previewPlainLength = plainText.length

        val builder = SpannableStringBuilder()

        builder.append(before)
        builder.append(converted)
        builder.append(plainText)
        if (
            mode == ImeMode.CONVERT &&
            currentSegmentIndex in segmentCandidates.indices
        ) {
            val currentReading =
                segmentCandidates[currentSegmentIndex].reading

            val start = previewBeforeLength + previewConvertedLength
            val end = start + currentReading.length

            builder.setSpan(
                BackgroundColorSpan(Color.argb(80, 80, 160, 255)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }


        if (cursorVisible) {
            builder.append("|")
        }

        builder.append(after)

        previewText.text = builder
    }

    private var previewEnabled = true

    private fun applyPreviewColors(root: View, bgColor: Int, fgColor: Int) {
        val previewContainer = root.findViewById<View>(R.id.preview_container)
        val previewText = root.findViewById<TextView>(R.id.preview_text)
        if (previewContainer == null || previewText == null) return

        if (previewEnabled) {
            previewContainer.setBackgroundColor(bgColor)
            previewText.setTextColor(fgColor)
        } else {
            previewContainer.setBackgroundColor(0x00000000)
            previewText.setTextColor(0x00000000)
        }
    }

    /* =========================================================
     * ■ 変換API通信
     * ========================================================= */
    private fun requestTransliterate(text: String, callback: () -> Unit) {
        Thread {
            try {
                debugLog("Transliterate start: $text")

                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = "https://www.google.com/transliterate?langpair=ja-Hira|ja&text=$encoded"

                debugLog("URL: $url")

                val response = URL(url).readText()
                debugLog("Response: $response")

                val json = JSONArray(response)
                val segments = mutableListOf<SegmentCandidate>()
                for (i in 0 until json.length()) {
                    val item = json.getJSONArray(i)
                    val reading = item.getString(0)
                    val arr = item.getJSONArray(1)
                    val list = mutableListOf<String>()

                    for (j in 0 until arr.length()) {
                        list.add(arr.getString(j))
                    }

                    segments.add(
                        SegmentCandidate(
                            reading = reading,
                            candidates = list
                        )
                    )
                }

                Handler(Looper.getMainLooper()).post {
                    segmentCandidates = segments
                    currentSegmentIndex = 0
                    updatePreviewDisplay()
                    callback()
                }

            } catch (e: Exception) {
                debugLog("Transliterate error: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    segmentCandidates = emptyList()
                    callback()
                }
            }
        }.start()
    }

    private fun clearCandidateView() {
        if (!::candidateButtons.isInitialized) return
        candidateButtons.forEach {
            it.text = ""
            it.visibility = View.INVISIBLE
        }
    }

    /* =========================================================
     * ■ ログ書き出し（Downloads）
     * ========================================================= */
    private fun exportLogToDownloads(): Boolean {
        debugLog("Export pressed")
        return try {
            if (debugBuffer.isEmpty()) {
                debugLog("Log was empty at export")
            }
            val fileName = "ime_log_${System.currentTimeMillis()}.txt"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(
                MediaStore.Files.getContentUri("external"),
                contentValues
            ) ?: return false

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(debugBuffer.toString().toByteArray())
            } ?: return false

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            true

        } catch (e: Exception) {
            false
        }
    }
}
