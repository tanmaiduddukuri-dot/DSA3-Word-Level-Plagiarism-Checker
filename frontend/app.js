const $=id=>document.getElementById(id);
let refs=[];

function words(text, stop=false){
  const raw=text.toLowerCase().match(/[a-z0-9]+(?:'[a-z0-9]+)*/g)||[];
  if(!stop)return raw;
  const sw=new Set("a an the and or but if then than is are was were be been being to of in on for with as by at from this that these those it its into about over under after before a".split(" "));
  return raw.filter(w=>!sw.has(w));
}
function escapeHTML(s){return s.replace(/[&<>"']/g,m=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[m]));}
function addReference(name,text){if(!text.trim())return;refs.push({name:name||`Reference ${refs.length+1}`,text});renderRefs();}
function renderRefs(){
  $("referencesList").innerHTML=refs.length?refs.map((r,i)=>`<div class="ref-item"><span>📄 ${escapeHTML(r.name)} <small>(${words(r.text).length} words)</small></span><button onclick="removeRef(${i})">Remove</button></div>`).join(""):`<div class="ref-item">No reference sources added yet.</div>`;
}
function removeRef(i){refs.splice(i,1);renderRefs();}
function updateStats(){
  const t=$("submission").value;
  $("submissionWords").textContent=`${words(t).length} words`;
  $("submissionChars").textContent=`${t.length} characters`;
}
$("submission").addEventListener("input",updateStats);
$("k").addEventListener("input",e=>$("kValue").textContent=e.target.value);
$("minSpan").addEventListener("input",e=>$("spanValue").textContent=e.target.value);
$("addRef").onclick=()=>{addReference($("refName").value,$("refText").value);$("refName").value="";$("refText").value="";};

$("referenceFiles").onchange=async e=>{
  for(const f of e.target.files)addReference(f.name,await f.text());
  e.target.value="";
};
$("submissionFile").onchange=async e=>{
  if(e.target.files[0]){$("submission").value=await e.target.files[0].text();updateStats();}
  e.target.value="";
};
$("themeBtn").onclick=()=>{document.body.classList.toggle("dark");$("themeBtn").textContent=document.body.classList.contains("dark")?"☀":"☾";};

// Base URL of the Java backend (com.plagcheck.api.Server). Override from the
// browser console with `window.API_BASE = "http://host:port"` if needed.
const API_BASE = window.API_BASE || "http://localhost:8787";

async function analyze(){
  const text=$("submission").value.trim();
  if(!text){alert("Please add a submission document.");return;}
  if(!refs.length){alert("Please add at least one reference source.");return;}
  const stop=$("stopwords").checked,k=+$("k").value,min=+$("minSpan").value;
  const btn=$("checkBtn"), original=btn.textContent;
  btn.disabled=true; btn.textContent="Analyzing…";
  try{
    const res=await fetch(`${API_BASE}/api/check`,{
      method:"POST",
      headers:{"Content-Type":"application/json"},
      body:JSON.stringify({
        submission:{name:"submission.txt",text},
        references:refs.map(r=>({name:r.name,text:r.text})),
        k, minSpan:min, stopwords:stop,
      }),
    });
    if(!res.ok){
      const err=await res.json().catch(()=>({error:res.statusText}));
      throw new Error(err.error||`Server returned ${res.status}`);
    }
    const data=await res.json();
    renderResults(data);
  }catch(e){
    alert(`Couldn't reach the plagiarism-checker backend at ${API_BASE}.\n\n`+
      `Make sure it's running (java com.plagcheck.api.Server) and reachable from this page.\n\n`+
      `Details: ${e.message}`);
  }finally{
    btn.disabled=false; btn.textContent=original;
  }
}

function renderResults(data){
  const {overallSimilarityPercent:score,exactTotal:exact,paraTotal:para,coveredTokens:matched,totalTokens:total,sources,highlightedHtml}=data;
  $("results").classList.remove("hidden");
  $("score").textContent=score.toFixed(2)+"%";
  document.querySelector(".score-ring").style.setProperty("--deg",score*3.6+"deg");
  $("exactCount").textContent=exact;$("paraCount").textContent=para;$("matchedCount").textContent=matched;
  const risk=$("risk");risk.textContent=score>=40?"HIGH":score>=20?"MEDIUM":"LOW";risk.className="badge "+(score>=40?"danger":score>=20?"warning":"safe");
  $("resultTitle").textContent=score>=20?"Potential plagiarism detected":"Low similarity detected";
  $("resultSummary").textContent=`${matched} of ${total} normalized submission tokens were attributed to reference material. Exact evidence is prioritized over paraphrase evidence.`;
  $("sourceBreakdown").innerHTML=sources.map(s=>`<div class="source-row"><div class="source-top"><span>${escapeHTML(s.name)}</span><b>${s.percent.toFixed(2)}%</b></div><div class="bar"><i style="width:${Math.min(100,s.percent)}%"></i></div><small>${s.exact} exact · ${s.para} paraphrase tokens</small></div>`).join("");
  const ms=[];sources.forEach(s=>{if(s.exact)ms.push(`<div class="match"><b>EXACT</b> — ${s.exact} tokens from ${escapeHTML(s.name)}</div>`);if(s.para)ms.push(`<div class="match"><b>PARAPHRASE</b> — ${s.para} tokens from ${escapeHTML(s.name)}</div>`);});
  $("matches").innerHTML=ms.join("")||"<div class='match'>No matching spans found.</div>";
  $("highlighted").innerHTML=highlightedHtml||"";
  $("results").scrollIntoView({behavior:"smooth"});
}
$("checkBtn").onclick=analyze;
$("clearHighlights").onclick=()=>{$("highlighted").innerHTML=escapeHTML($("submission").value);};
renderRefs();updateStats();