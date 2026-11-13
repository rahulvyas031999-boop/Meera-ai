package com.meraai.app

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

object MeeraAvatarPresets {
    // Maya जैसे दिखने और हिलने-डुलने वाले 3 तैयार Live2D कैरेक्टर
    val MODELS = listOf(
        "Hiyori" to "https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/hiyori/hiyori_pro_t10.model3.json",
        "Haru" to "https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/haru/haru_greeter_t03.model3.json",
        "Mao" to "https://cdn.jsdelivr.net/gh/Eikanya/Live2d-model/Live2D/Senko_Normals/senko.model3.json"
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MayaStyleAvatarCard(
    state: VState,
    audioLevel: Float = 0.45f,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val isSpeaking = state == VState.ASSISTANT_SPEAKING

    // जब Meera बोले या स्टेट बदले, तो कैरेक्टर के होंठ और एक्सप्रेशन अपडेट हों
    LaunchedEffect(state, audioLevel) {
        val wv = webViewRef ?: return@LaunchedEffect
        val mouth = if (isSpeaking) (audioLevel.coerceIn(0.15f, 1f)) else 0f
        val stateName = state.name
        wv.evaluateJavascript("window.updateMeeraState && window.updateMeeraState('$stateName', $mouth);", null)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    setBackgroundColor(0x00000000)
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(
                        "https://cdn.jsdelivr.net/",
                        buildLive2DHtml(MeeraAvatarPresets.MODELS[selectedIdx].second),
                        "text/html",
                        "UTF-8",
                        null
                    )
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ऊपर छोटा मॉडल स्विचर पिल ताकि आप तुरंत अलग-अलग लड़की का लुक चेक कर सकें
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MeeraAvatarPresets.MODELS.forEachIndexed { idx, (name, url) ->
                val active = selectedIdx == idx
                Surface(
                    color = if (active) Color(0xFF5B8CFF) else Color(0x9916203A),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .border(1.dp, if (active) Color(0xFF2EE6A8) else Color(0xFF26355E), RoundedCornerShape(14.dp))
                        .clickable {
                            selectedIdx = idx
                            webViewRef?.evaluateJavascript("window.loadAvatarModel && window.loadAvatarModel('$url');", null)
                        }
                ) {
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private fun buildLive2DHtml(initialModelUrl: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; user-select: none; }
  html, body { width: 100%; height: 100%; overflow: hidden; background: transparent; }
  #stage-wrap {
    position: relative; width: 100%; height: 100%;
    display: flex; align-items: center; justify-content: center;
  }
  /* Maya-style पीछे का नियॉन आभा (Glow Ring) */
  .neon-aura {
    position: absolute; width: 220px; height: 220px; border-radius: 50%;
    background: radial-gradient(circle, rgba(91,140,255,0.34) 0%, rgba(168,85,247,0.16) 55%, rgba(0,0,0,0) 75%);
    filter: blur(8px); transition: all 0.35s ease; pointer-events: none;
  }
  canvas { position: relative; z-index: 2; width: 100%; height: 100%; display: block; }
  #loader {
    position: absolute; z-index: 3; color: #8A94B8; font-family: sans-serif;
    font-size: 12px; text-align: center; pointer-events: none;
  }
</style>
<script src="https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js"></script>
<script src="https://cdn.jsdelivr.net/gh/dylanNew/live2d/webgl/Live2D/lib/live2d.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/pixi.js@6.5.8/dist/browser/pixi.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.4.0/dist/index.min.js"></script>
</head>
<body>
<div id="stage-wrap">
  <div class="neon-aura" id="aura"></div>
  <div id="loader">अवतार लोड हो रहा है...</div>
  <canvas id="canvas"></canvas>
</div>
<script>
  const aura = document.getElementById('aura');
  const loader = document.getElementById('loader');
  const app = new PIXI.Application({
    view: document.getElementById('canvas'),
    transparent: true,
    backgroundAlpha: 0,
    autoDensity: true,
    resolution: window.devicePixelRatio || 2,
    resizeTo: window
  });

  let currentModel = null;
  let speakingActive = false;
  let targetMouth = 0;
  let phase = 0;

  window.loadAvatarModel = async function(url) {
    try {
      loader.style.display = 'block';
      loader.innerText = 'अवतार तैयार हो रहा है...';
      if (currentModel) {
        app.stage.removeChild(currentModel);
        currentModel.destroy();
        currentModel = null;
      }
      const model = await PIXI.live2d.Live2DModel.from(url);
      currentModel = model;
      app.stage.addChild(model);

      const scaleX = (window.innerWidth * 0.88) / model.width;
      const scaleY = (window.innerHeight * 0.94) / model.height;
      const scale = Math.min(scaleX, scaleY) * 1.15;
      model.scale.set(scale);
      model.anchor.set(0.5, 0.52);
      model.x = window.innerWidth / 2;
      model.y = window.innerHeight * 0.56;

      // टच करने पर एक्सप्रेशन और मोशन
      model.interactive = true;
      model.on('pointerdown', () => {
        try { model.motion('TapBody'); } catch(e) {}
        try { model.expression(); } catch(e) {}
      });

      loader.style.display = 'none';
    } catch (e) {
      loader.innerText = 'मॉडल लोड में देरी: नेटवर्क चेक करें';
      console.error(e);
    }
  };

  window.updateMeeraState = function(state, mouthLevel) {
    speakingActive = (state === 'ASSISTANT_SPEAKING');
    targetMouth = mouthLevel || 0;
    if (state === 'ASSISTANT_SPEAKING') {
      aura.style.transform = 'scale(1.22)';
      aura.style.background = 'radial-gradient(circle, rgba(46,230,168,0.42) 0%, rgba(91,140,255,0.22) 60%, transparent 78%)';
    } else if (state === 'USER_SPEAKING' || state === 'LISTENING') {
      aura.style.transform = 'scale(1.12)';
      aura.style.background = 'radial-gradient(circle, rgba(91,140,255,0.40) 0%, rgba(168,85,247,0.20) 60%, transparent 78%)';
    } else {
      aura.style.transform = 'scale(1.0)';
    }
  };

  // हर फ्रेम पर स्मूथ Lip-Sync और सांस लेने का इफ़ेक्ट
  app.ticker.add((delta) => {
    if (!currentModel || !currentModel.internalModel) return;
    phase += 0.22 * delta;
    const core = currentModel.internalModel.coreModel;
    if (!core) return;
    let openVal = 0;
    if (speakingActive) {
      const wave = (Math.sin(phase * 1.7) + 1) * 0.35 + 0.25;
      openVal = Math.min(1.0, Math.max(0.15, targetMouth * wave * 1.4));
    }
    try {
      if (typeof core.setParameterValueById === 'function') {
        core.setParameterValueById('ParamMouthOpenY', openVal);
      } else if (typeof core.setParamFloat === 'function') {
        core.setParamFloat('PARAM_MOUTH_OPEN_Y', openVal);
      }
    } catch(e) {}
  });

  window.loadAvatarModel('$initialModelUrl');
</script>
</body>
</html>
""".trimIndent()
