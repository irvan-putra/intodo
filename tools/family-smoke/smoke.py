"""Focused synthetic widget tap, before resize; no root or account activity."""
import subprocess,time,pathlib,xml.etree.ElementTree as E,re,json
out=pathlib.Path('host-evidence');out.mkdir(exist_ok=True)
def adb(*args):return subprocess.run(['adb',*args],capture_output=True,check=True,timeout=45).stdout
# Do not restart adbd. Bounded boot/readiness check before any install.
for attempt in range(24):
 try:
  if adb('shell','getprop','sys.boot_completed').strip()==b'1':break
 except (subprocess.CalledProcessError,subprocess.TimeoutExpired):pass
 time.sleep(2)
else:raise AssertionError('emulator not ready within48s; no install attempted')
adb('shell','wm','size','1080x2340');adb('shell','wm','density','420');adb('shell','settings','put','system','font_scale','1.0')
adb('install','-r','host-apks/intodo.apk');adb('install','-r','tools/family-smoke/app/build/outputs/apk/debug/app-debug.apk')
# run-as uses the disposable debug app uid, never adb root.
feed=json.dumps(dict(sections=[dict(id='synthetic',title='Sample list',items=[dict(id=str(i),title='Read chapter '+str(i),note='Next small step',status='open')for i in range(1,7)])]))
r=E.Element('map');E.SubElement(r,'string',name='feed').text=feed;E.ElementTree(r).write('/tmp/family-profile.xml',encoding='utf-8')
adb('push','/tmp/family-profile.xml','/data/local/tmp/family-profile.xml');adb('shell','run-as','com.vanipy.intodo','mkdir','-p','shared_prefs');adb('shell','run-as','com.vanipy.intodo','cp','/data/local/tmp/family-profile.xml','shared_prefs/instinct_widget.xml')
adb('shell','appwidget','grantbind','--package','com.vanipy.widgethost','--user','0')
adb('shell','am','start','-W','-n','com.vanipy.widgethost/.HostActivity','--ei','single','0','--ei','spanX','1','--ei','spanY','1')
found=None
for attempt in range(12):
 adb('shell','uiautomator','dump','/sdcard/family-tap.xml');xml=adb('exec-out','cat','/sdcard/family-tap.xml');(out/'widget.xml').write_bytes(xml)
 try:found=next((n for n in E.fromstring(xml).iter('node')if n.get('resource-id')=='com.vanipy.intodo:id/widget_summary_root'),None)
 except E.ParseError:pass
 if found is not None:break
 time.sleep(1)
assert found is not None,'1x1 summary root absent before tap'
(out/'widget.png').write_bytes(adb('exec-out','screencap','-p'))
x1,y1,x2,y2=map(int,re.findall(r'\d+',found.get('bounds','')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
focus=''
for attempt in range(12):
 state=adb('shell','dumpsys','activity','activities').decode();focus='\n'.join(l for l in state.splitlines()if 'topResumedActivity' in l or 'mResumedActivity' in l)
 if 'com.vanipy.intodo/.MainActivity' in focus:break
 time.sleep(1)
else:raise AssertionError('widget tap did not resume MainActivity: '+focus)
(out/'resumed.txt').write_text(focus)
# Resumed activity is not a rendered app: require seeded content on screen.
visible=False
for attempt in range(18):
 try:
  adb('shell','uiautomator','dump','/sdcard/family-app.xml')
  xml=adb('exec-out','cat','/sdcard/family-app.xml');(out/'app.xml').write_bytes(xml)
  nodes=list(E.fromstring(xml).iter('node'))
  visible=any(n.get('text')=='Read chapter 1' and n.get('package')=='com.vanipy.intodo' for n in nodes)
  if visible:break
 except (E.ParseError,subprocess.CalledProcessError,subprocess.TimeoutExpired):pass
 time.sleep(1)
(out/'app.png').write_bytes(adb('exec-out','screencap','-p'))
(out/'package.txt').write_bytes(adb('shell','dumpsys','package','com.vanipy.intodo'))
(out/'logcat.txt').write_bytes(adb('logcat','-d','-t','600'))
(out/'activity.txt').write_bytes(adb('shell','dumpsys','activity','activities'))
assert visible,'MainActivity resumed but seeded row did not become visible within bounded content wait; inspect app.xml/app.png/logcat.txt'

print('PASS exact renamed candidate package,label (aapt),1x1 actual provider tap -> MainActivity and visible seeded row')
