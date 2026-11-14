"""Exercise exact #179 store/reconciliation source with synthetic inventory only."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=root / 'server-reference')
reference = parser.parse_args().source.resolve()
sys.path.insert(0, str(reference / 'src/roborock_local_server/bundled_backend'))
# The existing test's backend import only establishes this module search path.
pkg = ModuleType('roborock_local_server')
pkg.__path__ = []
sys.modules['roborock_local_server'] = pkg
sys.modules['roborock_local_server.backend'] = ModuleType('roborock_local_server.backend')
spec = importlib.util.spec_from_file_location('native_tests', reference / 'tests/test_native_schedule_sync.py')
tests = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tests)
from shared import schedule_store as store

class RestoreInteractionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'web_api_inventory.json'
        self.context = SimpleNamespace(http_jsonl=self.path.parent / 'http.jsonl')

    def seed(self, repeated=True, consumed=False):
        item = tests.job(False, repeated=repeated)
        item['param']['params'][0]['segments'] = '0'
        self.path.write_text(json.dumps({'schedules': {'robot': [item]},
            'schedule_occurrences': {'robot': {'701': 12345} if consumed else {}}}))
        return item

    def test_failed_restore_disable_can_reenable_saved_recurring_job(self):
        item = self.seed()
        changed = store.reconcile_native_enabled(self.context, 'robot', [copy.deepcopy(item)], {'native-1': True})
        self.assertEqual(changed, [701])
        self.assertTrue(store.list_jobs(self.context, 'robot')[0]['enabled'])

    def test_later_confirmed_off_poll_eventually_corrects_the_window(self):
        item = self.seed()
        store.reconcile_native_enabled(self.context, 'robot', [copy.deepcopy(item)], {'native-1': True})
        current = store.list_jobs(self.context, 'robot')
        store.reconcile_native_enabled(self.context, 'robot', current, {'native-1': False})
        self.assertFalse(store.list_jobs(self.context, 'robot')[0]['enabled'])

    def test_consumed_one_time_job_keeps_existing_protection(self):
        item = self.seed(repeated=False, consumed=True)
        self.assertEqual(store.reconcile_native_enabled(self.context, 'robot', [copy.deepcopy(item)], {'native-1': True}), [])
        self.assertFalse(store.list_jobs(self.context, 'robot')[0]['enabled'])

suite = unittest.TestSuite([
    unittest.defaultTestLoader.loadTestsFromTestCase(tests.NativeStoreTests),
    unittest.defaultTestLoader.loadTestsFromTestCase(RestoreInteractionTests),
])
result = unittest.TextTestRunner(verbosity=2).run(suite)
(root / 'server-test-result.json').write_text(json.dumps({'tests': result.testsRun,
    'failures': len(result.failures), 'errors': len(result.errors), 'source': '1927b4307d2ee6ea6771773ecb80cddd549eee64'}, indent=2)+'\n')
sys.exit(not result.wasSuccessful())
