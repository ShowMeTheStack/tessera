import json,urllib.request,time,sys
P=lambda i,t,x,y,pr:{"id":i,"type":t,"x":x,"y":y,"rot":0,"mir":False,"params":pr,"pins":[]}
N=lambda i,*pins:{"id":i,"pins":[list(p.split('.')) for p in pins]}
doc={"name":"BoardTest","title":"ZedBoard check: walking LEDs, switches, buttons","package":"studio","next":200,"notes":[],
"parts":[P("sw","in",2,2,{"name":"sw","width":8}),P("btn","in",2,20,{"name":"btn","width":5}),
 P("ev","every",2,10,{"seconds":0.25}),P("cnt","counter",14,10,{"max":3}),P("oh","onehot",26,10,{"n":4}),
 P("any","reduce",14,2,{"op":"or"}),P("m1","mux",40,4,{"inputs":2}),
 P("bc","slice",14,20,{"hi":0,"lo":0}),P("ff","const",30,16,{"value":"0xff","width":8}),P("m2","mux",52,8,{"inputs":2}),
 P("bh","slice",14,26,{"hi":4,"lo":1}),P("cat","concat",30,26,{"inputs":2}),
 P("led","out",76,12,{"name":"led","width":8})],
"nets":[N("a","sw.y","any.a","m1.in1"),N("b","any.y","m1.sel"),N("c","ev.y","cnt.inc"),N("d","cnt.value","oh.idx"),N("e","oh.y","cat.in1"),N("e2","cat.y","m1.in0"),
 N("f","btn.y","bc.a","bh.a"),N("g","bc.y","m2.sel"),N("h","m1.y","m2.in0"),N("i","ff.y","m2.in1"),
 N("j","bh.y","cat.in0"),N("n","m2.y","led.a")]}
json.dump({"doc":doc},open(sys.argv[1],'w'))
def call(n,b):
    try: r=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8801/api/'+n,data=json.dumps(b).encode(),headers={'Content-Type':'application/json'}))
    except urllib.error.HTTPError as e: print('ERR',e.read().decode()); sys.exit(1)
    return json.loads(r.read())
g=call('codegen',{"doc":doc}); print('problems',[p['message'] for p in g['problems']])
print(json.dumps(call('fpga',{"action":"plan","doc":doc})['map']))
if len(sys.argv)>2:
    j=call('fpga',{"action":"build","program":True,"doc":doc}); seen=0
    while True:
        time.sleep(10); s=call('fpga',{"action":"status","job":j['job']})
        for l in s['log'][seen:]:
            if l.strip() and not l.strip().startswith('*'): print('  ',l[:150])
        seen=len(s['log'])
        if s['state'] in ('programmed','failed'): break
    print('STATE',s['state'])
