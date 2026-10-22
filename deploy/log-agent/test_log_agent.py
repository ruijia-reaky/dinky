import importlib.util
import json
import os
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

spec = importlib.util.spec_from_file_location("log_agent", os.path.join(os.path.dirname(__file__), "log_agent.py"))
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


class LogAgentTest(unittest.TestCase):
    def test_run_identity_is_stable_across_file_rotation(self):
        prefix = "flink-run-20261023T030405123Z-12345678-abcd-1234-abcd-123456789abc-kubernetes-application-0-pod"
        first = agent.run_metadata(prefix + ".log")
        self.assertEqual(first, agent.run_metadata(prefix + ".log.3"))
        self.assertEqual("20261023T030405123Z-12345678-abcd-1234-abcd-123456789abc", first["runId"])
        self.assertEqual(1792724645123, first["runStarted"])
        self.assertEqual({}, agent.run_metadata("flink-old.log.2"))
        self.assertEqual({}, agent.run_metadata(prefix.replace("20261023", "20269999") + ".log"))

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = self.directory.name
        self.relative = "cdc/uid-a_cdc-taskmanager-1-1"
        self.pod_dir = os.path.join(self.root, self.relative)
        os.makedirs(self.pod_dir)
        self.payload = b"old execution log\n" * 100000
        with open(os.path.join(self.pod_dir, "flink.log"), "wb") as output:
            output.write(self.payload)
        with open(os.path.join(self.pod_dir, "credentials.txt"), "w") as output:
            output.write("not a log")
        self.token = "test-token-" * 4
        self.server = agent.Server(("127.0.0.1", 0), self.root, self.token)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def request(self, route, token=None, **query):
        request = Request("http://127.0.0.1:%s%s?%s" %
                          (self.server.server_port, route, urlencode(query)))
        request.add_header("Authorization", "Bearer " + (token or self.token))
        return urlopen(request, timeout=5)

    def assert_status(self, status, route, **query):
        with self.assertRaises(HTTPError) as caught:
            self.request(route, **query)
        self.assertEqual(status, caught.exception.code)
        caught.exception.close()

    def test_file_listing_and_large_stream_after_pod_deletion(self):
        # No live Pod/API server is present at any point.
        with self.request("/logs", directory=self.relative) as response:
            files = json.load(response)
        self.assertEqual(["flink.log"], [item["name"] for item in files])
        self.assertEqual(len(self.payload), files[0]["size"])
        with self.request("/download", directory=self.relative, file="flink.log") as response:
            self.assertEqual(len(self.payload), int(response.headers["Content-Length"]))
            self.assertEqual(self.payload, response.read())

    def test_authentication_required(self):
        self.assert_status(401, "/logs", directory=self.relative, token="incorrect")

    def test_health_probe_does_not_require_or_expose_credentials(self):
        with urlopen("http://127.0.0.1:%s/healthz" % self.server.server_port, timeout=5) as response:
            self.assertEqual(200, response.status)
            self.assertEqual(b"", response.read())

    def test_traversal_and_non_log_files_rejected(self):
        for directory in ("../", "/etc", "cdc/../other", "cdc\\other"):
            self.assert_status(400, "/logs", directory=directory)
        self.assert_status(400, "/download", directory=self.relative, file="../passwd")
        self.assert_status(400, "/download", directory=self.relative, file="credentials.txt")

    def test_missing_file(self):
        self.assert_status(404, "/download", directory=self.relative, file="missing.log")
        self.assert_status(404, "/logs", directory="cdc/deleted")

    def test_rotated_log_and_empty_file(self):
        with open(os.path.join(self.pod_dir, "flink.log.1.gz"), "wb"):
            pass
        with self.request("/download", directory=self.relative, file="flink.log.1.gz") as response:
            self.assertEqual(b"", response.read())

    @unittest.skipIf(os.name == "nt", "Linux symlink permissions are required")
    def test_symlinks_are_rejected(self):
        os.symlink("/etc", os.path.join(self.pod_dir, "escape"))
        self.assert_status(400, "/logs", directory=self.relative + "/escape")
        os.symlink("/etc/passwd", os.path.join(self.pod_dir, "escape.log"))
        self.assert_status(400, "/download", directory=self.relative, file="escape.log")

    def test_weak_token_is_rejected(self):
        with self.assertRaises(ValueError):
            agent.Server(("127.0.0.1", 0), self.root, "short")


if __name__ == "__main__":
    unittest.main()
