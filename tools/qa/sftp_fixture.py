import base64, copy, io, json, os, pathlib, socketserver, stat, struct, threading, time, zipfile, zlib
import paramiko

BASE=pathlib.Path('/tmp/comics-native-data-qa/files'); BASE.mkdir(parents=True,exist_ok=True)
LOCK=threading.RLock(); ORIGINAL_KEY=paramiko.RSAKey.generate(2048); KEY=ORIGINAL_KEY
STATS={}; DENIED=set(); DELAYS={}; TRANSPORTS=[]
def request_delay(user,operation):
    delay=DELAYS.get((user,operation),0)
    if delay: time.sleep(delay)
def metrics(user):
    return STATS.setdefault(user,{'connections':0,'listings':0,'reads':[],'writes':0,'active':0,'maxActive':0})
def png(color):
    def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
    row=bytes(color)*80
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',80,120,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(b'\0'+row for _ in range(120))))+chunk(b'IEND',b'')
def write_comic(user,version):
    folder=BASE/user/'library/Comics/DC/Sandman'; folder.mkdir(parents=True,exist_ok=True)
    for i in (1,2):
        target=folder/('a.cbz' if i==1 else 'b.cbz')
        with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_STORED) as z:
            z.writestr('ComicInfo.xml',f'<ComicInfo><Title>{user}-v{version}-book{i}</Title><Writer>Fixture Author</Writer><Pages><Page Image="0" Type="FrontCover"/></Pages></ComicInfo>')
            z.writestr('001.png',png((220,50,40) if version==1 else (40,90,220)))
            z.writestr('002.png',png((50,200,70)))
            z.writestr('padding.bin',b'fixture-padding\0'*(32768+version))
        os.utime(target,(1700000000+version,1700000000+version))
def reset_files():
    for user in ('qaA','qaB'):
        write_comic(user,1)
        root=BASE/user
        (root/'outside').mkdir(exist_ok=True)
        (root/'outside/secret.cbz').write_bytes(b'outside configured root')
        link=root/'library/escape'
        if not link.exists(): link.symlink_to('../outside',target_is_directory=True)
        (root/'library/slow.txt').write_text(('Long local fixture text for native pagination.\n'*100000),encoding='utf-8')
        (root/'library/readme.txt').write_text('Simple native library fixture.\n'*6000,encoding='utf-8')
        (root/'library/who?.txt').write_bytes(b'question mark literal\n')
        (root/'library/whoA.txt').write_bytes(b'other wildcard match\n')
reset_files()
class Auth(paramiko.ServerInterface):
    def check_auth_password(self,user,password):
        if user in ('qaA','qaB') and password=='fixture-password' and user not in DENIED:
            self.username=user
            with LOCK: metrics(user)['connections']+=1
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED
    def get_allowed_auths(self,user): return 'password'
    def check_channel_request(self,kind,chanid): return paramiko.OPEN_SUCCEEDED if kind=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
class Handle(paramiko.SFTPHandle):
    def __init__(self,path,user,virtual):
        super().__init__(flags=os.O_RDONLY); self.file=open(path,'rb'); self.user=user; self.virtual=virtual
        with LOCK:
            m=metrics(user); m['active']+=1; m['maxActive']=max(m['maxActive'],m['active'])
    def read(self,offset,length):
        request_delay(self.user,'read')
        self.file.seek(offset); data=self.file.read(length)
        with LOCK: metrics(self.user)['reads'].append({'path':self.virtual,'offset':offset,'length':len(data),'requested':length,'size':os.fstat(self.file.fileno()).st_size,'at':time.monotonic()})
        return data
    def stat(self): return paramiko.SFTPAttributes.from_stat(os.fstat(self.file.fileno()))
    def close(self):
        if not self.file.closed:
            self.file.close()
            with LOCK: metrics(self.user)['active']-=1
        return paramiko.SFTP_OK
