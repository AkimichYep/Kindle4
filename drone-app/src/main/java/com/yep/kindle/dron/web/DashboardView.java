package com.yep.kindle.dron.web;

import java.nio.charset.StandardCharsets;

final class DashboardView {

    static final byte[] BYTES = build().getBytes(StandardCharsets.UTF_8);

    private DashboardView() {}

    static String build() {
        return "<!DOCTYPE html><html lang='en'><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Kindle Drone</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:system-ui,Arial,sans-serif;background:#111827;color:#e5e7eb;min-height:100vh;padding:16px}" +
            ".wrap{max-width:480px;margin:0 auto}" +
            "h1{text-align:center;font-size:20px;font-weight:700;margin-bottom:14px;color:#93c5fd;letter-spacing:.5px}" +
            ".nav{display:flex;gap:8px;justify-content:center;margin-bottom:18px}" +
            ".nav a{padding:5px 14px;background:#1e3a5f;color:#93c5fd;text-decoration:none;border-radius:20px;font-size:13px;font-weight:600}" +
            ".nav a:hover{background:#1d4ed8}" +
            ".batt-card{background:#1e293b;border-radius:10px;padding:16px;margin-bottom:12px}" +
            ".batt-card.warn{background:#450a0a}" +
            ".batt-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}" +
            ".batt-pct{font-size:36px;font-weight:800;line-height:1}" +
            ".charge-badge{font-size:12px;font-weight:600;color:#34d399;padding:3px 8px;background:#064e3b;border-radius:12px}" +
            ".batt-bar{height:8px;background:rgba(255,255,255,.1);border-radius:4px;overflow:hidden;margin-bottom:8px}" +
            ".batt-fill{height:100%;border-radius:4px;transition:width .4s,background .4s}" +
            ".batt-sub{font-size:12px;color:#94a3b8}" +
            ".grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:12px}" +
            ".card{background:#1e293b;border-radius:10px;padding:14px;text-align:center}" +
            ".card-label{font-size:10px;text-transform:uppercase;letter-spacing:1.2px;color:#64748b;margin-bottom:6px}" +
            ".card-val{font-size:28px;font-weight:700;color:#e2e8f0}" +
            ".card-val.sm{font-size:20px}" +
            ".sec-label{font-size:10px;text-transform:uppercase;letter-spacing:1px;color:#64748b;margin-bottom:5px}" +
            ".info-box{background:#0f172a;border-radius:8px;padding:12px;font-size:13px;line-height:1.5;color:#94a3b8;margin-bottom:12px;min-height:36px;word-break:break-word}" +
            ".info-box.msg{color:#5eead4}" +
            ".input-row{display:flex;gap:8px;margin-bottom:12px}" +
            "input[type=text]{flex:1;padding:10px 12px;border:1px solid #1e293b;border-radius:8px;background:#0f172a;color:#e5e7eb;font-size:14px;outline:none}" +
            "input[type=text]:focus{border-color:#3b82f6}" +
            "input[type=text].err{border-color:#ef4444}" +
            ".btn{padding:10px 16px;border:none;border-radius:8px;font-size:13px;font-weight:700;cursor:pointer;letter-spacing:.5px;text-transform:uppercase}" +
            ".btn-send{background:#1d4ed8;color:#fff}" +
            ".btn-send:active{background:#1e40af}" +
            ".actions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:12px}" +
            ".btn-page{background:#065f46;color:#ecfdf5}" +
            ".btn-page:active{background:#064e3b}" +
            ".btn-refresh{background:#1e293b;color:#94a3b8;border:1px solid #334155}" +
            ".btn-refresh:active{background:#0f172a}" +
            ".divider{border:none;border-top:1px solid #1e293b;margin:14px 0}" +
            ".panel-wrap{display:flex;gap:12px;align-items:flex-start}" +
            ".btn-col{flex:1;display:flex;flex-direction:column;gap:10px}" +
            ".preview-col{flex:0 0 120px;display:none}" +
            ".preview-col img{width:120px;height:160px;object-fit:cover;border-radius:8px;border:1px solid #334155;display:block;cursor:pointer}" +
            ".preview-label{font-size:10px;text-transform:uppercase;color:#64748b;margin-top:4px;text-align:center;letter-spacing:.5px}" +
            ".wx-btn-col{position:relative}" +
            // Tooltip anchored to button column
            ".wx-tip{visibility:hidden;opacity:0;background:#1e293b;color:#94a3b8;border:1px solid #334155;" +
                "padding:10px 12px;border-radius:8px;position:absolute;z-index:10;top:100%;left:0;right:0;" +
                "font-size:11px;line-height:1.7;white-space:pre-line;pointer-events:none;transition:opacity .15s;margin-top:4px}" +
            ".wx-btn-col:hover .wx-tip{visibility:visible;opacity:1}" +
            ".btn-wx{background:#0e7490;color:#ecfeff;width:100%;text-align:center}" +
            ".btn-wx:hover{background:#0891b2}" +
            ".btn-ht{background:#7c3aed;color:#ede9fe;width:100%;text-align:center}" +
            ".btn-ht:hover{background:#6d28d9}" +
            ".btn-moon{background:#1e3a8a;color:#bfdbfe;width:100%;text-align:center}" +
            ".btn-moon:hover{background:#1e40af}" +
            ".btn-sw{background:#7f1d1d;color:#fecaca;width:100%;text-align:center}" +
            ".btn-sw:hover{background:#991b1b}" +
            ".btn-radar{background:#166534;color:#bbf7d0;width:100%;text-align:center}" +
            ".btn-radar:hover{background:#15803d}" +
            ".btn-wx:disabled,.btn-ht:disabled,.btn-moon:disabled,.btn-sw:disabled,.btn-radar:disabled{background:#1e293b;color:#4b5563;cursor:default}" +
            ".toast{text-align:center;padding:8px 12px;border-radius:8px;font-size:12px;margin-top:4px;display:none}" +
            ".toast.ok{background:#064e3b;color:#6ee7b7;display:block}" +
            ".toast.err{background:#450a0a;color:#fca5a5;display:block}" +
            ".dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#4ade80;margin-left:6px;vertical-align:middle;animation:pulse 2s infinite}" +
            "@keyframes pulse{0%,100%{opacity:1}50%{opacity:.2}}" +
            "</style></head><body><div class='wrap'>" +
            "<h1>Kindle Drone<span class='dot'></span></h1>" +
            "<div class='nav'><a href='/'>Dashboard</a><a href='/gallery'>Gallery</a></div>" +

            "<div class='batt-card' id='bcard'>" +
            "<div class='batt-header'>" +
            "<div><div style='font-size:11px;text-transform:uppercase;letter-spacing:1px;color:#64748b;margin-bottom:4px'>Battery</div>" +
            "<div class='batt-pct' id='bpct'>--</div></div>" +
            "<span class='charge-badge' id='cbadge' style='display:none'>Charging</span>" +
            "</div>" +
            "<div class='batt-bar'><div class='batt-fill' id='bfill' style='width:0'></div></div>" +
            "<div class='batt-sub'>Cell temp: <span id='btval'>--</span></div>" +
            "</div>" +

            "<div class='grid'>" +
            "<div class='card'><div class='card-label'>Room Temp</div><div class='card-val' id='tval'>--</div></div>" +
            "<div class='card'><div class='card-label'>Charging</div><div class='card-val sm' id='cval'>--</div></div>" +
            "</div>" +

            "<div class='sec-label'>Detector status</div>" +
            "<div class='info-box' id='det'>Loading…</div>" +

            "<div class='sec-label'>Last message on Kindle</div>" +
            "<div class='info-box msg' id='mdsp'>—</div>" +

            "<div class='input-row'>" +
            "<input type='text' id='msgIn' placeholder='Message to Kindle screen…' maxlength='200'/>" +
            "<button class='btn btn-send' onclick='sendMsg()'>Send</button>" +
            "</div>" +

            "<div class='actions'>" +
            "<button class='btn btn-page' onclick='nextPage()'>Next Page</button>" +
            "<button class='btn btn-refresh' onclick='refresh()'>Refresh</button>" +
            "</div>" +

            "<hr class='divider'>" +

            "<div class='sec-label'>City</div>" +
            "<div class='input-row'>" +
            "<input type='text' id='cityIn' placeholder='e.g. London, Kyiv…' maxlength='100'/>" +
            "<button class='btn btn-send' onclick='saveCity()'>Save</button>" +
            "</div>" +

            "<div class='panel-wrap'>" +
            "<div class='btn-col'>" +
            "<div class='wx-btn-col'>" +
            "<div class='wx-tip'>" +
            "Weather data: open-meteo.com (no API key)\n" +
            "Geocoding: geocoding-api.open-meteo.com\n" +
            "Image: /mnt/us/drone-app/img/weather.png\n" +
            "Shown on Kindle via eips command" +
            "</div>" +
            "<button class='btn btn-wx' id='wxBtn' onclick='fetchWeather()'>Weather in <span id='wxCity'>…</span></button>" +
            "</div>" +
            "<div class='wx-btn-col'>" +
            "<div class='wx-tip'>" +
            "Sensor: /sys/bus/i2c/devices/1-0048/papyrus_temperature\n" +
            "History: all-time daily aggregates (min/max/avg)\n" +
            "CSV: /mnt/us/drone-app/data/hometemp.csv\n" +
            "Image: /mnt/us/drone-app/img/hometemp.png\n" +
            "Shown on Kindle via eips command" +
            "</div>" +
            "<button class='btn btn-ht' id='htBtn' onclick='fetchHomeTemp()'>Home Temp</button>" +
            "</div>" +
            "<div class='wx-btn-col'>" +
            "<div class='wx-tip'>" +
            "Moon phase calculated from lunar cycle\n" +
            "28-day calendar with phase icons\n" +
            "Image: /mnt/us/drone-app/img/moon.png\n" +
            "Shown on Kindle via eips command" +
            "</div>" +
            "<button class='btn btn-moon' id='moonBtn' onclick='fetchMoon()'>Moon Calendar</button>" +
            "</div>" +
            "<div class='wx-btn-col'>" +
            "<div class='wx-tip'>" +
            "Space weather from NOAA SWPC\n" +
            "Solar wind, Kp index, X-ray flux\n" +
            "Image: /mnt/us/drone-app/img/spaceweather.png\n" +
            "Shown on Kindle via eips command" +
            "</div>" +
            "<button class='btn btn-sw' id='swBtn' onclick='fetchSpaceWeather()'>Space Weather</button>" +
            "</div>" +
            "<div class='wx-btn-col'>" +
            "<div class='wx-tip'>" +
            "Current WiFi radar frame (live detection)\n" +
            "Radar PNG updated by the drone detector loop\n" +
            "Image: /mnt/us/drone-app/img/radar.png\n" +
            "Shown on Kindle via eips command" +
            "</div>" +
            "<button class='btn btn-radar' id='radarBtn' onclick='fetchRadar()'>Radar</button>" +
            "</div>" +
            "</div>" +
            "<div class='preview-col' id='previewCol'>" +
            "<img id='previewImg' src='' alt='Preview' onclick='openFullPreview()'/>" +
            "<div class='preview-label' id='previewLabel'></div>" +
            "</div>" +
            "</div>" +

            "<div class='toast' id='toast'></div>" +
            "</div>" +

            "<script>" +
            "document.addEventListener('DOMContentLoaded',function(){" +
            "  loadStatus();" +
            "  loadConfig();" +
            "  document.getElementById('msgIn').addEventListener('keydown',function(e){if(e.key==='Enter')sendMsg();});" +
            "  document.getElementById('cityIn').addEventListener('keydown',function(e){if(e.key==='Enter')saveCity();});" +
            "  document.getElementById('cityIn').addEventListener('input',function(){this.classList.remove('err');});" +
            "});" +
            "function loadStatus(){" +
            "  fetch('/api/status')" +
            "    .then(function(r){return r.json();})" +
            "    .then(update)" +
            "    .catch(function(){document.getElementById('det').textContent='Offline — tap Refresh';});" +
            "}" +
            "function loadConfig(){" +
            "  fetch('/api/config')" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(d){" +
            "      var city=d.city||'Kharkiv';" +
            "      document.getElementById('cityIn').value=city;" +
            "      document.getElementById('wxCity').textContent=city;" +
            "    })" +
            "    .catch(function(){});" +
            "}" +
            "function update(d){" +
            "  var b=d.battery;" +
            "  document.getElementById('bpct').textContent=b>=0?b+'%':'--';" +
            "  var f=document.getElementById('bfill');" +
            "  f.style.width=(b>=0?b:0)+'%';" +
            "  f.style.background=b>30?'#4ade80':b>15?'#facc15':'#f87171';" +
            "  document.getElementById('bcard').className='batt-card'+(b>=0&&b<=15?' warn':'');" +
            "  var chg=d.charging;" +
            "  var cb=document.getElementById('cbadge');" +
            "  cb.style.display=chg===1?'':'none';" +
            "  document.getElementById('cval').textContent=chg===1?'Yes':chg===0?'No':'—';" +
            "  var bt=d.batteryTemp;" +
            "  document.getElementById('btval').textContent=bt>=-0?bt.toFixed(1)+'°C':'--';" +
            "  var t=d.temperature;" +
            "  document.getElementById('tval').textContent=t>=0?t+'°C':'--';" +
            "  document.getElementById('det').textContent=d.status||'—';" +
            "  var m=d.userMessage;" +
            "  document.getElementById('mdsp').textContent=m&&m.length?m:'—';" +
            "}" +
            "function refresh(){loadStatus();}" +
            "function sendMsg(){" +
            "  var t=document.getElementById('msgIn').value.trim();" +
            "  if(!t){toast('Enter a message','err');return;}" +
            "  fetch('/api/message',{method:'POST',headers:{'Content-Type':'text/plain'},body:t})" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(){" +
            "      toast('Sent!','ok');" +
            "      document.getElementById('msgIn').value='';" +
            "      document.getElementById('mdsp').textContent=t;" +
            "    }).catch(function(){toast('Error','err');});" +
            "}" +
            "function nextPage(){" +
            "  fetch('/api/next-page',{method:'POST'})" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(){toast('Next page!','ok');})" +
            "    .catch(function(){toast('Error','err');});" +
            "}" +
            "function validateCityInput(city){" +
            "  if(!city||city.length<2)return'Too short (min 2 characters)';" +
            // Count Unicode letters with a basic range covering Latin + extended + Cyrillic
            "  var letters=city.replace(/[^a-zA-Z\\u00C0-\\u024F\\u0400-\\u04FF]/g,'').length;" +
            "  if(letters<2)return'Must contain at least 2 letters';" +
            "  return null;" +
            "}" +
            "function saveCity(){" +
            "  var inp=document.getElementById('cityIn');" +
            "  var city=inp.value.trim().split(/[,;]/)[0].trim();" +
            "  var err=validateCityInput(city);" +
            "  if(err){inp.classList.add('err');toast(err,'err');return;}" +
            "  inp.classList.remove('err');" +
            "  fetch('/api/config',{method:'POST',headers:{'Content-Type':'text/plain'},body:city})" +
            "    .then(function(r){" +
            "      if(!r.ok)return r.json().then(function(d){throw new Error(d.error||r.status);});" +
            "      return r.json();" +
            "    })" +
            "    .then(function(d){" +
            "      inp.value=d.city;" +
            "      document.getElementById('wxCity').textContent=d.city;" +
            "      toast('Saved!','ok');" +
            "    })" +
            "    .catch(function(e){inp.classList.add('err');toast(e.message||'Error','err');});" +
            "}" +
            "function showPreview(url,label){" +
            "  var img=document.getElementById('previewImg');" +
            "  img.src=url+'?t='+new Date().getTime();" +
            "  document.getElementById('previewLabel').textContent=label;" +
            "  document.getElementById('previewCol').style.display='block';" +
            "}" +
            "function openFullPreview(){" +
            "  var src=document.getElementById('previewImg').src;" +
            "  if(src)window.open(src,'_blank');" +
            "}" +
            "function fetchView(btnId,endpoint,waitMsg,label){" +
            "  var btn=document.getElementById(btnId);btn.disabled=true;" +
            "  toast(waitMsg,'ok');" +
            "  fetch(endpoint,{method:'POST'})" +
            "    .then(function(r){if(!r.ok)return r.json().then(function(d){throw new Error(d.error||('HTTP '+r.status));});return r.json();})" +
            "    .then(function(d){btn.disabled=false;showPreview(d.imageUrl,label);toast(label+' updated on Kindle!','ok');})" +
            "    .catch(function(e){btn.disabled=false;toast(e.message||'Connection error','err');});" +
            "}" +
            "function fetchWeather(){" +
            "  var city=document.getElementById('cityIn').value.trim().split(/[,;]/)[0].trim();" +
            "  var err=validateCityInput(city);" +
            "  if(err){document.getElementById('cityIn').classList.add('err');toast(err,'err');return;}" +
            "  fetchView('wxBtn','/api/weather-refresh','Fetching weather…','Weather');" +
            "}" +
            "function fetchHomeTemp(){fetchView('htBtn','/api/hometemp-refresh','Reading temperature…','Home Temp');}" +
            "function fetchMoon(){fetchView('moonBtn','/api/moon-refresh','Generating moon calendar…','Moon Calendar');}" +
            "function fetchSpaceWeather(){fetchView('swBtn','/api/spaceweather-refresh','Fetching space weather…','Space Weather');}" +
            "function fetchRadar(){fetchView('radarBtn','/api/radar-refresh','Loading radar…','Radar');}" +
            "function toast(msg,cls){" +
            "  var el=document.getElementById('toast');" +
            "  el.textContent=msg;el.className='toast '+cls;" +
            "  setTimeout(function(){el.className='toast';},4000);" +
            "}" +
            "</script></body></html>";
    }
}
