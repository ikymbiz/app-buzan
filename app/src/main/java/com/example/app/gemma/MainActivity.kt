package com.example.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var userInput: EditText
    private lateinit var sendButton: Button

    // 会話履歴を保持
    private val conversationHistory = mutableListOf<JSONObject>()

    // APIエンドポイント設定（ローカルLLMサーバーやOpenAI互換API）
    private var apiEndpoint = "http://10.0.2.2:8080/v1/chat/completions"
    private var apiKey = ""
    private var modelName = "gemma-4-2b-it"
    private var isReady = false

    // dp変換ヘルパー
    private fun Int.dp(): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SharedPreferencesから設定を読み込み
        val prefs = getSharedPreferences("gemma_chat", MODE_PRIVATE)
        apiEndpoint = prefs.getString("api_endpoint", apiEndpoint) ?: apiEndpoint
        apiKey = prefs.getString("api_key", apiKey) ?: apiKey
        modelName = prefs.getString("model_name", modelName) ?: modelName

        // ルートレイアウト
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#f4f4f9"))
        }

        // ステータスバー
        statusText = TextView(this).apply {
            text = "ステータス: 初期化中..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#666666"))
            setPadding(20.dp(), 8.dp(), 20.dp(), 4.dp())
            setBackgroundColor(Color.parseColor("#f4f4f9"))
        }
        rootLayout.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 設定ボタン行
        val settingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            setBackgroundColor(Color.parseColor("#f4f4f9"))
        }
        val settingsButton = Button(this).apply {
            text = "⚙ API設定"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Color.parseColor("#6c757d"))
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#6c757d"))
                cornerRadius = 5.dp().toFloat()
            }
            background = bg
            setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            setOnClickListener { showSettingsDialog() }
        }
        settingsRow.addView(settingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rootLayout.addView(settingsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // チャット表示エリア（ScrollView + LinearLayout）
        scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#f4f4f9"))
            isFillViewport = true
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
        }
        scrollView.addView(chatContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // 入力エリア
        val inputArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            setBackgroundColor(Color.WHITE)
            // 上部にボーダーを模擬
            val bg = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.parseColor("#cccccc"))
            }
            background = bg
        }

        userInput = EditText(this).apply {
            hint = "Gemma 4にメッセージ..."
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            val bg = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.parseColor("#cccccc"))
                cornerRadius = 5.dp().toFloat()
            }
            background = bg
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage()
                    true
                } else false
            }
        }
        inputArea.addView(userInput, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        sendButton = Button(this).apply {
            text = "送信"
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#007bff"))
                cornerRadius = 5.dp().toFloat()
            }
            background = bg
            setPadding(20.dp(), 10.dp(), 20.dp(), 10.dp())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginStart = 10.dp()
            layoutParams = params
            setOnClickListener { sendMessage() }
        }
        inputArea.addView(sendButton)

        rootLayout.addView(inputArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(rootLayout)

        // 初期化チェック
        checkApiConnection()
    }

    // API接続チェック
    private fun checkApiConnection() {
        statusText.text = "APIサーバーに接続確認中..."
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val url = URL(apiEndpoint)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    if (apiKey.isNotEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    conn.doOutput = true

                    // 簡単なテストリクエスト
                    val testBody = JSONObject().apply {
                        put("model", modelName)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", "hi")
                            })
                        })
                        put("max_tokens", 5)
                    }
                    val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                    writer.write(testBody.toString())
                    writer.flush()
                    writer.close()

                    val responseCode = conn.responseCode
                    conn.disconnect()
                    responseCode in 200..299
                } catch (e: Exception) {
                    false
                }
            }
            if (success) {
                statusText.text = "Gemma 4 準備完了 (モデル: $modelName)"
                isReady = true
            } else {
                statusText.text = "⚠ API接続失敗 - ⚙設定を確認してください"
                isReady = false
                appendInfoMessage("API接続に失敗しました。\n\n「⚙ API設定」からエンドポイントを設定してください。\n\nローカルで利用する場合:\n• llama.cpp / ollama / LM Studio 等でGemmaモデルを起動\n• エンドポイント例:\n  http://10.0.2.2:8080/v1/chat/completions (エミュレータ)\n  http://192.168.x.x:8080/v1/chat/completions (実機)")
            }
        }
    }

    // 情報メッセージ表示
    private fun appendInfoMessage(text: String) {
        val msgView = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#333333"))
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#fff3cd"))
                cornerRadius = 10.dp().toFloat()
            }
            background = bg
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 10.dp()
            layoutParams = params
        }
        chatContainer.addView(msgView)
        scrollToBottom()
    }

    // メッセージ送信
    private fun sendMessage() {
        val prompt = userInput.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(this, "メッセージを入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isReady) {
            Toast.makeText(this, "APIが未接続です。設定を確認してください", Toast.LENGTH_SHORT).show()
            return
        }

        // キーボード非表示
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(userInput.windowToken, 0)

        // ユーザーメッセージ表示
        appendMessage("user", prompt)
        userInput.text.clear()

        // 会話履歴に追加
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })

        // AIの思考中メッセージ
        val thinkingView = appendMessage("gemma", "思考中...")
        sendButton.isEnabled = false
        userInput.isEnabled = false

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                callChatApi()
            }
            // レスポンス反映
            if (response != null) {
                thinkingView.text = response
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", response)
                })
            } else {
                thinkingView.text = "⚠ エラーが発生しました。接続を確認してください。"
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#f8d7da"))
                    cornerRadius = 10.dp().toFloat()
                }
                thinkingView.background = bg
            }
            sendButton.isEnabled = true
            userInput.isEnabled = true
            scrollToBottom()
        }
    }

    // チャットAPI呼び出し
    private fun callChatApi(): String? {
        return try {
            val url = URL(apiEndpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60000
            conn.readTimeout = 120000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (apiKey.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.doOutput = true

            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("messages", JSONArray().apply {
                    for (msg in conversationHistory) {
                        put(msg)
                    }
                })
                put("temperature", 0.7)
                put("max_tokens", 2048)
            }

            val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val responseText = reader.readText()
                reader.close()
                conn.disconnect()

                val json = JSONObject(responseText)
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    message.getString("content").trim()
                } else {
                    null
                }
            } else {
                val errorReader = BufferedReader(InputStreamReader(conn.errorStream, "UTF-8"))
                val errorText = errorReader.readText()
                errorReader.close()
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // メッセージをチャットに追加
    private fun appendMessage(role: String, text: String): TextView {
        val msgView = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 10.dp()

            if (role == "user") {
                // ユーザーメッセージ：右寄せ、青背景
                setTextColor(Color.WHITE)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#007bff"))
                    cornerRadius = 10.dp().toFloat()
                }
                background = bg
                params.gravity = Gravity.END
                params.marginStart = 60.dp()
            } else {
                // Gemmaメッセージ：左寄せ、灰色背景
                setTextColor(Color.BLACK)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#e9e9eb"))
                    cornerRadius = 10.dp().toFloat()
                }
                background = bg
                params.gravity = Gravity.START
                params.marginEnd = 60.dp()
            }
            // テキスト選択可能にする
            setTextIsSelectable(true)
            layoutParams = params
        }
        chatContainer.addView(msgView)
        scrollToBottom()
        return msgView
    }

    // スクロールを一番下に
    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    // 設定ダイアログ
    private fun showSettingsDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
        }

        // エンドポイント入力
        val endpointLabel = TextView(this).apply {
            text = "APIエンドポイント:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#333333"))
        }
        dialogLayout.addView(endpointLabel)

        val endpointInput = EditText(this).apply {
            setText(apiEndpoint)
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://10.0.2.2:8080/v1/chat/completions"
        }
        dialogLayout.addView(endpointInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12.dp() })

        // モデル名入力
        val modelLabel = TextView(this).apply {
            text = "モデル名:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#333333"))
        }
        dialogLayout.addView(modelLabel)

        val modelInput = EditText(this).apply {
            setText(modelName)
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            hint = "gemma-4-2b-it"
        }
        dialogLayout.addView(modelInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12.dp() })

        // APIキー入力
        val keyLabel = TextView(this).apply {
            text = "APIキー（不要なら空欄）:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#333333"))
        }
        dialogLayout.addView(keyLabel)

        val keyInput = EditText(this).apply {
            setText(apiKey)
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-..."
        }
        dialogLayout.addView(keyInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("API設定")
            .setView(dialogLayout)
            .setPositiveButton("保存して接続") { _, _ ->
                apiEndpoint = endpointInput.text.toString().trim()
                modelName = modelInput.text.toString().trim()
                apiKey = keyInput.text.toString().trim()

                // 設定保存
                val prefs = getSharedPreferences("gemma_chat", MODE_PRIVATE)
                prefs.edit()
                    .putString("api_endpoint", apiEndpoint)
                    .putString("api_key", apiKey)
                    .putString("model_name", modelName)
                    .apply()

                // 会話履歴クリア
                conversationHistory.clear()
                chatContainer.removeAllViews()

                // 再接続
                checkApiConnection()
            }
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.show()
    }
}