class Files(paramiko.SFTPServerInterface):
    def __init__(self,server,*args,**kw): super().__init__(server,*args,**kw); self.user=server.username; self.root=BASE/self.user
    def local(self,path,follow=True):
        p=self.root/path.lstrip('/')
        p=p.resolve() if follow else pathlib.Path(os.path.abspath(p))
        if os.path.commonpath((str(self.root),str(p)))!=str(self.root): raise PermissionError()
        return p
    def canonicalize(self,path):
        request_delay(self.user,'realpath')
        try:
            relative=str(self.local(path).relative_to(self.root))
            return '/' if relative=='.' else '/'+relative
        except Exception: return '/forbidden'
    def list_folder(self,path):
        request_delay(self.user,'list')
        if self.user in DENIED: return paramiko.SFTP_PERMISSION_DENIED
        try:
            with LOCK: metrics(self.user)['listings']+=1
            values=[]
            for p in self.local(path).iterdir():
                a=paramiko.SFTPAttributes.from_stat(p.lstat()); a.filename=p.name; values.append(a)
            return values
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def stat(self,path):
        request_delay(self.user,'stat')
        if self.user in DENIED: return paramiko.SFTP_PERMISSION_DENIED
        try: return paramiko.SFTPAttributes.from_stat(self.local(path).stat())
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def lstat(self,path):
        try: return paramiko.SFTPAttributes.from_stat(self.local(path,False).lstat())
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def readlink(self,path):
        try: return os.readlink(self.local(path,False))
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def open(self,path,flags,attr):
        if flags & (os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC|os.O_APPEND):
            with LOCK: metrics(self.user)['writes']+=1
            return paramiko.SFTP_PERMISSION_DENIED
        if self.user in DENIED: return paramiko.SFTP_PERMISSION_DENIED
        try: return Handle(self.local(path),self.user,path)
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def write_denied(self,*args):
        with LOCK: metrics(self.user)['writes']+=1
        return paramiko.SFTP_PERMISSION_DENIED
    remove=rename=mkdir=rmdir=chattr=symlink=write_denied
class SshHandler(socketserver.BaseRequestHandler):
    def handle(self):
        t=paramiko.Transport(self.request)
        with LOCK: t.add_server_key(KEY); TRANSPORTS.append(t)
        t.set_subsystem_handler('sftp',paramiko.SFTPServer,Files)
        try:
            t.start_server(server=Auth())
            while t.is_active(): time.sleep(.05)
        except Exception: pass
        finally:
            t.close()
            with LOCK:
                if t in TRANSPORTS: TRANSPORTS.remove(t)
class Control(socketserver.StreamRequestHandler):
    def handle(self):
        global KEY
        command=json.loads(self.rfile.readline())
        op=command.get('op','stats')
        if command.get('user') is not None and command['user'] not in ('qaA','qaB'):
            self.wfile.write(b'{"error":"unknown fixture user"}\n'); return
        with LOCK:
            if op=='reset': STATS.clear(); DENIED.clear(); DELAYS.clear(); KEY=ORIGINAL_KEY; reset_files()
            elif op=='deny': DENIED.add(command['user']) if command.get('value',True) else DENIED.discard(command['user'])
            elif op=='delay': DELAYS[(command['user'],command.get('operation','read'))]=float(command['seconds'])
            elif op=='mutate': write_comic(command['user'],int(command['version']))
            elif op=='rotate': KEY=paramiko.RSAKey.generate(2048)
            value=copy.deepcopy(STATS)
        self.wfile.write((json.dumps(value)+'\n').encode())
class Server(socketserver.ThreadingTCPServer): allow_reuse_address=True; daemon_threads=True
ssh=Server(('127.0.0.1',22991),SshHandler); ctl=Server(('127.0.0.1',22992),Control)
threading.Thread(target=ctl.serve_forever,daemon=True).start()
print('SFTP_QA_READY ssh=22991 control=22992',flush=True)
ssh.serve_forever()
