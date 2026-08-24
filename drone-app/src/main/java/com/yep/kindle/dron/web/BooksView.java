package com.yep.kindle.dron.web;

import java.nio.charset.StandardCharsets;

final class BooksView {

    static final byte[] BYTES = build().getBytes(StandardCharsets.UTF_8);

    private BooksView() {}

    static String build() {
        return "<!DOCTYPE html><html lang='en'><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Books — Kindle Drone</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:system-ui,Arial,sans-serif;background:#111827;color:#e5e7eb;min-height:100vh;padding:16px}" +
            ".wrap{max-width:800px;margin:0 auto}" +
            "h1{text-align:center;font-size:20px;font-weight:700;margin-bottom:14px;color:#93c5fd;letter-spacing:.5px}" +
            ".nav{display:flex;gap:8px;justify-content:center;margin-bottom:18px}" +
            ".nav a{padding:5px 14px;background:#1e3a5f;color:#93c5fd;text-decoration:none;border-radius:20px;font-size:13px;font-weight:600}" +
            ".nav a:hover{background:#1d4ed8}" +
            ".nav a.active{background:#1d4ed8;color:#fff}" +

            // Storage card
            ".storage-card{background:#1e293b;border-radius:10px;padding:16px;margin-bottom:16px}" +
            ".storage-hdr{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}" +
            ".storage-title{font-size:12px;text-transform:uppercase;letter-spacing:1px;color:#64748b}" +
            ".storage-val{font-size:14px;font-weight:700;color:#38bdf8}" +
            ".storage-bar{height:8px;background:rgba(255,255,255,.1);border-radius:4px;overflow:hidden;margin-bottom:6px}" +
            ".storage-fill{height:100%;background:#38bdf8;border-radius:4px;transition:width .4s}" +
            ".storage-sub{font-size:11px;color:#94a3b8;display:flex;justify-content:space-between}" +

            // Upload dropzone
            ".dropzone{background:#0f172a;border:2px dashed #334155;border-radius:10px;padding:24px;text-align:center;margin-bottom:16px;cursor:pointer;transition:border-color .2s,background .2s}" +
            ".dropzone:hover,.dropzone.dragover{border-color:#3b82f6;background:#1e293b}" +
            ".drop-icon{font-size:28px;margin-bottom:8px;color:#93c5fd}" +
            ".drop-text{font-size:14px;font-weight:600;color:#e2e8f0;margin-bottom:4px}" +
            ".drop-sub{font-size:11px;color:#64748b}" +

            // Progress bar
            ".progress-wrap{display:none;margin-bottom:16px}" +
            ".progress-bar{height:6px;background:#1e293b;border-radius:3px;overflow:hidden;margin-bottom:4px}" +
            ".progress-fill{height:100%;background:#10b981;width:0%;transition:width .2s}" +
            ".progress-info{font-size:11px;color:#94a3b8;display:flex;justify-content:space-between}" +

            // Toolbar
            ".toolbar{display:flex;gap:10px;align-items:center;margin-bottom:12px;flex-wrap:wrap}" +
            ".search-in{flex:1;min-width:180px;padding:8px 12px;border:1px solid #1e293b;border-radius:8px;background:#0f172a;color:#e5e7eb;font-size:13px;outline:none}" +
            ".search-in:focus{border-color:#3b82f6}" +
            ".btn{padding:8px 14px;border:none;border-radius:8px;font-size:12px;font-weight:700;cursor:pointer;letter-spacing:.3px;text-transform:uppercase}" +
            ".btn-del{background:#7f1d1d;color:#fecaca}" +
            ".btn-del:hover{background:#991b1b}" +
            ".btn-del:disabled{background:#1e293b;color:#4b5563;cursor:default}" +
            ".btn-ref{background:#1e293b;color:#94a3b8;border:1px solid #334155}" +
            ".btn-ref:hover{background:#334155}" +

            // Table
            ".table-wrap{background:#1e293b;border-radius:10px;overflow:hidden;margin-bottom:16px}" +
            ".tbl{width:100%;border-collapse:collapse;font-size:13px}" +
            ".tbl th{background:#0f172a;color:#64748b;font-weight:600;text-transform:uppercase;font-size:11px;letter-spacing:.5px;padding:10px 12px;text-align:left;border-bottom:1px solid #334155}" +
            ".tbl td{padding:10px 12px;border-bottom:1px solid rgba(255,255,255,.05);vertical-align:middle}" +
            ".tbl tr:last-child td{border-bottom:none}" +
            ".tbl tr:hover{background:rgba(255,255,255,.02)}" +
            ".book-title{font-weight:600;color:#f1f5f9;word-break:break-word}" +
            ".book-meta{font-size:11px;color:#64748b}" +
            ".badge-mobi{font-size:10px;font-weight:700;background:#1e3a5f;color:#93c5fd;padding:2px 6px;border-radius:4px;margin-right:6px}" +
            ".act-btn{padding:4px 8px;border-radius:4px;font-size:11px;font-weight:600;text-decoration:none;cursor:pointer;border:none;margin-left:4px}" +
            ".act-dl{background:#065f46;color:#a7f3d0}" +
            ".act-dl:hover{background:#047857}" +
            ".act-del{background:#450a0a;color:#fca5a5}" +
            ".act-del:hover{background:#7f1d1d}" +

            ".empty-msg{padding:30px;text-align:center;color:#64748b;font-size:14px}" +

            // Toast
            ".toast{text-align:center;padding:8px 12px;border-radius:8px;font-size:12px;margin-top:8px;display:none}" +
            ".toast.ok{background:#064e3b;color:#6ee7b7;display:block}" +
            ".toast.err{background:#450a0a;color:#fca5a5;display:block}" +

            ".dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#4ade80;margin-left:6px;vertical-align:middle;animation:pulse 2s infinite}" +
            "@keyframes pulse{0%,100%{opacity:1}50%{opacity:.2}}" +
            "</style></head><body><div class='wrap'>" +

            "<h1>Kindle Drone · Books<span class='dot'></span></h1>" +
            "<div class='nav'>" +
            "<a href='/'>Dashboard</a>" +
            "<a href='/gallery'>Gallery</a>" +
            "<a href='/books' class='active'>Books</a>" +
            "<a href='/logs'>Logs</a>" +
            "</div>" +

            // Storage overview
            "<div class='storage-card'>" +
            "<div class='storage-hdr'>" +
            "<span class='storage-title'>Kindle Storage (/mnt/us)</span>" +
            "<span class='storage-val' id='storageText'>Loading…</span>" +
            "</div>" +
            "<div class='storage-bar'><div class='storage-fill' id='storageFill' style='width:0'></div></div>" +
            "<div class='storage-sub'>" +
            "<span>Books count: <strong id='bookCount' style='color:#e2e8f0'>0</strong></span>" +
            "<span>Total MOBI size: <strong id='bookSize' style='color:#e2e8f0'>0 B</strong></span>" +
            "</div>" +
            "</div>" +

            // Dropzone
            "<div class='dropzone' id='dropzone' onclick='document.getElementById(\"fileIn\").click()'>" +
            "<div class='drop-icon'>📚</div>" +
            "<div class='drop-text'>Click or Drag & Drop .mobi files here to upload</div>" +
            "<div class='drop-sub'>Files will be saved to Kindle /mnt/us/documents/ directory</div>" +
            "<input type='file' id='fileIn' accept='.mobi,.azw,.azw3' multiple style='display:none' onchange='handleFiles(this.files)'/>" +
            "</div>" +

            // Progress bar
            "<div class='progress-wrap' id='progressWrap'>" +
            "<div class='progress-bar'><div class='progress-fill' id='progressFill'></div></div>" +
            "<div class='progress-info'><span id='progressStatus'>Uploading…</span><span id='progressPct'>0%</span></div>" +
            "</div>" +

            // Toolbar
            "<div class='toolbar'>" +
            "<input type='text' class='search-in' id='searchIn' placeholder='Search books by title…' oninput='filterBooks()'/>" +
            "<button class='btn btn-del' id='delSelBtn' disabled onclick='deleteSelected()'>Delete Selected (0)</button>" +
            "<button class='btn btn-ref' onclick='loadBooks()'>Refresh</button>" +
            "</div>" +

            // Table
            "<div class='table-wrap'>" +
            "<table class='tbl'>" +
            "<thead><tr>" +
            "<th style='width:36px;text-align:center'><input type='checkbox' id='selectAll' onclick='toggleSelectAll(this)'/></th>" +
            "<th>Title / File</th>" +
            "<th style='width:90px'>Size</th>" +
            "<th style='width:130px'>Modified</th>" +
            "<th style='width:120px;text-align:right'>Actions</th>" +
            "</tr></thead>" +
            "<tbody id='booksTbody'><tr><td colspan='5' class='empty-msg'>Loading books…</td></tr></tbody>" +
            "</table>" +
            "</div>" +

            "<div class='toast' id='toast'></div>" +

            "</div>" +

            // JavaScript
            "<script>" +
            "var booksList=[];" +

            "document.addEventListener('DOMContentLoaded',function(){" +
            "  loadBooks();" +
            "  var dz=document.getElementById('dropzone');" +
            "  dz.addEventListener('dragover',function(e){e.preventDefault();dz.classList.add('dragover');});" +
            "  dz.addEventListener('dragleave',function(e){e.preventDefault();dz.classList.remove('dragover');});" +
            "  dz.addEventListener('drop',function(e){" +
            "    e.preventDefault();dz.classList.remove('dragover');" +
            "    if(e.dataTransfer.files&&e.dataTransfer.files.length)handleFiles(e.dataTransfer.files);" +
            "  });" +
            "});" +

            "function loadBooks(){" +
            "  fetch('/api/books')" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(d){" +
            "      booksList=d.books||[];" +
            "      renderStorage(d);" +
            "      renderBooks(booksList);" +
            "    })" +
            "    .catch(function(){" +
            "      document.getElementById('booksTbody').innerHTML='<tr><td colspan=\"5\" class=\"empty-msg\">Error loading books</td></tr>';" +
            "    });" +
            "}" +

            "function renderStorage(d){" +
            "  var free=d.freeSpaceFormatted||'--';" +
            "  var total=d.totalSpaceFormatted||'--';" +
            "  document.getElementById('storageText').textContent=free+' free / '+total;" +
            "  if(d.totalSpace>0){" +
            "    var usedPct=Math.round(((d.totalSpace-d.freeSpace)/d.totalSpace)*100);" +
            "    document.getElementById('storageFill').style.width=usedPct+'%';" +
            "  }" +
            "  document.getElementById('bookCount').textContent=d.count||booksList.length;" +
            "  document.getElementById('bookSize').textContent=d.totalMobiSizeFormatted||'0 B';" +
            "}" +

            "function renderBooks(list){" +
            "  var tb=document.getElementById('booksTbody');" +
            "  if(!list||!list.length){" +
            "    tb.innerHTML='<tr><td colspan=\"5\" class=\"empty-msg\">No .mobi books found in /mnt/us/documents</td></tr>';" +
            "    updateDelBtn();" +
            "    return;" +
            "  }" +
            "  var html='';" +
            "  for(var i=0;i<list.length;i++){" +
            "    var b=list[i];" +
            "    var nameEsc=escHtml(b.name);" +
            "    var ext=b.name.split('.').pop().toUpperCase();" +
            "    html+='<tr data-name=\"'+nameEsc.toLowerCase()+'\">'+" +
            "      '<td style=\"text-align:center\"><input type=\"checkbox\" class=\"book-chk\" value=\"'+nameEsc+'\" onclick=\"updateDelBtn()\"/></td>'+" +
            "      '<td><span class=\"badge-mobi\">'+ext+'</span><span class=\"book-title\">'+nameEsc+'</span></td>'+" +
            "      '<td class=\"book-meta\">'+(b.sizeFormatted||b.size)+'</td>'+" +
            "      '<td class=\"book-meta\">'+(b.date||'--')+'</td>'+" +
            "      '<td style=\"text-align:right\">'+" +
            "        '<a class=\"act-btn act-dl\" href=\"/api/books/download?name='+encodeURIComponent(b.name)+'\" download=\"'+nameEsc+'\">Download</a>'+" +
            "        '<button class=\"act-btn act-del\" onclick=\"deleteSingle(\\\"'+nameEsc.replace(/\"/g,'&quot;')+'\\\")\">Delete</button>'+" +
            "      '</td>'+" +
            "      '</tr>';" +
            "  }" +
            "  tb.innerHTML=html;" +
            "  document.getElementById('selectAll').checked=false;" +
            "  updateDelBtn();" +
            "}" +

            "function escHtml(s){" +
            "  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');" +
            "}" +

            "function filterBooks(){" +
            "  var q=document.getElementById('searchIn').value.trim().toLowerCase();" +
            "  var rows=document.querySelectorAll('#booksTbody tr');" +
            "  for(var i=0;i<rows.length;i++){" +
            "    var name=rows[i].getAttribute('data-name')||'';" +
            "    rows[i].style.display=name.indexOf(q)>=0?'':'none';" +
            "  }" +
            "}" +

            "function toggleSelectAll(master){" +
            "  var chks=document.querySelectorAll('.book-chk');" +
            "  for(var i=0;i<chks.length;i++){" +
            "    if(chks[i].closest('tr').style.display!=='none')chks[i].checked=master.checked;" +
            "  }" +
            "  updateDelBtn();" +
            "}" +

            "function updateDelBtn(){" +
            "  var sel=getSelectedNames();" +
            "  var btn=document.getElementById('delSelBtn');" +
            "  btn.disabled=sel.length===0;" +
            "  btn.textContent='Delete Selected ('+sel.length+')';" +
            "}" +

            "function getSelectedNames(){" +
            "  var chks=document.querySelectorAll('.book-chk:checked');" +
            "  var names=[];" +
            "  for(var i=0;i<chks.length;i++)names.push(chks[i].value);" +
            "  return names;" +
            "}" +

            "function deleteSingle(name){" +
            "  if(!confirm('Delete \"'+name+'\"?'))return;" +
            "  doDelete([name]);" +
            "}" +

            "function deleteSelected(){" +
            "  var sel=getSelectedNames();" +
            "  if(!sel.length)return;" +
            "  if(!confirm('Delete '+sel.length+' selected book(s)?'))return;" +
            "  doDelete(sel);" +
            "}" +

            "function doDelete(names){" +
            "  fetch('/api/books/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(names)})" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(d){" +
            "      if(d.ok){" +
            "        toast('Deleted '+d.deletedCount+' book(s)','ok');" +
            "        loadBooks();" +
            "      } else {" +
            "        toast(d.error||'Delete failed','err');" +
            "      }" +
            "    }).catch(function(){toast('Connection error','err');});" +
            "}" +

            "function handleFiles(files){" +
            "  if(!files||!files.length)return;" +
            "  var queue=[];" +
            "  for(var i=0;i<files.length;i++){" +
            "    var f=files[i];" +
            "    var n=f.name.toLowerCase();" +
            "    if(n.endsWith('.mobi')||n.endsWith('.azw')||n.endsWith('.azw3')||n.endsWith('.pdf')||n.endsWith('.epub')||n.endsWith('.txt')){" +
            "      queue.push(f);" +
            "    } else {" +
            "      toast('Skipped '+f.name+' (unsupported book format)','err');" +
            "    }" +
            "  }" +
            "  if(queue.length)uploadNext(queue,0);" +
            "}" +

            "function uploadNext(queue,idx){" +
            "  if(idx>=queue.length){" +
            "    document.getElementById('progressWrap').style.display='none';" +
            "    toast('Uploaded '+queue.length+' book(s) successfully!','ok');" +
            "    document.getElementById('fileIn').value='';" +
            "    loadBooks();" +
            "    return;" +
            "  }" +
            "  var file=queue[idx];" +
            "  var pWrap=document.getElementById('progressWrap');" +
            "  var pFill=document.getElementById('progressFill');" +
            "  var pStat=document.getElementById('progressStatus');" +
            "  var pPct=document.getElementById('progressPct');" +
            "  pWrap.style.display='block';" +
            "  pStat.textContent='Uploading ('+(idx+1)+'/'+queue.length+'): '+file.name+'…';" +
            "  pFill.style.width='0%';" +
            "  pPct.textContent='0%';" +

            "  var xhr=new XMLHttpRequest();" +
            "  xhr.open('POST','/api/books/upload?name='+encodeURIComponent(file.name),true);" +
            "  xhr.setRequestHeader('X-Filename',encodeURIComponent(file.name));" +
            "  xhr.upload.onprogress=function(e){" +
            "    if(e.lengthComputable){" +
            "      var pct=Math.round((e.loaded/e.total)*100);" +
            "      pFill.style.width=pct+'%';" +
            "      pPct.textContent=pct+'%';" +
            "    }" +
            "  };" +
            "  xhr.onload=function(){" +
            "    if(xhr.status===200){" +
            "      uploadNext(queue,idx+1);" +
            "    } else {" +
            "      pWrap.style.display='none';" +
            "      toast('Failed uploading '+file.name,'err');" +
            "    }" +
            "  };" +
            "  xhr.onerror=function(){" +
            "    pWrap.style.display='none';" +
            "    toast('Upload error for '+file.name,'err');" +
            "  };" +
            "  xhr.send(file);" +
            "}" +

            "function toast(msg,cls){" +
            "  var el=document.getElementById('toast');" +
            "  el.textContent=msg;el.className='toast '+cls;" +
            "  setTimeout(function(){el.className='toast';},4000);" +
            "}" +

            "</script></body></html>";
    }
}
