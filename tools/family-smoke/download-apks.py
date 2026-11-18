"""Download pinned public CI debug artifacts, never release APKs or signing material."""
import os,json,urllib.request,zipfile,io,pathlib,hashlib,time
runs=json.loads(os.environ['ARTIFACT_RUNS'])
base='https://api.github.com/repos/irvan-putra/'
headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'}
class Redirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self, req, fp, code, msg, headers, newurl):
  new=super().redirect_request(req,fp,code,msg,headers,newurl)
  new.remove_header('Authorization')
  return new
opener=urllib.request.build_opener(Redirect())
def get(url):
 with opener.open(urllib.request.Request(url,headers=headers)) as r:return r.read()
out=pathlib.Path('host-apks');out.mkdir(exist_ok=True)
for repo,spec in runs.items():
 run=str(spec['run']); needle=spec['artifact']
 for attempt in range(21):
  data=json.loads(get(base+repo+'/actions/runs/'+run))
  assert data['head_sha']==spec['sha'], (repo,'commit mismatch')
  if data['status']=='completed':break
  print(repo,'awaiting pinned debug qualification',data['status'],flush=True)
  if attempt==20:raise RuntimeError((repo,'qualification timeout'))
  time.sleep(30)
 assert data['status']=='completed' and data['conclusion']=='success', (repo,'run not green')
 artifacts=json.loads(get(base+repo+'/actions/runs/'+run+'/artifacts'))['artifacts']
 choices=[a for a in artifacts if needle in a['name']]
 assert len(choices)==1,(repo,needle,[a['name'] for a in artifacts])
 z=zipfile.ZipFile(io.BytesIO(get(choices[0]['archive_download_url'])))
 apks=[n for n in z.namelist() if n.endswith('.apk')]
 assert len(apks)==1,(repo,apks)
 blob=z.read(apks[0]);(out/(repo+'.apk')).write_bytes(blob)
 print(repo, data['head_sha'], data['html_url'], hashlib.sha256(blob).hexdigest(),len(blob),flush=True)
