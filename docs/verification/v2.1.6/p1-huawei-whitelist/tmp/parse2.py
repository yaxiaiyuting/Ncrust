import re,json

def parse(path):
    nodes=[];stack=[]
    for ln,line in enumerate(open(path,encoding='utf-8',errors='replace'),1):
        m=re.match(r'^(\s*)E: (\S+) \(line=(\d+)\)',line)
        if m:
            d=len(m.group(1))//2
            n={'name':m.group(2),'attrs':{},'children':[],'srcline':int(m.group(3)),'depth':d,'xml_line':ln}
            while stack and stack[-1]['depth']>=d: stack.pop()
            (stack[-1]['children'] if stack else nodes).append(n)
            stack.append(n); continue
        m=re.match(r'^(\s*)A: (.*?)\(0x[0-9a-f]+\)=(.*)$',line) or re.match(r'^(\s*)A: (.*?)=(.*)$',line)
        if m and stack:
            stack[-1]['attrs'][m.group(2)]=m.group(3).strip()
    return nodes

def walk(ns):
    for n in ns:
        yield n; yield from walk(n['children'])

def val(a):
    if a is None: return None
    m=re.search(r'\(Raw: (.*)\)\s*$',a)
    if m: return m.group(1).strip().strip('"')
    a=a.strip()
    m=re.match(r'^\(type 0x[0-9a-f]+\)(0x[0-9a-f]+)$',a)
    if m: return str(int(m.group(1),16))
    return a.strip('"')

def summarize(path):
    flat=list(walk(parse(path)))
    r={'receivers':[],'services':[],'permissions':[],'uses_sdk':{},'n_activity':0}
    for n in flat:
        if n['name'] in ('receiver','service'):
            acts=[];prio=None
            for c in walk(n['children']):
                if c['name']=='action': acts.append(val(c['attrs'].get('android:name')))
                elif c['name']=='intent-filter':
                    p=c['attrs'].get('android:priority')
                    if p is not None: prio=val(p)
            r[n['name']+'s'].append({'name':val(n['attrs'].get('android:name')),
                'exported':val(n['attrs'].get('android:exported')),
                'permission':val(n['attrs'].get('android:permission')),
                'fgsType':val(n['attrs'].get('android:foregroundServiceType')),
                'priority':prio,'actions':acts,'srcline':n['srcline']})
        elif n['name'] in ('uses-permission','uses-permission-sdk-23'):
            r['permissions'].append(val(n['attrs'].get('android:name')))
        elif n['name']=='uses-sdk':
            for k,v in n['attrs'].items(): r['uses_sdk'][k.split(':')[-1].split('(')[0]]=val(v)
        elif n['name'] in ('activity','activity-alias'): r['n_activity']+=1
    return r

out={t:summarize(f'tmp/manifest/{t}.manifest.xmltree.txt') for t in ('netease','huaweimusic','ncrust')}
json.dump(out,open('tmp/manifest-summary.json','w'),ensure_ascii=False,indent=1)
KEY=re.compile(r'MEDIA_BUTTON|MediaBrowser|MediaSession|MediaLibrary|media\.browse|mediasession|playback|Playback|audio',re.I)
for t,r in out.items():
    print('='*22,t)
    print(' uses-sdk',r['uses_sdk'],' activity',r['n_activity'],' 权限',len(r['permissions']))
    print(' receiver 总数',len(r['receivers']),' service 总数',len(r['services']))
    print(' -- 媒体相关 receiver:')
    for x in r['receivers']:
        if any(a and KEY.search(a) for a in x['actions']):
            print(f"    {x['name']}  exported={x['exported']} prio={x['priority']} line={x['srcline']}")
            for a in x['actions']: print('        ',a)
    print(' -- 媒体相关 service:')
    for x in r['services']:
        if any(a and KEY.search(a) for a in x['actions']):
            print(f"    {x['name']}  exported={x['exported']} fgs={x['fgsType']} line={x['srcline']}")
            for a in x['actions']: print('        ',a)
