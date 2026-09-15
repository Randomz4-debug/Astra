from pathlib import Path
p=Path('app/src/main/java/com/astra/ai/AstraLanServer.kt')
s=p.read_text(encoding='utf-8')
marker='</script></body></html>\n"""'
over='''
// Astra Web UI hardening overrides. These are intentionally defined last so they replace broken legacy handlers.
async function send(){const text=q.value.trim();if(!text)return;add(text,'u');q.value='';status.textContent='Thinking…';try{const r=await jsonFetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:text,localOnly:false})});add(r.response||r.error||'No response','a')}catch(e){add('Error: '+e.message,'a')}finally{status.textContent='Ready'}}
async function parallel(){const raw=q.value.trim();if(!raw){add('Enter multiple tasks, one per line, then press Parallel.','a');return}const tasks=raw.split(/\\n+/).map(x=>x.trim()).filter(Boolean).map(x=>({task:x,instruction:document.getElementById('instruction').value.trim()}));add('Parallel: '+tasks.map(x=>x.task).join(' | '),'u');q.value='';status.textContent='Running in parallel…';try{const r=await jsonFetch('/api/parallel',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tasks})});(r.results||[]).forEach((x,i)=>add('Task '+(i+1)+': '+x,'a'));}catch(e){add('Parallel error: '+e.message,'a')}finally{status.textContent='Ready'}}
async function backgroundTask(){const text=q.value.trim();if(!text){add('Enter a task first, then press Background task.','a');return}add('Background task: '+text,'u');q.value='';status.textContent='Starting background task…';try{const r=await jsonFetch('/api/tasks',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({prompt:text})});add('Started task '+r.id,'a');refreshTasks()}catch(e){add('Background task error: '+e.message,'a')}finally{status.textContent='Ready'}}
async function refreshTasks(){try{const a=await jsonFetch('/api/tasks');document.getElementById('tasksOut').innerHTML=(a.map? a.map(t=>`<div class="file"><span><b>${t.id}</b> ${t.status} • ${t.step}/${t.total}<br><span class="small">${String(t.prompt||'')}</span></span><button class="secondary" onclick="stopTask('${t.id}')">Stop</button></div>`).join(''):'No tasks')}catch(e){document.getElementById('tasksOut').textContent=e.message}}
async function stopTask(id){try{await jsonFetch('/api/tasks/'+encodeURIComponent(id),{method:'DELETE'});refreshTasks()}catch(e){add('Stop error: '+e.message,'a')}}
q.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}});
refreshTasks();
</script></body></html>
"""'''
if 'Astra Web UI hardening overrides' not in s:
    s=s.replace(marker,over)
p.write_text(s,encoding='utf-8')
