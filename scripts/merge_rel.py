import json
from collections import Counter

path=r'd:\\git\\concept-sketch\\grammars\\relations.json'
with open(path,'r',encoding='utf-8') as f:
    data=json.load(f)
rels=data['relations']
merged={}
order=[]
for r in rels:
    id=r['id']
    if id not in merged:
        merged[id]=r.copy()
        order.append(id)
    else:
        existing=merged[id]
        # combine fields
        for k,v in r.items():
            if k=='id': continue
            if k not in existing:
                existing[k]=v
            else:
                if existing[k]!=v:
                    bcqk='bcql_'+k
                    if bcqk not in existing:
                        existing[bcqk]=existing[k]
        # later entry takes precedence
        existing.update(r)
newrels=[merged[id] for id in order]
out={'version':data.get('version'),'description':data.get('description'),'bcql':data.get('bcql'),'relations':newrels}
print(json.dumps(out, indent=2, ensure_ascii=False))
