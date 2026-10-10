"""User-owned registered IR operations. Never dispatch model-generated timings."""
from __future__ import annotations
import asyncio
import json
import re
import sqlite3
import time
import uuid
from pathlib import Path
from .actions import DeviceAction
from .volume_voice import normalize, parse_volume, target_named


def phrase(text):
    if not isinstance(text,str):raise ValueError('Röstfrasen måste vara text')
    value=' '.join(text.casefold().strip(' .!?').split())
    if not 2<=len(value)<=100:raise ValueError('Röstfrasen måste vara 2–100 tecken')
    return value

class RemoteService:
    def __init__(self,settings,config_dir,node=None):
        self.enabled=settings.get('enabled') is True
        self.users=settings.get('allowed_users',[])
        self.lock=asyncio.Lock();self.capture=None
        self.node=node
        self.targets={}
        self.target_nodes={}
        if not self.enabled:return
        if node is None:
            from euthercommand.network_node import NetworkNode
            self.node=NetworkNode(settings['node_config'])
        for key, item in settings.get('tv_targets', {}).items():
            if not re.fullmatch(r'[a-z][a-z0-9_]{0,31}',key):raise ValueError('Ogiltigt TV-id')
            label=self._label(item.get('label'))
            aliases={normalize(a) for a in [label,*item.get('aliases',[])]}
            if any(aliases & t['aliases'] for t in self.targets.values()):raise ValueError('TV-namn måste vara unika')
            self.targets[key]={'label':label,'aliases':aliases}
            if item.get('use_default_node') is True:
                if self.node in self.target_nodes.values():raise ValueError('Standardnoden får bara tillhöra en TV')
                self.target_nodes[key]=self.node
            elif item.get('node_config'):
                from euthercommand.network_node import NetworkNode
                self.target_nodes[key]=NetworkNode(item['node_config'])
        path=Path(settings.get('database',str(config_dir/'state/remotes.sqlite')))
        path.parent.mkdir(parents=True,exist_ok=True)
        self.db=sqlite3.connect(path)
        path.chmod(0o600)
        self.db.executescript('''CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY,owner TEXT,device TEXT,label TEXT,node_command TEXT,signal TEXT);
        CREATE TABLE IF NOT EXISTS aliases(owner TEXT,phrase TEXT,command_id TEXT,UNIQUE(owner,phrase));
        CREATE TABLE IF NOT EXISTS executions(id TEXT PRIMARY KEY,owner TEXT,command_id TEXT,result TEXT);
        CREATE TABLE IF NOT EXISTS confirmations(id TEXT PRIMARY KEY,owner TEXT,responded INTEGER);''')
        for user in self.users:
            for command,label,alias in [('volume_up','Volym upp','höj volymen på logitech'),('volume_down','Volym ned','sänk volymen på logitech')]:
                cid='logitech_'+command+'_'+user
                self.db.execute('INSERT OR IGNORE INTO commands VALUES(?,?,?,?,?,?)',(cid,user,'Logitech ljudsystem',label,command,None))
                self.db.execute('INSERT OR IGNORE INTO aliases VALUES(?,?,?)',(user,alias,cid))
        self.db.commit()
    def start_fast_refresh(self):
        if not self.enabled:return
        async def refresh_node(node):
            while True:
                try:await asyncio.to_thread(node.refresh_fast)
                except Exception:pass # No credentials in logs; send_fast fails closed on stale cache.
                await asyncio.sleep(5)
        async def refresh():
            await asyncio.gather(*(refresh_node(node) for node in dict.fromkeys([self.node,*self.target_nodes.values()])))
        self.fast_refresh_task=asyncio.create_task(refresh())

    async def dispatch_fast(self,user,cid,rid,target_id=None):
        self.require(user)
        node=self.node
        if target_id is not None:
            if target_id not in self.targets:raise ValueError('Okänd TV')
            if target_id not in self.target_nodes:raise ValueError('Ingen IR-sändare är konfigurerad för '+self.targets[target_id]['label'])
            node=self.target_nodes[target_id]
        if not isinstance(rid,str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}',rid):raise ValueError('Ogiltigt begärande-ID')
        # No await in reservation/send: preserve sequence order, no queued hardware jobs.
        row=self.db.execute('SELECT node_command FROM commands WHERE owner=? AND id=?',(user,cid)).fetchone()
        if not row or row[0] not in ('volume_up','volume_down'):raise ValueError('Otillåtet snabbkommando')
        ledger_cid=cid if target_id is None else cid+'@'+target_id
        prior=self.db.execute('SELECT owner,command_id FROM executions WHERE id=?',(rid,)).fetchone()
        if prior:
            if prior!=(user,ledger_cid):raise ValueError('Begärande-ID används redan')
            return {'status':'duplicate','request_id':rid}
        result={'status':'unknown','request_id':rid}
        cur=self.db.execute('INSERT INTO executions VALUES(?,?,?,?)',(rid,user,ledger_cid,json.dumps(result)));self.db.commit()
        # Durable row IDs increase across gateway restart. History must not be purged.
        try:
            node.send_fast(row[0],cur.lastrowid)
            result['status']='dispatched'
        finally:
            self.db.execute('UPDATE executions SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit()
        return result

    def can_use(self,user):return bool(self.enabled and user in self.users)
    def require(self,user):
        if not self.can_use(user):raise ValueError('Åtkomst till fjärrkontroller nekad')
    def listing(self,user):
        self.require(user)
        return [{'id':r[0],'device':r[1],'label':r[2],'aliases':[a[0] for a in self.db.execute('SELECT phrase FROM aliases WHERE owner=? AND command_id=?',(user,r[0]))]} for r in self.db.execute('SELECT id,device,label FROM commands WHERE owner=? ORDER BY device,label',(user,))]
    def plan(self,user,text,node_name,selected_target=None,pending_direction=None):
        if not self.can_use(user):return None
        try:clean=phrase(text)
        except ValueError:return None
        row=self.db.execute('SELECT command_id FROM aliases WHERE owner=? AND phrase=?',(user,clean)).fetchone()
        if row and not row[0].startswith('logitech_volume_'):
            return DeviceAction(str(uuid.uuid4()),'remote.execute',node_name,{'command_id':row[0]},'Jag skickar det registrerade fjärrkommandot.')
        if self.targets:
            intent=parse_volume(text,self.targets)
            answer=target_named(text,self.targets) if pending_direction else None
            if intent or answer:
                direction=intent.direction if intent else pending_direction
                target=(intent.target if intent else answer) or selected_target
                if (intent and intent.unknown_target) or not target:
                    return DeviceAction(str(uuid.uuid4()),'remote.clarify',node_name,{'direction':direction},'Menar du '+ ' eller '.join(t['label'] for t in self.targets.values())+'?')
                if target not in self.target_nodes:
                    return DeviceAction(str(uuid.uuid4()),'remote.unavailable',node_name,{},'Ingen IR-sändare är konfigurerad för '+self.targets[target]['label']+' ännu.')
                return DeviceAction(str(uuid.uuid4()),'remote.volume',node_name,{'command_id':'logitech_volume_'+direction+'_'+user,'target_id':target},'')
        if not row:return None
        return DeviceAction(str(uuid.uuid4()),'remote.execute',node_name,{'command_id':row[0]},'Jag skickar det registrerade fjärrkommandot.')
    async def execute(self,user,cid,rid):
        self.require(user)
        if not isinstance(rid,str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}',rid):raise ValueError('Ogiltigt begärande-ID')
        async with self.lock:
            row=self.db.execute('SELECT node_command FROM commands WHERE owner=? AND id=?',(user,cid)).fetchone()
            if not row:raise ValueError('Kommandot är inte registrerat för dig')
            prior=self.db.execute('SELECT owner,command_id,result FROM executions WHERE id=?',(rid,)).fetchone()
            if prior:
                if prior[:2]!=(user,cid):raise ValueError('Begärande-ID används redan')
                return dict(json.loads(prior[2]),duplicate=True)
            result={'status':'unknown','request_id':rid,'message':'Ingen säker sändningsbekräftelse. Kommandot skickas inte igen automatiskt.'}
            self.db.execute('INSERT INTO executions VALUES(?,?,?,?)',(rid,user,cid,json.dumps(result)));self.db.commit()
            boot=None
            try:
                state=await asyncio.to_thread(self.node.status);boot=state['boot_id']
                if state.get('history_remaining',64)<3:
                    await asyncio.to_thread(self.node.command,'disable',uuid.uuid4().hex,boot)
                    state=await asyncio.to_thread(self.node.request,'POST','/v1/session',{'boot_id':boot})
                    if state.get('status')!='disabled':raise ValueError('Nodsessionen kunde inte förnyas')
                    boot=state['boot_id']
                arm=await asyncio.to_thread(self.node.command,'enable',uuid.uuid4().hex,boot)
                if arm['status']!='enabled':raise ValueError('Sändaren kunde inte aktiveras')
                await asyncio.sleep(.3)
                reply=await asyncio.to_thread(self.node.command,row[0],rid,boot)
                result['status']=reply['status']
                result['message']='IR-kommandot är sänt. Enhetens svar är inte verifierat.' if reply['status']=='transmitted' else 'Kommandot kunde inte bekräftas: '+reply['status']
            except Exception:
                pass # Never expose credentials or automatically retry uncertain sends.
            finally:
                if boot:
                    try:await asyncio.to_thread(self.node.command,'disable',uuid.uuid4().hex,boot)
                    except Exception:pass
            self.db.execute('UPDATE executions SET result=? WHERE id=?',(json.dumps(result),rid));self.db.commit()
            return result
    async def request(self,user,b):
        self.require(user);op=b.get('operation')
        if op=='logitech':
            direction=b.get('direction')
            if direction not in ('up','down'):raise ValueError('Ogiltig volymriktning')
            return await self.dispatch_fast(user,'logitech_volume_'+direction+'_'+user,b['request_id'],b.get('target_id'))
        if op=='execute':return await self.execute(user,b['command_id'],b['request_id'])
        async with self.lock:
            if op=='list':return {'commands':self.listing(user)}
            if op=='learn':
                if self.capture and self.capture['expires']>time.monotonic():raise ValueError('En inspelning pågår redan; vänta högst 60 sekunder')
                value=await asyncio.to_thread(self.node.request,'POST','/v1/learn')
                self.capture={'owner':user,'id':value['capture_id'],'expires':time.monotonic()+60,'signal':None}
                return value
            if op=='cancel':
                self._capture(user,b);self.capture=None
                return {'status':'cancelled'}
            if op=='capture':
                c=self._capture(user,b)
                value=await asyncio.to_thread(self.node.request,'GET','/v1/learn')
                if value.get('capture_id')!=c['id']:raise ValueError('Inspelningen ändrades; börja om')
                if value.get('signal'):
                    from euthercommand.ir import validate,decode
                    c['signal']=validate(value['signal']);c['expires']=time.monotonic()+300;value['metadata']=decode(c['signal'])
                    value['carrier_source']='assumed_receiver_nominal'
                return value
            if op=='save':
                c=self._capture(user,b)
                if not c['signal']:raise ValueError('Ingen signal att spara; läs inspelningen först')
                device=self._label(b.get('device'));label=self._label(b.get('label'));aliases=self._aliases(user,b.get('aliases'))
                cid='ir_'+uuid.uuid4().hex[:12]
                value=await asyncio.to_thread(self.node.request,'POST','/v1/signals',{'name':cid,'signal':c['signal']})
                if value.get('status')!='saved':raise ValueError('Noden kunde inte spara: '+str(value.get('status')))
                self.db.execute('INSERT INTO commands VALUES(?,?,?,?,?,?)',(cid,user,device,label,cid,json.dumps(c['signal'])))
                for a in aliases:self.db.execute('INSERT INTO aliases VALUES(?,?,?)',(user,a,cid))
                self.db.commit();self.capture=None
                return {'status':'saved','commands':self.listing(user)}
            if op=='aliases':
                cid=b['command_id']
                if not self.db.execute('SELECT 1 FROM commands WHERE owner=? AND id=?',(user,cid)).fetchone():raise ValueError('Okänt kommando')
                aliases=self._aliases(user,b.get('aliases'),cid)
                with self.db:
                    self.db.execute('DELETE FROM aliases WHERE owner=? AND command_id=?',(user,cid))
                    for a in aliases:self.db.execute('INSERT INTO aliases VALUES(?,?,?)',(user,a,cid))
                return {'commands':self.listing(user),'status':'saved'}
            if op=='confirm':
                if type(b.get('responded')) is not bool or not self.db.execute('SELECT 1 FROM executions WHERE owner=? AND id=?',(user,b.get('request_id'))).fetchone():raise ValueError('Okänt test')
                self.db.execute('INSERT OR REPLACE INTO confirmations VALUES(?,?,?)',(b['request_id'],user,int(b['responded'])));self.db.commit()
                return {'status':'confirmed','user_reported_response':b['responded']}
            raise ValueError('Okänd fjärråtgärd')
    def _capture(self,user,b):
        c=self.capture
        if not c or c['owner']!=user or c['id']!=b.get('capture_id') or time.monotonic()>=c['expires']:raise ValueError('Inspelningen saknas eller har gått ut')
        return c
    def _aliases(self,user,values,cid=''):
        if not isinstance(values,list) or not 1<=len(values)<=8:raise ValueError('Ange 1–8 röstfraser')
        aliases=list(dict.fromkeys(phrase(v) for v in values))
        for a in aliases:
            row=self.db.execute('SELECT command_id FROM aliases WHERE owner=? AND phrase=?',(user,a)).fetchone()
            if row and row[0]!=cid:raise ValueError('Röstfrasen används redan: '+a)
        return aliases
    @staticmethod
    def _label(value):
        if not isinstance(value,str) or not 1<=len(value.strip())<=64:raise ValueError('Ange ett namn med 1–64 tecken')
        return value.strip()
