"""Isolated Android document fixture; stdlib only, loopback port 18866.

Run this script, adb reverse tcp:18866 tcp:18866, then run DocumentWorkspaceTest
with instrumentation argument documentFixture=http://127.0.0.1:18866.
No production credentials, source device, database or model requests are used.
"""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import json,hashlib,urllib.parse,base64,zlib,struct

def sample_png():
 width,height=320,120
 raw=b"".join(b"\x00"+bytes([45,110,180])*width for _ in range(height))
 def chunk(name,data):return struct.pack(">I",len(data))+name+data+struct.pack(">I",zlib.crc32(name+data))
 return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",width,height,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(raw))+chunk(b"IEND",b"")


def make_pdf():
 objs=[]
 def obj(s):objs.append(s.encode());return len(objs)
 obj('<< /Type /Catalog /Pages 2 0 R >>')
 obj('<< /Type /Pages /Kids [4 0 R 6 0 R 8 0 R] /Count 3 >>')
 obj('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
 for i in range(3):
  stream=f'BT /F1 24 Tf 60 720 Td (Document page {i+1}) Tj ET\nBT /F1 14 Tf 60 680 Td (PDF reading and zoom verification) Tj ET'.encode()
  obj(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {len(objs)+2} 0 R >>')
  obj(f'<< /Length {len(stream)} >>\nstream\n'+stream.decode()+'\nendstream')
 data=b'%PDF-1.4\n'; offsets=[0]
 for i,o in enumerate(objs,1): offsets.append(len(data));data+=f'{i} 0 obj\n'.encode()+o+b'\nendobj\n'
 start=len(data);data+=f'xref\n0 {len(objs)+1}\n0000000000 65535 f \n'.encode()
 for n in offsets[1:]:data+=f'{n:010d} 00000 n \n'.encode()
 return data+f'trailer\n<< /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()
md='# Rendered report\n\n**Document reading works.**\n\n| Item | Result |\n|---|---|\n| Markdown | Ready |\n\n$$\\begin{aligned}a&=b+c\\\\d&=e+f\\end{aligned}$$\n\n![Sample image](sample.png)\n\n[Open the PDF](report.pdf)\n\n'+''.join(f'## Section {i}\n\nParagraph {i} for long document scrolling and reading position.\n\n' for i in range(1,151))
files={'report.md':md.encode(),'report.pdf':make_pdf(),'sample.png':sample_png(),'notes.txt':b'Plain text file','broken.pdf':b'not a pdf'}
files['long.md'] = md.encode() * 15

class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def send(self,code,data,typ='application/json'):
  if isinstance(data,dict):data=json.dumps(data).encode()
  self.send_response(code);self.send_header('Content-Type',typ);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_POST(self):
  data=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or '{}');path=urllib.parse.urlparse(self.path).path
  if path.endswith('/fs/list'):
   root=data.get('root','/fixture');folder=data.get('path','.')
   return self.send(200,{'ok':True,'result':{'path':'/fixture','entries':[{'name':n,'path':'/fixture/'+n,'type':'file','size':len(v)} for n,v in files.items()]+[{'name':'subfolder','path':'/fixture/subfolder','type':'directory'}]}})
  if path.endswith('/fs/read'):
   name=data.get('path','').rsplit('/',1)[-1];v=files.get(name)
   if v is None:return self.send(404,{'detail':'Missing file'})
   return self.send(200,{'ok':True,'result':{'path':'/fixture/'+name,'name':name,'size':len(v),'sha256':hashlib.sha256(v).hexdigest(),'downloadUrl':'/fixture-download/'+name}})
  self.send(404,{'detail':'Unknown endpoint'})
 def do_GET(self):
  n=urllib.parse.urlparse(self.path).path.rsplit('/',1)[-1]
  if '/sessions/fixture/snapshot' in self.path:
   def item(i,role,text):return {'id':str(i),'sessionId':'fixture','type':'message','status':'done','role':role,'orderSeq':i,'updatedSeq':i,'revision':1,'createdAt':'2026-10-26T10:00:00Z','content':{'kind':'markdown','text':text}}
   return self.send(200,{'session':{'id':'fixture','connectorId':'fixture','projectId':'project','title':'Document workspace','cwd':'/fixture','runtime':'dsh','runtimeName':'DSH','status':'idle','connectorStatus':'online','takeover':False},'state':{'runtime':'dsh','status':'idle'},'timeline':{'items':[item(1,'user','Please prepare a report.'),item(2,'assistant','The report is ready. Open the files below to read it.'),{'id':'delivered','sessionId':'fixture','type':'artifact','status':'done','role':'assistant','orderSeq':3,'createdAt':'2026-10-26T10:01:00Z','content':{'kind':'file','path':'/fixture/report.md','description':'Rendered Markdown report'}}],'nextSeq':3,'hasMore':False},'eventCursor':'seq:0'})
  if n in files:return self.send(200,files[n],'application/octet-stream')
  self.send(404,{'detail':'Missing'})
ThreadingHTTPServer(('127.0.0.1',18866),Handler).serve_forever()
