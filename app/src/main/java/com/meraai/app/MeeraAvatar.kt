package com.meraai.app

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    // 100% Verified Cubism 2 Models (जिनके लिए किसी ब्लॉक होने वाली एक्सटर्नल स्क्रिप्ट की ज़रूरत नहीं है)
    val MODELS = listOf(
        "Maya 2D" to "BUILTIN_MAYA",
        "Shizuku" to "https://cdn.jsdelivr.net/gh/guansss/pixi-live2d-display/test/assets/shizuku/shizuku.model.json",
        "Haru" to "https://cdn.jsdelivr.net/gh/evrstr/live2d-widget-models/live2d_evrstr/haru_02/model.json",
        "Koharu" to "https://cdn.jsdelivr.net/gh/evrstr/live2d-widget-models/live2d_evrstr/koharu/model.json"
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MayaStyleAvatarCard(
    state: VState,
    audioLevel: Float = 0.45f,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val isSpeaking = state == VState.ASSISTANT_SPEAKING

    LaunchedEffect(state, audioLevel) {
        val wv = webViewRef ?: return@LaunchedEffect
        val mouth = if (isSpeaking) audioLevel.coerceIn(0.25f, 1f) else 0f
        val stateName = state.name
        wv.evaluateJavascript("window.updateMeeraState && window.updateMeeraState('$stateName', $mouth);", null)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(AColor.TRANSPARENT)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                            if (cm != null) {
                                MeeraDebugLog.log("AVATAR_WV", "${cm.messageLevel()}: ${cm.message()} (line ${cm.lineNumber()})")
                            }
                            return true
                        }
                    }
                    loadDataWithBaseURL(
                        "https://cdn.jsdelivr.net/",
                        buildAvatarStageHtml(),
                        "text/html",
                        "UTF-8",
                        null
                    )
                    webViewRef = this
                }
            },
            update = { wv ->
                webViewRef = wv
            },
            modifier = Modifier.fillMaxSize()
        )

        // ऊपर मॉडल स्विचर पिल (Maya 2D, Shizuku, Haru, Koharu)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            MeeraAvatarPresets.MODELS.forEachIndexed { idx, (name, url) ->
                val active = selectedIdx == idx
                Surface(
                    color = if (active) Color(0xFF5B8CFF) else Color(0xCC16203A),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .border(1.dp, if (active) Color(0xFF2EE6A8) else Color(0xFF26355E), RoundedCornerShape(14.dp))
                        .clickable {
                            selectedIdx = idx
                            webViewRef?.evaluateJavascript("window.switchCharacter && window.switchCharacter('$url');", null)
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

private fun buildAvatarStageHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; user-select: none; }
  html, body {
    width: 100vw; height: 100vh; overflow: hidden;
    background: transparent !important;
    font-family: sans-serif;
  }
  #stage {
    position: relative; width: 100vw; height: 100vh;
    display: flex; align-items: center; justify-content: center;
  }
  /* पीछे की साइबरपंक नियॉन आभा */
  #aura {
    position: absolute; width: 210px; height: 210px; border-radius: 50%;
    background: radial-gradient(circle, rgba(91,140,255,0.42) 0%, rgba(168,85,247,0.22) 55%, rgba(0,0,0,0) 75%);
    box-shadow: 0 0 38px rgba(46,230,168,0.28);
    transition: all 0.3s ease; pointer-events: none; z-index: 1;
  }
  /* इनबिल्ट एनीमे गर्ल रिग (बिना इंटरनेट के भी तुरंत दिखेगी!) */
  #builtin-wrap {
    position: relative; z-index: 2; width: 230px; height: 255px;
    display: flex; align-items: center; justify-content: center;
    animation: floatBody 3.2s ease-in-out infinite;
  }
  @keyframes floatBody {
    0%, 100% { transform: translateY(0px) scale(1); }
    50% { transform: translateY(-6px) scale(1.015); }
  }
  #live2d-canvas {
    position: absolute; top: 0; left: 0; width: 100vw; height: 100vh;
    z-index: 3; display: none; pointer-events: auto;
  }
  #badge {
    position: absolute; bottom: 4px; left: 50%; transform: translateX(-50%);
    z-index: 4; color: #2EE6A8; font-size: 10px; background: rgba(18,25,46,0.75);
    padding: 2px 8px; border-radius: 10px; border: 1px solid #26355E;
    display: none;
  }
