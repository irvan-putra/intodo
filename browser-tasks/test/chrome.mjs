import {spawn} from 'node:child_process';
import {mkdtempSync,readFileSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
export async function launch(){
 const dir=mkdtempSync(path.join(tmpdir(),'task-ui-chrome-'));
 const proc=spawn(process.env.CHROME_BIN||'google-chrome',['--headless=new','--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--no-first-run','--remote-debugging-port=0','--user-data-dir='+dir,'about:blank'],{stdio:['ignore','ignore','pipe']});
 let stderr='';proc.stderr.on('data',b=>{stderr=(stderr+b.toString()).slice(-5000);});proc.on('error',e=>{stderr+=e.message;});
 let port;for(let i=0;i<800;i++){if(existsSync(path.join(dir,'DevToolsActivePort'))){port=readFileSync(path.join(dir,'DevToolsActivePort'),'utf8').split('\n')[0];break;}await new Promise(r=>setTimeout(r,25));}if(!port){proc.kill();throw Error('Chrome failed to start. Set CHROME_BIN to its executable. '+stderr);}
 const targets=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json(),target=targets.find(t=>t.type==='page');const ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
 let id=0;const pending=new Map(),events=new Map();
 ws.onmessage=ev=>{const x=JSON.parse(ev.data);if(x.id){const p=pending.get(x.id);if(!p)return;pending.delete(x.id);clearTimeout(p.timer);x.error?p.reject(Error(x.error.message)):p.resolve(x.result);}else if(x.method){const listeners=events.get(x.method)||[];events.delete(x.method);for(const fn of listeners)fn(x.params);}};
 function page(method,params={}){return new Promise((resolve,reject)=>{const key=++id;const timer=setTimeout(()=>{pending.delete(key);reject(Error('Timed out: '+method));},30000);pending.set(key,{resolve,reject,timer});ws.send(JSON.stringify({id:key,method,params}));});}
 function waitFor(method){return new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Timed out waiting for '+method)),30000);const listeners=events.get(method)||[];listeners.push(v=>{clearTimeout(timer);resolve(v);});events.set(method,listeners);});}
 return {page,waitFor,close(){ws.close();proc.kill();}};
}
