import copy
import unittest
from idle_check import compare

class IdleCheckTests(unittest.TestCase):
    def state(self):
        return {'live': True, 'result': {'session_id': 'same', 'pid': 42, 'standby': {'ready': True, 'enabled': True}}}

    def test_quiet_event_subscription(self):
        self.assertEqual(compare(self.state(), self.state(), 60)['status'], 'passed')

    def test_brief_capture_between_idle_snapshots_is_detected(self):
        before = self.state(); after = copy.deepcopy(before)
        after['result']['recorder_start_requests_total'] = 1
        after['result']['audio_receive_packets_total'] = 90
        self.assertNotEqual(compare(before, after, 60)['status'], 'passed')

    def test_new_session_with_zero_counts_is_not_a_pass(self):
        before = self.state(); after = self.state(); after['result']['session_id'] = 'new'
        self.assertNotEqual(compare(before, after, 60)['status'], 'passed')

    def test_idle_label_cannot_hide_active_cloud_socket(self):
        before = self.state(); after = self.state(); after['result']['asr_stream_active'] = True
        self.assertNotEqual(compare(before, after, 60)['status'], 'passed')

    def test_cloud_connection_without_upload_is_detected(self):
        before = self.state(); after = self.state(); after['result']['cloud_stream_connections_total'] = 1
        self.assertNotEqual(compare(before, after, 60)['status'], 'passed')
