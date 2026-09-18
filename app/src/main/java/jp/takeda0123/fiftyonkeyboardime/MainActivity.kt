package jp.takeda0123.fiftyonkeyboardime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import jp.takeda0123.fiftyonkeyboardime.theme.FiftyOnKeyboardIMETheme
import jp.takeda0123.fiftyonkeyboardime.BuildConfig
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.sp
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FiftyOnKeyboardIMETheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    val versionCode = BuildConfig.VERSION_CODE
    val versionName = BuildConfig.VERSION_NAME
    val context = LocalContext.current

    Column(modifier = modifier.padding(16.dp)) {

        Text(
            text = "Version $versionName ($versionCode)",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.ui.graphics.Color.Gray
        )

        Spacer(modifier = Modifier.height(24.dp))

        // キーボード設定リンク
        Text(
            text = "▶ キーボード設定を開く",
            modifier = Modifier.clickable {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // プライバシーポリシーリンク
        Text(
            text = "▶ プライバシーポリシー（外部サイト）",
            modifier = Modifier.clickable {
                val url = "https://sites.google.com/view/takedaapp/50on%E3%82%AD%E3%83%BC%E3%83%9C%E3%83%BC%E3%83%89%E3%82%A2%E3%83%97%E3%83%AA/chromebook%E5%90%91%E3%81%91%E3%82%A2%E3%83%97%E3%83%AA-50%E3%82%AD%E3%83%BC%E3%83%9C%E3%83%BC%E3%83%89"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- ここから説明テキスト ---

        // タイトル（見出し）
        Text(
            text = "設定手順",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 箇条書きの設定を共通化（1行目を-18sp戻し、全体の行間を少し広げる）
        val listItemStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
            lineHeight = 22.sp,
            textIndent = androidx.compose.ui.text.style.TextIndent(
                firstLine = (-18).sp,  // 1行目の記号を左に飛び出させる
                restLine = 0.sp
            )
        )

        // 箇条書きエリア（各項目の左側に余白を持たせる）
        Column(modifier = Modifier.padding(start = 18.dp)) {
            Text(text = "① 「50onキーボード」にチェックを入れます。", style = listItemStyle)
            Spacer(modifier = Modifier.height(8.dp))

            Text(text = "② 権限を確認するメッセージが表示されたら、内容を確認して「OK」をタップします（2回表示されます）。", style = listItemStyle)
            Spacer(modifier = Modifier.height(8.dp))

            // 注意書き（さらにインデントを下げて小さく表示）
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = "⚠️ メッセージ1: この入力方法を選択すると、すべての入力内容の収集をアプリに許可することになります。これにはパスワードやクレジットカード番号などの個人情報も含まれます。この入力方法を使用しますか？",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠️ メッセージ2: スマートフォンを再起動したときに画面ロックが設定されている場合は、スマートフォンのロックを解除するまでこのアプリは起動できません",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text(text = "③ もし違うキーボードが起動した場合は、キーボード上の 🌐 マークを長押し（またはタップ）して「50onキーボード」を選択してください。", style = listItemStyle)
        }
    }
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    FiftyOnKeyboardIMETheme {
        Greeting("Android")
    }
}