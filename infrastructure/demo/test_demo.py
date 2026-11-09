import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('fleet_demo', Path(__file__).with_name('run.py'))
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)

HEADER = 'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n'


class ContractTest(unittest.TestCase):
    def test_inactive_group_can_omit_only_empty_partitions(self):
        text = HEADER + 'group topic 1 2 3 1 - - -\n'
        self.assertEqual(demo.consumer_lag(text, 'group', 'topic', {0: 0, 1: 3, 2: 0}), 1)
        with self.assertRaisesRegex(AssertionError, 'Nonempty partition missing'):
            demo.consumer_lag(text, 'group', 'topic', {0: 1, 1: 3, 2: 0})

    def test_uncommitted_offset_counts_the_entire_nonempty_partition(self):
        text = HEADER + 'group topic 0 - 1 - - - -\n'
        self.assertEqual(demo.consumer_lag(text, 'group', 'topic', {0: 1, 1: 0, 2: 0}), 1)

    def test_malformed_duplicate_wrong_group_and_missing_offsets_fail(self):
        row = 'group topic 0 2 3 1 - - -\n'
        for text, ends in [(row, {0: 3, 1: 0, 2: 0}),
                           (HEADER + row + row, {0: 3, 1: 0, 2: 0}),
                           (HEADER + row.replace('group', 'other'), {0: 3, 1: 0, 2: 0}),
                           (HEADER + row.replace('2 3', '4 3'), {0: 3, 1: 0, 2: 0}),
                           (HEADER + row.replace('2 3', 'bad 3'), {0: 3, 1: 0, 2: 0}),
                           (HEADER + row, {0: 4, 1: 0, 2: 0}),
                           (HEADER + row, {0: 3})]:
            with self.subTest(text=text, ends=ends), self.assertRaises(AssertionError):
                demo.consumer_lag(text, 'group', 'topic', ends)

    def test_topic_offsets_require_all_partitions_and_ignore_cli_logging(self):
        valid = 'Log message\ntopic:0:0\ntopic:1:3\ntopic:2:0\n'
        self.assertEqual(demo.topic_ends(valid, 'topic'), {0: 0, 1: 3, 2: 0})
        for text in ['', valid.replace('topic:2:0', ''), valid.replace('topic:2:0', 'topic:1:0'),
                     valid.replace('topic:1:3', 'topic:1:-1')]:
            with self.subTest(text=text), self.assertRaises(AssertionError):
                demo.topic_ends(text, 'topic')

    def test_state_requires_correlated_vehicle_sequence_timestamp_and_values(self):
        message = dict(vehicleId='vehicle', sequenceNumber=1, observedAt='2026-11-09T08:00:00+00:00',
                       speedKmh=70, engineTemperatureC=120, batteryVoltage=12.8, odometerKm=10001,
                       latitude=41.9, longitude=12.5)
        state = {key: message[key] for key in ('vehicleId', 'speedKmh', 'engineTemperatureC', 'batteryVoltage', 'odometerKm', 'latitude', 'longitude')}
        state.update(lastSequenceNumber=1, lastSeenAt='2026-11-09T08:00:00Z', stale=False)
        demo.compare_state(state, message)
        for key, value in [('vehicleId', 'other'), ('lastSequenceNumber', 0), ('speedKmh', 60),
                           ('engineTemperatureC', 90), ('lastSeenAt', '2026-11-09T08:00:01Z')]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                demo.compare_state({**state, key: value}, message)

    def test_processor_of_another_project_is_rejected(self):
        stack = Mock(project='demo-owned')
        stack.compose.return_value = 'container'
        stack.inspect.return_value = {'Config': {'Labels': {'com.docker.compose.project': 'user-stack'}}}
        scenario = demo.Demo(stack, {})
        with self.assertRaisesRegex(AssertionError, 'outside demo project'):
            scenario.own_processor()
        self.assertEqual(stack.compose.call_args.args, ('ps', '-aq', 'telemetry-processor'))

    def test_checkpoints_pause_only_when_requested_and_persist_links(self):
        with tempfile.TemporaryDirectory() as directory:
            stack = Mock(output=Path(directory), project='demo-owned')
            scenario = demo.Demo(stack, {}, interactive=False)
            scenario.links = {'dashboard': 'http://127.0.0.1:12345/dashboard'}
            scenario.messages = [{'observedAt': '2026-11-09T08:00:00Z'}]
            with patch('builtins.input') as prompt, patch('builtins.print'):
                scenario.checkpoint('nominal', 'ready')
                prompt.assert_not_called()
                scenario.interactive = True
                scenario.checkpoint('alert', 'ready')
                prompt.assert_called_once()
            session = json.loads((stack.output / 'session.json').read_text())
            self.assertEqual(session['phase'], 'alert')
            self.assertTrue(session['linksActive'])
            self.assertEqual(session['links'], scenario.links)
            self.assertEqual(session['historyInterval'], {'from': '2026-11-09 07:59:00', 'to': '2026-11-09 08:01:00'})


class LifecycleTest(unittest.TestCase):
    def execute(self, failure=None, cleanup_error=None, interactive=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        source = root / 'repository'
        source.mkdir()
        (source / '.env.example').write_text('SIMULATOR_ENABLED=false\n')
        stack = Mock(output=root, checkout=source, private=root / 'compose.json',
                     project='demo-owned', evidence=[])
        config = {'services': {'vehicle-simulator': {'environment': {'SIMULATOR_ENABLED': 'false'}}}}
        def command(args, *unused, **kwargs):
            if args[:2] == ['docker', 'compose']:
                (root / 'resolved.log').write_text('temporary config')
                return json.dumps(config)
            return ''
        stack.run.side_effect = command
        stack.cleanup.side_effect = cleanup_error
        scenario = Mock(vehicle_id='vehicle-id', alert_id='alert-id')
        scenario.run.side_effect = failure
        arguments = ['run.py', '--output', str(root / 'unused')]
        if interactive:
            arguments.append('--interactive')
        with patch.object(sys, 'argv', arguments), patch.object(demo.stack_tools, 'Verification', return_value=stack), \
             patch.object(demo.containers, 'snapshot', return_value='source-hash'), \
             patch.object(demo.containers, 'ready', return_value={'api': 'url'}), \
             patch.object(demo.subprocess, 'check_output', return_value='commit\n'), \
             patch.object(demo, 'Demo', return_value=scenario) as constructor, patch('builtins.print'):
            result = demo.main()
        stack.cleanup.assert_called_once()
        self.assertEqual(constructor.call_args.args[-1], interactive)
        return result, root

    def test_success_summary_is_written_only_after_cleanup(self):
        result, root = self.execute(interactive=True)
        self.assertEqual(result, 0)
        summary = json.loads((root / 'summary.json').read_text())
        self.assertTrue(summary['resourcesRemoved'])
        self.assertEqual(summary['messages'], 3)

    def test_scenario_failure_and_keyboard_interrupt_remove_resources_without_success(self):
        for failure in [AssertionError('sample mismatch'), KeyboardInterrupt('Interrupted')]:
            with self.subTest(failure=type(failure).__name__):
                result, root = self.execute(failure=failure)
                self.assertEqual(result, 1)
                self.assertFalse((root / 'summary.json').exists())
                self.assertTrue((root / 'failure.json').exists())

    def test_cleanup_failure_cannot_publish_success(self):
        result, root = self.execute(cleanup_error=RuntimeError('cleanup failed'))
        self.assertEqual(result, 1)
        self.assertFalse((root / 'summary.json').exists())
        self.assertTrue((root / 'cleanup-failure.json').exists())


if __name__ == '__main__':
    unittest.main()
