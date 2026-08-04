package com.yep.kindle.dron.web;

import java.nio.charset.StandardCharsets;

final class LogsView {

    static final byte[] BYTES = build().getBytes(StandardCharsets.UTF_8);

    private LogsView() {}

    static String build() {
        return "<!DOCTYPE html><html lang='en'><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Logs — Kindle Drone</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:system-ui,Arial,sans-serif;background:#111827;color:#e5e7eb;" +
                "height:100vh;display:flex;flex-direction:column;padding:12px 16px;gap:8px}" +
            "h1{text-align:center;font-size:20px;font-weight:700;color:#93c5fd;letter-spacing:.5px;flex-shrink:0}" +
            ".nav{display:flex;gap:8px;justify-content:center;flex-shrink:0}" +
            ".nav a{padding:5px 14px;background:#1e3a5f;color:#93c5fd;text-decoration:none;" +
                "border-radius:20px;font-size:13px;font-weight:600}" +
            ".nav a:hover{background:#1d4ed8}" +
            ".nav a.active{background:#1d4ed8}" +
            /* file selector row */
            ".file-row{display:flex;gap:6px;flex-shrink:0;overflow-x:auto;padding:2px 0}" +
            ".file-row::-webkit-scrollbar{height:4px}" +
            ".file-row::-webkit-scrollbar-thumb{background:#334155;border-radius:2px}" +
            ".fbtn{flex-shrink:0;padding:5px 10px;border:1px solid #334155;border-radius:14px;" +
                "background:#1e293b;color:#94a3b8;font-size:11px;font-weight:600;cursor:pointer;white-space:nowrap}" +
            ".fbtn:hover{background:#334155;color:#e2e8f0}" +
            ".fbtn.active{background:#1d4ed8;border-color:#1d4ed8;color:#fff}" +
            /* toolbar */
            ".toolbar{display:flex;gap:8px;align-items:center;flex-shrink:0;flex-wrap:wrap}" +
            ".badge{font-size:11px;font-weight:700;padding:3px 8px;border-radius:12px;white-space:nowrap}" +
            ".badge-err{background:#450a0a;color:#fca5a5}" +
            ".badge-info{background:#0f2d40;color:#67e8f9}" +
            ".badge-auto{background:#064e3b;color:#6ee7b7;cursor:pointer;border:none;" +
                "font-size:11px;font-weight:700;letter-spacing:.3px}" +
            ".badge-mod{background:#1e293b;color:#94a3b8;font-size:10px}" +
            ".btn{padding:6px 12px;border:none;border-radius:6px;font-size:12px;" +
                "font-weight:700;cursor:pointer;text-transform:uppercase;letter-spacing:.5px}" +
            ".btn-sm{background:#1e293b;color:#94a3b8;border:1px solid #334155}" +
            ".btn-sm:hover{background:#334155}" +
            "a.btn-dl{background:#1e3a5f;color:#93c5fd;text-decoration:none;padding:6px 12px;" +
                "border-radius:6px;font-size:12px;font-weight:700;letter-spacing:.5px}" +
            "a.btn-dl:hover{background:#1d4ed8}" +
            ".spacer{flex:1}" +
            ".log-box{flex:1;background:#0f172a;border-radius:8px;padding:12px;font-family:monospace;" +
                "font-size:12px;line-height:1.6;overflow-y:auto;white-space:pre-wrap;word-break:break-all}" +
            ".l-err{color:#fca5a5}" +
            ".l-info{color:#94a3b8}" +
            ".l-start{color:#60a5fa}" +
            ".l-warn{color:#fde68a}" +
            ".loading{color:#475569;font-style:italic}" +
            "</style></head><body>" +
            "<h1>Kindle Drone<span id='dot' style='display:inline-block;width:7px;height:7px;" +
                "border-radius:50%;background:#4ade80;margin-left:6px;vertical-align:middle;" +
                "animation:pulse 2s infinite'></span></h1>" +
            "<style>@keyframes pulse{0%,100%{opacity:1}50%{opacity:.2}}</style>" +
            "<div class='nav'>" +
            "<a href='/'>Dashboard</a><a href='/gallery'>Gallery</a><a href='/logs' class='active'>Logs</a>" +
            "</div>" +
            "<div class='file-row' id='fileRow'>" +
            "<button class='fbtn active' id='fbtn-live' onclick='selectLive()'>&#9679; Live</button>" +
            "</div>" +
            "<div class='toolbar'>" +
            "<span class='badge badge-err' id='errBadge'>ERR: –</span>" +
            "<span class='badge badge-info' id='lineBadge'>lines: –</span>" +
            "<button class='badge badge-auto' id='autoBtn' onclick='toggleAuto()'>⏸ pause</button>" +
            "<span class='badge badge-mod' id='modBadge'></span>" +
            "<div class='spacer'></div>" +
            "<button class='btn btn-sm' onclick='doRefresh()'>Refresh</button>" +
            "<a class='btn-dl' id='dlBtn' href='/api/logs/file' download='drone.log'>Download</a>" +
            "</div>" +
            "<div class='log-box' id='logBox'><span class='loading'>Loading…</span></div>" +
            "<script>" +
            "var autoRefresh=true,timer=null,currentFile='live';" +
            "" +
            "function fmtSize(b){" +
            "  if(b<1024)return b+'b';" +
            "  if(b<1024*1024)return Math.round(b/1024)+'k';" +
            "  return (b/1024/1024).toFixed(1)+'M';" +
            "}" +
            "function fmtTime(ms){" +
            "  var d=new Date(ms);" +
            "  var now=new Date();" +
            "  var pad=function(n){return n<10?'0'+n:n;};" +
            "  if(d.toDateString()===now.toDateString())" +
            "    return pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());" +
            "  return ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][d.getMonth()]" +
            "    +' '+d.getDate()+' '+pad(d.getHours())+':'+pad(d.getMinutes());" +
            "}" +
            "function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}" +
            "function colorLine(l){" +
            "  var cls;" +
            "  if(l.indexOf(' ERR ')>=0)cls='l-err';" +
            "  else if(l.indexOf('---')>=0||l.indexOf('===')>=0)cls='l-start';" +
            "  else if(l.indexOf(' WARN ')>=0)cls='l-warn';" +
            "  else cls='l-info';" +
            "  return '<span class=\"'+cls+'\">'+esc(l)+'</span>';" +
            "}" +
            "function renderLines(lines,box){" +
            "  var atBottom=box.scrollHeight-box.scrollTop<=box.clientHeight+60;" +
            "  box.innerHTML=lines.map(colorLine).join('\\n');" +
            "  if(atBottom)box.scrollTop=box.scrollHeight;" +
            "}" +
            "" +
            "function loadLive(){" +
            "  fetch('/api/logs?n=400')" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(d){" +
            "      var box=document.getElementById('logBox');" +
            "      renderLines(d.lines,box);" +
            "      document.getElementById('errBadge').textContent='ERR: '+d.errCount;" +
            "      document.getElementById('lineBadge').textContent='lines: '+d.total;" +
            "      document.getElementById('modBadge').textContent='';" +
            "    })" +
            "    .catch(function(){document.getElementById('logBox').textContent='Connection error';});" +
            "}" +
            "" +
            "function loadFile(name){" +
            "  var box=document.getElementById('logBox');" +
            "  box.innerHTML='<span class=\\'loading\\'>Loading…</span>';" +
            "  fetch('/api/logs/view?name='+encodeURIComponent(name)+'&n=500')" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(d){" +
            "      if(d.error){box.textContent='Error: '+d.error;return;}" +
            "      renderLines(d.lines,box);" +
            "      document.getElementById('errBadge').textContent='lines: '+d.totalLines;" +
            "      document.getElementById('lineBadge').textContent=fmtSize(d.size);" +
            "      document.getElementById('modBadge').textContent=fmtTime(d.modified);" +
            "    })" +
            "    .catch(function(){box.textContent='Connection error';});" +
            "}" +
            "" +
            "function selectLive(){" +
            "  setActive('fbtn-live');" +
            "  currentFile='live';" +
            "  document.getElementById('dlBtn').href='/api/logs/file';" +
            "  document.getElementById('dlBtn').download='drone.log';" +
            "  setAuto(true);" +
            "  loadLive();" +
            "}" +
            "" +
            "function selectFile(name){" +
            "  setActive('fbtn-'+name);" +
            "  currentFile=name;" +
            "  document.getElementById('dlBtn').href='/api/logs/file?name='+encodeURIComponent(name);" +
            "  document.getElementById('dlBtn').download=name;" +
            "  setAuto(false);" +
            "  loadFile(name);" +
            "}" +
            "" +
            "function setActive(id){" +
            "  var row=document.getElementById('fileRow');" +
            "  var btns=row.getElementsByTagName('button');" +
            "  for(var i=0;i<btns.length;i++)btns[i].classList.remove('active');" +
            "  var el=document.getElementById(id);" +
            "  if(el)el.classList.add('active');" +
            "}" +
            "" +
            "function doRefresh(){" +
            "  if(currentFile==='live')loadLive(); else loadFile(currentFile);" +
            "}" +
            "" +
            "function setAuto(on){" +
            "  autoRefresh=on;" +
            "  document.getElementById('autoBtn').textContent=on?'\\u23F8 pause':'\\u25B6 resume';" +
            "  if(on)startTimer(); else{clearInterval(timer);timer=null;}" +
            "}" +
            "" +
            "function toggleAuto(){setAuto(!autoRefresh);}" +
            "" +
            "function startTimer(){" +
            "  if(timer)clearInterval(timer);" +
            "  timer=setInterval(function(){if(currentFile==='live')loadLive();},5000);" +
            "}" +
            "" +
            "function loadFileList(){" +
            "  fetch('/api/logs/list')" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(d){" +
            "      var row=document.getElementById('fileRow');" +
            "      d.files.forEach(function(f){" +
            "        var btn=document.createElement('button');" +
            "        btn.className='fbtn';" +
            "        btn.id='fbtn-'+f.name;" +
            "        btn.textContent=f.name+' '+fmtSize(f.size);" +
            "        btn.onclick=(function(n){return function(){selectFile(n);};})(f.name);" +
            "        row.appendChild(btn);" +
            "      });" +
            "    })" +
            "    .catch(function(){});" +
            "}" +
            "" +
            "loadFileList();" +
            "loadLive();" +
            "startTimer();" +
            "</script></body></html>";
    }
}