</style>
</head>
<body>
<div id="stage">
  <div id="aura"></div>

  <!-- Layer 1: 100% Instant Built-in Anime Companion (Maya Hoodie Style) -->
  <div id="builtin-wrap" onclick="triggerTouchReaction()">
    <svg viewBox="0 0 240 270" width="230" height="255">
      <defs>
        <linearGradient id="hairGrad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#2B3566"/>
          <stop offset="100%" stop-color="#141A38"/>
        </linearGradient>
        <linearGradient id="hoodieGrad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="#5B8CFF"/>
          <stop offset="100%" stop-color="#38BDF8"/>
        </linearGradient>
        <linearGradient id="skinGrad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#FFE6D5"/>
          <stop offset="100%" stop-color="#F7CBB2"/>
        </linearGradient>
      </defs>

      <!-- पीछे के लंबे बाल -->
      <path d="M62,70 Q42,155 54,238 Q120,250 186,238 Q198,155 178,70 Z" fill="url(#hairGrad)"/>

      <!-- गर्दन और कंधे (Maya Blue Hoodie) -->
      <rect x="106" y="142" width="28" height="26" rx="10" fill="url(#skinGrad)"/>
      <path d="M52,262 Q58,172 95,162 L145,162 Q182,172 188,262 Z" fill="url(#hoodieGrad)"/>
      <!-- सफेद इनर टी-शर्ट और हुडी स्ट्रिंग्स -->
      <polygon points="102,162 138,162 120,196" fill="#FFFFFF"/>
      <path d="M88,162 Q120,192 152,162" fill="none" stroke="#2EE6A8" stroke-width="3.5"/>
      <line x1="108" y1="176" x2="106" y2="215" stroke="#FFFFFF" stroke-width="3" stroke-linecap="round"/>
      <line x1="132" y1="176" x2="134" y2="215" stroke="#FFFFFF" stroke-width="3" stroke-linecap="round"/>

      <!-- चेहरा -->
      <path d="M74,78 Q74,152 120,156 Q166,152 166,78 Q166,42 120,42 Q74,42 74,78 Z" fill="url(#skinGrad)"/>

      <!-- गालों का हल्का ब्लश (Blush) -->
      <ellipse id="blushL" cx="89" cy="118" rx="9" ry="4.5" fill="#FF7597" opacity="0.45"/>
      <ellipse id="blushR" cx="151" cy="118" rx="9" ry="4.5" fill="#FF7597" opacity="0.45"/>

      <!-- आँखें (Blinking सपोर्ट के साथ) -->
      <g id="eyes-open">
        <ellipse cx="96" cy="102" rx="10.5" ry="12" fill="#1E293B"/>
        <ellipse cx="96" cy="103" rx="7.5" ry="9" fill="#38BDF8"/>
        <circle cx="96" cy="104" r="4" fill="#0F172A"/>
        <circle cx="92" cy="98" r="3" fill="#FFFFFF"/>

        <ellipse cx="144" cy="102" rx="10.5" ry="12" fill="#1E293B"/>
        <ellipse cx="144" cy="103" rx="7.5" ry="9" fill="#38BDF8"/>
        <circle cx="144" cy="104" r="4" fill="#0F172A"/>
        <circle cx="140" cy="98" r="3" fill="#FFFFFF"/>
      </g>
      <g id="eyes-closed" style="display:none;">
        <path d="M85,104 Q96,112 107,104" fill="none" stroke="#1E293B" stroke-width="3.2" stroke-linecap="round"/>
        <path d="M133,104 Q144,112 155,104" fill="none" stroke="#1E293B" stroke-width="3.2" stroke-linecap="round"/>
      </g>

      <!-- आइब्रो -->
      <path id="browL" d="M84,85 Q96,80 106,84" fill="none" stroke="#1E293B" stroke-width="2.6" stroke-linecap="round"/>
      <path id="browR" d="M134,84 Q144,80 156,85" fill="none" stroke="#1E293B" stroke-width="2.6" stroke-linecap="round"/>

      <!-- नाक -->
      <path d="M120,111 L118,118 L122,118" fill="none" stroke="#D99B82" stroke-width="1.8" stroke-linecap="round"/>

      <!-- होंठ (Lip-Sync के साथ खुलने-बंद होने वाला मुँह) -->
      <ellipse id="mouth-open" cx="120" cy="134" rx="8" ry="1.5" fill="#E11D48" stroke="#881337" stroke-width="1.5"/>

      <!-- आगे के बाल (Anime Bangs) -->
      <path d="M68,84 Q72,34 120,32 Q168,34 172,84 Q156,56 142,82 Q132,54 120,80 Q108,54 98,82 Q84,56 68,84 Z" fill="url(#hairGrad)"/>
      <!-- हेडफोन / साइबर क्लिप -->
      <circle cx="70" cy="94" r="7" fill="#2EE6A8"/>
      <circle cx="170" cy="94" r="7" fill="#2EE6A8"/>
    </svg>
  </div>

  <!-- Layer 2: Full WebGL Live2D Canvas -->
  <canvas id="live2d-canvas"></canvas>
  <div id="badge">लोड हो रहा है...</div>
</div>

<!-- Verified Non-Blocked CDN Scripts for Live2D Cubism 2 -->
<script src="https://cdn.jsdelivr.net/gh/dylanNew/live2d/webgl/Live2D/lib/live2d.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/pixi.js@6.5.8/dist/browser/pixi.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.4.0/dist/cubism2.min.js"></script>

