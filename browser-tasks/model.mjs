export const STORE_KEY='tindak-task-web-v1';
export function empty(){return {format:'tindak-task-web',version:1,lists:[{id:'inbox',name:'Inbox'}],tasks:[]};}
const text=(v,max)=>typeof v==='string'&&v.length<=max;
export function validate(x){
 if(!x||x.format!=='tindak-task-web'||x.version!==1||!Array.isArray(x.lists)||!Array.isArray(x.tasks)||x.lists.length<1||x.lists.length>200||x.tasks.length>10000)throw Error('This is not a supported task backup.');
 const ids=new Set(),ls=new Set();
 for(const l of x.lists){if(!l||!text(l.id,100)||!l.id||ids.has(l.id)||!text(l.name,200)||!l.name.trim())throw Error('A list in the backup is invalid.');ids.add(l.id);ls.add(l.id);}
 for(const t of x.tasks){if(!t||!text(t.id,100)||!t.id||ids.has(t.id)||!ls.has(t.list)||!text(t.title,500)||!t.title.trim()||!text(t.note,10000)||typeof t.done!=='boolean'||typeof t.archived!=='boolean'||!text(t.due,10)||!validDate(t.due)||!Number.isSafeInteger(t.created)||!Number.isSafeInteger(t.updated)||t.created<0||t.updated<0)throw Error('A task in the backup is invalid.');ids.add(t.id);}
 return structuredClone({format:x.format,version:x.version,lists:x.lists.map(({id,name})=>({id,name})),tasks:x.tasks.map(({id,list,title,note,done,archived,due,created,updated})=>({id,list,title,note,done,archived,due,created,updated}))});
}
export function validDate(v){if(v==='')return true;if(!/^\d{4}-\d{2}-\d{2}$/.test(v))return false;const d=new Date(v+'T12:00:00Z');return Number.isFinite(+d)&&d.toISOString().slice(0,10)===v;}
export function addTask(state,{id,list,title,note='',due='',now}){const next=structuredClone(state);next.tasks.push({id,list,title:title.trim(),note,done:false,archived:false,due,created:now,updated:now});return validate(next);}
export function updateTask(state,id,patch,now){const next=structuredClone(state),t=next.tasks.find(t=>t.id===id);if(!t)throw Error('Task no longer exists.');for(const key of ['title','note','done','archived','due','list'])if(key in patch)t[key]=patch[key];t.title=t.title.trim();t.updated=now;return validate(next);}
export function addList(state,id,name){const next=structuredClone(state);next.lists.push({id,name:name.trim()});return validate(next);}
export function importCopy(state,backup,newId){const next=validate(state),incoming=validate(backup),mapping=new Map();for(const l of incoming.lists){const id=newId();mapping.set(l.id,id);next.lists.push({id,name:(l.name+' (imported)').slice(0,200)});}for(const t of incoming.tasks)next.tasks.push({...t,id:newId(),list:mapping.get(t.list)});return validate(next);}
export function save(storage,key,state){const clean=validate(state);storage.setItem(key,JSON.stringify(clean));return clean;}
export function load(storage,key){const raw=storage.getItem(key);return raw===null?empty():validate(JSON.parse(raw));}
export function demo(){let s=empty();s.lists[0].name='Demo list';s=addTask(s,{id:'example-1',list:'inbox',title:'Try checking this fictional task',note:'Demo changes never touch your own list.',now:1});return addTask(s,{id:'example-2',list:'inbox',title:'Open a task to edit its note',note:'No account, no upload, no phone sync.',now:2});}
