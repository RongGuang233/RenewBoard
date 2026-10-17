"""Local real OpenResty/DAV acceptance. No server credentials or real data.
Run with Docker available: python3 tools/tuition-relay/test_openresty.py
Uses the same image tag observed during the previously authorized server check.
"""
import base64
import http.client
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import uuid

SOURCE = Path(__file__).resolve().parent
IMAGE = '1panel/openresty:1.31.1.1-2-4-noble'
READ = 'local-test-read'
WRITE = 'local-test-write'

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()

class OpenRestyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix='renewboard-dav-')
        cls.addClassCleanup(cls.work.cleanup)
        cls.root = Path(cls.work.name)
        cls.root.chmod(0o755)
        cls.name = 'renewboard-dav-test-' + uuid.uuid4().hex[:8]
        cls.addClassCleanup(lambda: subprocess.run(['docker', 'rm', '-f', cls.name], capture_output=True))
        cls.http = (SOURCE / 'http.conf.example').read_text().replace('REPLACE_READ_TOKEN', READ).replace('REPLACE_UPLOAD_TOKEN', WRITE)
        cls.write_config(False)
        docker('run', '-d', '--name', cls.name, '--platform', 'linux/amd64', '-p', '127.0.0.1::8080',
               '-v', f'{cls.root}:/fixture:ro',
               '-v', f'{SOURCE}:/source:ro', '--entrypoint', 'sh', IMAGE, '-c',
               'mkdir -p /www/renewboard-tuition/data /www/renewboard-tuition/tmp /www/renewboard-tuition/logs; '
               'chown nobody /www/renewboard-tuition/data /www/renewboard-tuition/tmp; '
               'chmod 700 /www/renewboard-tuition/data /www/renewboard-tuition/tmp; '
               'exec /usr/local/openresty/bin/openresty -p /www/renewboard-tuition/ -c /fixture/nginx.conf -g "daemon off;"')
        cls.port = int(docker('port', cls.name, '8080/tcp').rsplit(':', 1)[1])
        for _ in range(50):
            try:
                if cls.request('GET', '/health')[0] == 200: break
            except OSError: pass
            time.sleep(.2)
        else: raise RuntimeError(docker('logs', cls.name))
        cls.measure('baseline_existing_worker')
        cls.write_config(True)
        docker('exec', cls.name, '/usr/local/openresty/bin/openresty', '-t', '-p', '/www/renewboard-tuition/', '-c', '/fixture/nginx.conf')
        docker('exec', cls.name, '/usr/local/openresty/bin/openresty', '-s', 'reload', '-p', '/www/renewboard-tuition/', '-c', '/fixture/nginx.conf')
        time.sleep(1)
        cls.measure('route_enabled_idle')
        vector = json.loads((SOURCE / 'test-vector.json').read_text())
        cls.envelope = json.dumps(vector['envelope'], separators=(',', ':')).encode()

    @classmethod
    def write_config(cls, enabled):
        location = (SOURCE / 'location.conf').read_text().replace('/www/renewboard-tuition/validate.lua', '/source/validate.lua').replace('/var/log/nginx/tuition-access.log', '/www/renewboard-tuition/logs/access.log') if enabled else ''
        (cls.root / 'nginx.conf').write_text('''user nobody nogroup;
worker_processes 1;
error_log /www/renewboard-tuition/logs/error.log notice;
pid /www/renewboard-tuition/nginx.pid;
events { worker_connections 64; }
http { access_log off;
access_by_lua_block { ngx.header["X-Fixture-Access-Hook"] = "seen" }
''' + cls.http + '\nserver { listen 8080; location = /health { return 200 "ok"; }\n' + location + '\nlocation / { return 404; }\n} }\n')

    @classmethod
    def measure(cls, label):
        script = '''for pid in 1 $(cat /proc/1/task/1/children); do grep -E '^(Name|Pid|VmRSS|VmHWM):' /proc/$pid/status; done; cat /sys/fs/cgroup/memory.current; cat /sys/fs/cgroup/memory.peak'''
        print('\nMEMORY', label, docker('exec', cls.name, 'sh', '-c', script), flush=True)

    @classmethod
    def request(cls, method, path='/tuition', body=None, token=READ, content_type='application/json', chunked=False):
        conn = http.client.HTTPConnection('127.0.0.1', cls.port, timeout=30)
        try:
            headers = {'Content-Type': content_type}
            if token is not None: headers['Authorization'] = 'Bearer ' + token
            conn.request(method, path, body, headers, encode_chunked=chunked)
            response = conn.getresponse()
            return response.status, response.read(), dict(response.getheaders())
        finally: conn.close()

    def setUp(self):
        self.assertIn(self.request('PUT', body=self.envelope, token=WRITE)[0], (201, 204))

    def assertOld(self):
        self.assertEqual(self.request('GET')[1], self.envelope)

    def test_01_create_replace_download(self):
        docker('exec', self.name, 'rm', '/www/renewboard-tuition/data/latest.json')
        self.assertEqual(self.request('GET')[0], 404)
        self.assertEqual(self.request('PUT', body=self.envelope, token=WRITE)[0], 201)
        self.assertEqual(self.request('PUT', body=self.envelope, token=WRITE)[0], 204)
        status, body, headers = self.request('GET')
        self.assertEqual(status, 200)
        self.assertEqual(body, self.envelope)
        self.assertEqual(headers['Content-Type'], 'application/json')
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(headers['X-Fixture-Access-Hook'], 'seen')
        self.measure('after_small_vector')

    def test_02_auth_method_path(self):
        for method, token, status in [('GET', WRITE, 401), ('PUT', READ, 401), ('GET', None, 401), ('DELETE', WRITE, 405), ('POST', WRITE, 405)]:
            self.assertEqual(self.request(method, token=token, body=self.envelope)[0], status)
        self.assertEqual(self.request('GET', '/data/latest.json')[0], 404)
        self.assertOld()

    def test_03_rejected_bodies_preserve_old(self):
        invalid = [b'{}', b'[]', b'null', b'{', self.envelope.replace(b'"version":1', b'"version":2'), self.envelope.replace(b'"nonce":"', b'"nonce":"!'), self.envelope.replace(b'{', b'{"extra":1,', 1)]
        for body in invalid:
            self.assertEqual(self.request('PUT', body=body, token=WRITE)[0], 400, body[:40])
            self.assertOld()
        for content_type in ['text/plain', 'application/jsonx', '']:
            self.assertEqual(self.request('PUT', body=self.envelope, token=WRITE, content_type=content_type)[0], 415)
            self.assertOld()
        # Chunked overflow also passes through Nginx's native size limit.
        self.assertEqual(self.request('PUT', body=iter([b'x' * 500000] * 5), token=WRITE, chunked=True)[0], 413)
        self.assertOld()

    def test_04_interrupted_upload_keeps_old(self):
        sock = socket.create_connection(('127.0.0.1', self.port))
        sock.sendall(f'PUT /tuition HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {WRITE}\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\n\r\n'.encode() + b'x' * 100000)
        self.assertOld()  # Existing data remains readable while PUT is incomplete.
        sock.close()
        time.sleep(.2)
        self.assertOld()
        self.assertEqual(docker('exec', self.name, 'ls', '-A', '/www/renewboard-tuition/tmp'), '')

    def test_05_native_rename_failure_preserves_old(self):
        # Temp remains writable, destination directory doesn't: fail commit, not body read.
        docker('exec', self.name, 'chmod', '500', '/www/renewboard-tuition/data')
        try:
            new = self.envelope + b'\n'
            self.assertEqual(self.request('PUT', body=new, token=WRITE)[0], 500)
            self.assertOld()
        finally:
            docker('exec', self.name, 'chmod', '700', '/www/renewboard-tuition/data')
        self.assertEqual(docker('exec', self.name, 'ls', '-A', '/www/renewboard-tuition/tmp'), '')

    def test_06_typical_summary_and_atomic_completion(self):
        students = [{'id': f'fake-{i}', 'name': '示例学生', 'status': 'active', 'balanceCents': 170000, 'verified': True} for i in range(100)]
        plaintext = json.dumps({'version': 1, 'publishedAt': '2026-10-17T06:00:00Z', 'students': students}, ensure_ascii=False).encode()
        item = {'version': 1, 'nonce': base64.b64encode(bytes(12)).decode(), 'ciphertext': base64.b64encode(bytes(len(plaintext) + 16)).decode()}
        body = json.dumps(item, separators=(',', ':')).encode()
        print('\nREPRESENTATIVE_BYTES', len(body), flush=True)
        sock = socket.create_connection(('127.0.0.1', self.port))
        sock.sendall(f'PUT /tuition HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {WRITE}\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n'.encode() + body[:10000])
        self.assertOld()
        sock.sendall(body[10000:])
        response = http.client.HTTPResponse(sock)
        response.begin()
        self.assertEqual(response.status, 204)
        response.read()
        sock.close()
        self.assertEqual(self.request('GET')[1], body)
        self.measure('after_100_student_sized_envelope')

    def test_07_maximum_envelope_and_memory(self):
        item = {'version': 1, 'nonce': base64.b64encode(bytes(12)).decode(), 'ciphertext': base64.b64encode(bytes(1499900)).decode()}
        body = json.dumps(item, separators=(',', ':')).encode()
        body += b' ' * (2000000 - len(body))
        self.assertEqual(len(body), 2000000)
        self.assertEqual(self.request('PUT', body=body, token=WRITE)[0], 204)
        self.assertEqual(self.request('GET')[1], body)
        self.measure('after_2MB_envelope')
        # Known-length overflow can be rejected before sending the body.
        conn = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        conn.putrequest('PUT', '/tuition')
        conn.putheader('Authorization', 'Bearer ' + WRITE)
        conn.putheader('Content-Length', '2000001')
        conn.endheaders()
        self.assertEqual(conn.getresponse().status, 413)
        conn.close()
        self.assertEqual(self.request('GET')[1], body)

    def test_08_body_storage_failure_preserves_old(self):
        docker('exec', self.name, 'chmod', '500', '/www/renewboard-tuition/tmp')
        try:
            self.assertEqual(self.request('PUT', body=self.envelope + b'\n', token=WRITE)[0], 500)
            self.assertOld()
        finally:
            docker('exec', self.name, 'chmod', '700', '/www/renewboard-tuition/tmp')

    def test_09_body_timeout_preserves_old(self):
        started = time.monotonic()
        sock = socket.create_connection(('127.0.0.1', self.port), timeout=35)
        try:
            sock.sendall(f'PUT /tuition HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {WRITE}\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\n\r\n'.encode() + b'x' * 100000)
            self.assertOld()
            response = http.client.HTTPResponse(sock)
            try:
                response.begin()
                self.assertEqual(response.status, 408)
                response.read()
            except http.client.RemoteDisconnected:
                # ngx.req.read_body timeout may close without writing an HTTP response.
                self.assertGreaterEqual(time.monotonic() - started, 24)
            log = docker('exec', self.name, 'cat', '/www/renewboard-tuition/logs/access.log')
            self.assertIn(' PUT 408 ', log.splitlines()[-1])
        finally:
            sock.close()
        self.assertOld()
        self.assertEqual(docker('exec', self.name, 'ls', '-A', '/www/renewboard-tuition/tmp'), '')

    @classmethod
    def tearDownClass(cls):
        cls.measure('end_idle')
        print('\nIMAGE', docker('image', 'inspect', IMAGE, '--format', '{{json .RepoDigests}}'))
        print('\nERROR LOG', docker('exec', cls.name, 'cat', '/www/renewboard-tuition/logs/error.log'))
        docker('rm', '-f', cls.name)
        cls.work.cleanup()

if __name__ == '__main__':
    unittest.main(verbosity=2)
