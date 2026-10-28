const $=id=>document.getElementById(id);
let csrf, me, orders=[], page=0, selected=null, pending=null;
function el(tag,text,cls){const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;return n;}
function notice(text,error=false){$('notice').textContent=text;$('notice').className=error?'error':'';}
async function token(){const r=await fetch('/api/csrf');csrf=await r.json();}
async function api(path,{method='GET',body,headers={}}={}){
 const response=await fetch(path,{method,headers:{...(body?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{[csrf.header]:csrf.token}:{}),...headers},body:body?JSON.stringify(body):undefined});
 if(response.status===401){showLogin();throw new Error('Sign in to continue.');}
 if(!response.ok){let message='Request failed. Refresh and retry.';try{const data=await response.json();message=data.message||message;}catch{}throw new Error(message);}
 return response.status===204?null:response.json();
}
function planner(){return me?.roles.includes('ROLE_PLANNER');}
function showLogin(){me=null;$('workspace').hidden=true;$('login-panel').hidden=false;$('logout').hidden=true;$('identity').textContent='Not signed in';}
function localInput(date){return new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,16);}
async function refresh(){
 const selectedAsset=$('asset').value, selectedTech=$('technician').value;
 const [assets,technicians,result]=await Promise.all([api('/api/assets'),api('/api/technicians'),api('/api/orders?page='+page+($('status-filter').value?'&status='+$('status-filter').value:''))]);
 orders=result;$('assets').replaceChildren();$('asset').replaceChildren();$('technician').replaceChildren();
 for(const asset of assets){const card=el('article','','asset');card.append(el('span',asset.code),el('strong',asset.name),el('small',asset.location));$('assets').append(card);const option=el('option',`${asset.code} · ${asset.name}`);option.value=asset.id;$('asset').append(option);}
 for(const tech of technicians){const option=el('option',tech.DISPLAY_NAME);option.value=tech.USERNAME;$('technician').append(option);}
 if([...$('asset').options].some(x=>x.value===selectedAsset))$('asset').value=selectedAsset;
 if([...$('technician').options].some(x=>x.value===selectedTech))$('technician').value=selectedTech;
 $('asset-count').textContent=assets.length+' assets';renderOrders();
}
function renderOrders(){
 $('total').textContent=orders.length;$('scheduled').textContent=orders.filter(x=>x.status==='SCHEDULED').length;$('progress').textContent=orders.filter(x=>x.status==='IN_PROGRESS').length;$('overdue').textContent=orders.filter(x=>x.overdue).length;
 $('orders').replaceChildren();
 if(!orders.length){const empty=el('div','','empty');empty.append(el('strong','A clear schedule starts here.'),el('span','Create a reservation, or choose another status to view work.'));$('orders').append(empty);}
 for(const order of orders){const date=new Date(order.startsAt),card=el('article','','order'),day=el('div','','date');day.append(el('span',date.toLocaleDateString('en',{month:'short'}).toUpperCase()),el('strong',date.getDate()));const text=el('div','');text.append(el('h3',order.title),el('p',`${order.assetCode} · ${order.technician}`),el('p',`${date.toLocaleString()} — ${new Date(order.endsAt).toLocaleTimeString()}`),el('span',order.status.replaceAll('_',' '),'status '+order.status));if(order.overdue)text.append(el('span','OVERDUE','late'));const button=el('button','View ↗','secondary');button.addEventListener('click',()=>openOrder(order).catch(e=>notice(e.message,true)));card.append(day,text,button);$('orders').append(card);}
 $('page-label').textContent='Page '+(page+1);$('prev').disabled=page===0;$('next').disabled=orders.length<100;
}
async function openOrder(order){selected=order;$('detail').hidden=false;$('detail-title').textContent=`#${order.id} · ${order.title}`;$('detail-body').textContent=`${order.assetCode} / ${order.status} / version ${order.version}`;$('note').value='';$('actions').replaceChildren();
 const actions=[];if(planner()&&order.status==='SCHEDULED')actions.push('cancel');if(me.username===order.technician){if(order.status==='SCHEDULED')actions.push('start');if(order.status==='IN_PROGRESS')actions.push('complete');}
 $('action-form').hidden=!actions.length;
 for(const action of actions){const button=el('button',action[0].toUpperCase()+action.slice(1)+' work','primary');button.type='submit';button.value=action;$('actions').append(button);}
 const history=await api(`/api/orders/${order.id}/history`);$('history').replaceChildren();for(const event of history){const line=el('article','','audit');line.append(el('strong',`${event.action} · ${event.actor}`),el('p',event.note),el('small',new Date(event.createdAt).toLocaleString()));$('history').append(line);}
 $('detail').scrollIntoView({behavior:'smooth',block:'start'});
}
async function enter(){await token();me=await api('/api/me');$('identity').textContent=me.username+' / '+me.roles.map(x=>x.replace('ROLE_','').toLowerCase()).join(', ');$('login-panel').hidden=true;$('workspace').hidden=false;$('logout').hidden=false;$('planner-panel').hidden=!planner();$('asset-editor').hidden=!planner();await refresh();}
$('login-form').addEventListener('submit',async event=>{event.preventDefault();try{await token();const response=await fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',[csrf.header]:csrf.token},body:new URLSearchParams({username:$('username').value,password:$('password').value})});if(!response.ok)throw new Error('Sign-in failed. Check the username and password.');$('password').value='';notice('');await enter();}catch(e){notice(e.message,true);}});
$('logout').addEventListener('click',async()=>{try{await api('/api/logout',{method:'POST'});showLogin();await token();notice('Signed out.');}catch(e){notice(e.message,true);}});
$('booking-form').addEventListener('submit',async event=>{event.preventDefault();$('book').disabled=true;try{const request={assetId:Number($('asset').value),technician:$('technician').value,title:$('title').value.trim(),startsAt:new Date($('starts').value).toISOString(),durationMinutes:Number($('duration').value),occurrences:Number($('occurrences').value),intervalDays:Number($('interval').value)};const payload=JSON.stringify(request);if(!pending||pending.payload!==payload)pending={payload,key:crypto.randomUUID()};const result=await api('/api/orders',{method:'POST',body:request,headers:{'Idempotency-Key':pending.key}});pending=null;notice(`${result.length} work order(s) reserved. Asset and technician windows are conflict-free.`);await refresh();}catch(e){notice(e.message,true);}finally{$('book').disabled=false;}});
$('action-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;try{const updated=await api(`/api/orders/${selected.id}/${button.value}`,{method:'POST',body:{expectedVersion:selected.version,note:$('note').value.trim()}});notice('Work order updated; audit event recorded.');await refresh();await openOrder(updated);}catch(e){notice(e.message,true);}finally{button.disabled=false;}});
$('asset-form').addEventListener('submit',async event=>{event.preventDefault();try{await api('/api/assets',{method:'POST',body:{code:$('asset-code').value,name:$('asset-name').value.trim(),location:$('asset-location').value.trim()}});$('asset-form').reset();await refresh();notice('Asset registered.');}catch(e){notice(e.message,true);}});
$('refresh').addEventListener('click',()=>refresh().catch(e=>notice(e.message,true)));$('status-filter').addEventListener('change',()=>{page=0;refresh().catch(e=>notice(e.message,true));});$('prev').addEventListener('click',()=>{page--;refresh().catch(e=>notice(e.message,true));});$('next').addEventListener('click',()=>{page++;refresh().catch(e=>notice(e.message,true));});$('close-detail').addEventListener('click',()=>{$('detail').hidden=true;});
$('starts').value=localInput(new Date(Date.now()+3600000));
enter().catch(e=>{if(me)notice(e.message,true);});
