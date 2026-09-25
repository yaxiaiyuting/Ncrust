import re,sys,json

def parse(path):
    """aapt2 xmltree -> list of nodes with depth"""
    nodes=[]; stack=[]
    for ln,line in enumerate(open(path,encoding='utf-8',errors='replace'),1):
        m=re.match(r'^(\s*)E: (\S+) \(line=(\d+)\)',line)
        if m:
            depth=len(m.group(1))//2; name=m.group(2); srcline=int(m.group(3))
            node={'name':name,'attrs':{},'children':[],'srcline':srcline,'depth':depth,'xml_line':ln}
            while stack and stack[-1]['depth']>=depth: stack.pop()
            if stack: stack[-1]['children'].append(node)
            else: nodes.append(node)
            stack.append(node); continue
        m=re.match(r'^(\s*)A: (\S+)(?:\([^)]*\))?=(.*)$',line)
        if m and stack:
            stack[-1]['attrs'][m.group(2)]=m.group(3).strip()
    return nodes

def walk(nodes):
    for n in nodes:
        yield n
        yield from walk(n['children'])

def val(a):
    if a is None: return None
    a=a.strip()
    m=re.match(r'^\(type 0x[0-9a-f]+\)(0x[0-9a-f]+)$',a)
    if m: return str(int(m.group(1),16))
    m=re.match(r'^"?(.*?)"?\s*\(Raw:.*\)$',a)
    if m: return m.group(1)
    return a.strip('"')

def summarize(path):
    top=parse(path); flat=list(walk(top))
    res={'receivers':[],'services':[],'activities':[],'permissions':[],'uses_sdk':{},'other':[]}
    for n in flat:
        if n['name']=='receiver':
            acts=[]; prio=None
            for c in walk(n['children']):
                if c['name']=='action': acts.append(val(c['attrs'].get('android:name')))
                if c['name']=='category': acts.append('category:'+str(val(c['attrs'].get('android:name'))))
                if c['name'] in ('intent-filter',):
                    p=c['attrs'].get('android:priority')
                    if p is not None: prio=val(p)
            res['receivers'].append({'name':val(n['attrs'].get('android:name')),
                                     'exported':val(n['attrs'].get('android:exported')),
                                     'permission':val(n['attrs'].get('android:permission')),
                                     'priority':prio,'filters':acts,
                                     'srcline':n['srcline']})
        elif n['name']=='service':
            acts=[]
            for c in walk(n['children']):
                if c['name']=='action': acts.append(val(c['attrs'].get('android:name')))
                if c['name']=='intent-filter':
                    p=c['attrs'].get('android:priority')
                    if p is not None: acts.append('priority='+str(val(p)))
            res['services'].append({'name':val(n['attrs'].get('android:name')),
                                    'exported':val(n['attrs'].get('android:exported')),
                                    'foregroundServiceType':val(n['attrs'].get('android:foregroundServiceType')),
                                    'permission':val(n['attrs'].get('android:permission')),
                                    'filters':acts,'srcline':n['srcline']})
        elif n['name']=='uses-permission' or n['name']=='uses-permission-sdk-23':
            res['permissions'].append((n['name'],val(n['attrs'].get('android:name'))))
        elif n['name']=='uses-sdk':
            for k,v in n['attrs'].items(): res['uses_sdk'][k]=val(v)
        elif n['name'] in ('activity','activity-alias'):
            acts=[]
            for c in walk(n['children']):
                if c['name']=='action': acts.append(val(c['attrs'].get('android:name')))
            res['activities'].append({'name':val(n['attrs'].get('android:name')),'filters':acts,'srcline':n['srcline']})
    return res

out={}
for tag in ('netease','huaweimusic','ncrust'):
    out[tag]=summarize(f'tmp/manifest/{tag}.manifest.xmltree.txt')
json.dump(out,open('tmp/manifest-summary.json','w'),ensure_ascii=False,indent=1)

for tag,r in out.items():
    print('='*20,tag)
    print(' receivers 总数:',len(r['receivers']),' services 总数:',len(r['services']),' activities 总数:',len(r['activities']))
    print(' uses-sdk:',r['uses_sdk'])
    print(' 权限数:',len(r['permissions']))
    med=[x for x in r['receivers'] if any(a and re.search(r'MEDIA|media|Media',str(a)) for a in x['filters'])]
    print(' 媒体相关 receiver:',len(med))
    for x in med[:12]:
        print('   -',x['name'],'exported=',x['exported'],'prio=',x['priority'],'srcline=',x['srcline'])
        for a in x['filters']: print('       ',a)
    meds=[x for x in r['services'] if any(a and re.search(r'MEDIA|media|Media',str(a)) for a in x['filters'])]
    print(' 媒体相关 service:',len(meds))
    for x in meds[:12]:
        print('   -',x['name'],'exported=',x['exported'],'fgsType=',x['foregroundServiceType'],'srcline=',x['srcline'])
        for a in x['filters']: print('       ',a)