<script>
  const aura = document.getElementById('aura');
  const builtinWrap = document.getElementById('builtin-wrap');
  const liveCanvas = document.getElementById('live2d-canvas');
  const badge = document.getElementById('badge');
  const mouthEl = document.getElementById('mouth-open');
  const eyesOpen = document.getElementById('eyes-open');
  const eyesClosed = document.getElementById('eyes-closed');

  let speaking = false;
  let mouthTarget = 0;
  let phase = 0;
  let pixiApp = null;
  let currentLiveModel = null;

  // पलकें झपकाना (Natural Blinking every 3.4s)
  setInterval(() => {
    eyesOpen.style.display = 'none';
    eyesClosed.style.display = 'block';
    setTimeout(() => {
      eyesOpen.style.display = 'block';
      eyesClosed.style.display = 'none';
    }, 150);
  }, 3400);

  function triggerTouchReaction() {
    aura.style.transform = 'scale(1.28)';
    setTimeout(() => { aura.style.transform = 'scale(1.0)'; }, 400);
  }

  // हर 30ms में Lip-Sync एनिमेशन (SVG + Live2D दोनों के लिए)
  setInterval(() => {
    phase += 0.45;
    let openVal = 0;
    if (speaking) {
      const wave = (Math.sin(phase) + 1) * 0.42 + 0.2;
      openVal = Math.min(1.0, Math.max(0.2, mouthTarget * wave * 1.45));
    }
    // 1. Built-in Maya 2D के होंठ हिलाएँ
    const ry = 1.4 + openVal * 9.5;
    const rx = 7.5 + openVal * 3.0;
    mouthEl.setAttribute('ry', ry.toFixed(1));
    mouthEl.setAttribute('rx', rx.toFixed(1));

    // 2. अगर Live2D मॉडल चालू है तो उसके होंठ हिलाएँ
    if (currentLiveModel && currentLiveModel.internalModel && currentLiveModel.internalModel.coreModel) {
      const core = currentLiveModel.internalModel.coreModel;
      try {
        if (typeof core.setParamFloat === 'function') {
          core.setParamFloat('PARAM_MOUTH_OPEN_Y', openVal);
        } else if (typeof core.setParameterValueById === 'function') {
          core.setParameterValueById('ParamMouthOpenY', openVal);
        }
      } catch (e) {}
    }
  }, 35);

  window.updateMeeraState = function(state, level) {
    speaking = (state === 'ASSISTANT_SPEAKING');
    mouthTarget = level || 0;
    if (state === 'ASSISTANT_SPEAKING') {
      aura.style.transform = 'scale(1.24)';
      aura.style.background = 'radial-gradient(circle, rgba(46,230,168,0.50) 0%, rgba(91,140,255,0.25) 60%, transparent 78%)';
    } else if (state === 'USER_SPEAKING' || state === 'LISTENING') {
      aura.style.transform = 'scale(1.14)';
      aura.style.background = 'radial-gradient(circle, rgba(91,140,255,0.48) 0%, rgba(168,85,247,0.24) 60%, transparent 78%)';
    } else {
      aura.style.transform = 'scale(1.0)';
    }
  };

  window.switchCharacter = async function(url) {
    if (url === 'BUILTIN_MAYA') {
      liveCanvas.style.display = 'none';
      builtinWrap.style.display = 'flex';
      badge.style.display = 'none';
      return;
    }
    try {
      badge.style.display = 'block';
      badge.innerText = 'Live2D मॉडल लोड हो रहा है...';
      if (!pixiApp && window.PIXI && PIXI.live2d) {
        pixiApp = new PIXI.Application({
          view: liveCanvas,
          transparent: true,
          backgroundAlpha: 0,
          autoDensity: true,
          resolution: window.devicePixelRatio || 2,
          resizeTo: window
        });
      }
      if (currentLiveModel && pixiApp) {
        pixiApp.stage.removeChild(currentLiveModel);
        currentLiveModel.destroy();
        currentLiveModel = null;
      }
      console.log('Loading Live2D URL: ' + url);
      const model = await PIXI.live2d.Live2DModel.from(url);
      currentLiveModel = model;
      pixiApp.stage.addChild(model);

      const scaleX = (window.innerWidth * 0.85) / model.width;
      const scaleY = (window.innerHeight * 0.92) / model.height;
      const scale = Math.min(scaleX, scaleY);
      model.scale.set(scale);
      model.anchor.set(0.5, 0.5);
      model.x = window.innerWidth / 2;
      model.y = window.innerHeight * 0.54;

      builtinWrap.style.display = 'none';
      liveCanvas.style.display = 'block';
      badge.style.display = 'none';
      console.log('Live2D loaded successfully!');
    } catch (err) {
      console.error('Live2D load failed: ' + err);
      badge.innerText = 'Maya 2D एक्टिव है';
      setTimeout(() => { badge.style.display = 'none'; }, 2000);
      liveCanvas.style.display = 'none';
      builtinWrap.style.display = 'flex';
    }
  };

  console.log('Meera Avatar Stage Ready');
</script>
</body>
</html>
""".trimIndent()
